"""Exercise actual call orchestration with deterministic providers and fake hardware."""
import io
import json
import tempfile
import threading
import time
import unittest
from contextlib import contextmanager, redirect_stdout
from pathlib import Path

from verification.hardware.audio import Utterance
from verification.models import Interpretation
from verification.runtime import CallRunner, CallFailure
from verification.store import Store
from test_verification import request, settings, wav

class FakeProviders:
    def __init__(self, scenario, events, *, fail_tts=False, fail_jev=False):
        self.scenario,self.events=scenario,events
        self.fail_tts,self.fail_jev=fail_tts,fail_jev
        self.inputs=[]

    def synthesize(self,text,folder):
        self.events.append(('tts',text))
        if self.fail_tts: raise RuntimeError('TTS offline')
        return wav(folder/f'voice-{len(self.events)}.wav',8000)

    def transcribe(self,path):
        self.events.append(('stt',path.name))
        return self.scenario[int(path.stem.split('-')[1])-1]['text']

    def classify_utterance(self,text,spoken,state,*,overlapping=False):
        self.events.append(('jev',text))
        if self.fail_jev: raise RuntimeError('Jev offline')
        ignored=any(entry['text']==text and entry.get('irrelevant') for entry in self.scenario)
        verdict='IGNORE' if ignored else 'PROCESS' if not overlapping else 'BACKCHANNEL' if text=='네네' else 'INTERRUPT'
        return {'verdict':verdict,'scores':{'irrelevant':.99 if ignored else .01}}

    def interpret(self,dialogue,text):
        self.inputs.append(text); self.events.append(('llm',text))
        if '끊어' in text: return Interpretation(stop=True)
        if '다온식당' in text: return Interpretation(kind='VALUE',value='다온식당',evidence='다온식당')
        if dialogue.stage=='IDENTITY': return Interpretation(identity='YES')
        return Interpretation(kind='YES')

class FakeAudio:
    def __init__(self,scenario,events):
        self.scenario,self.events=scenario,events
        self.plays=0; self.sent=0; self.started=0.; self.ends=0.; self.closed=False
        self.playing=False; self.folder=None

    def prepare_playback(self,source,folder,turn=0):
        self.events.append(('convert_tts',source.name))
        return source

    @contextmanager
    def capture(self,folder):
        self.folder=folder
        self.events.append(('capture',None))
        try: yield self
        finally:
            self.closed=True
            self.events.append(('audio_closed',None))

    def play_prepared(self,path,turn=0):
        self.plays+=1; self.started=time.monotonic(); self.ends=self.started+.06
        self.playing=True; self.events.append(('play',self.plays))

    def stop_playback(self):
        self.playing=False; self.events.append(('stop',self.plays))

    @property
    def is_playing(self):
        return self.playing and time.monotonic()<self.ends

    @property
    def is_speaking(self): return False

    def next_utterance(self,timeout=.05):
        if self.sent<len(self.scenario):
            entry=self.scenario[self.sent]
            if self.plays>=entry['play']:
                self.sent+=1
                started=entry.get('started',self.started+1.001 if entry.get('after_playback') else self.started+.001)
                return Utterance(self.folder/f'u-{self.sent}.wav',self.sent,.5,time.monotonic(),started)
        time.sleep(.005)
        return None

    def prepare_for_stt(self,u):
        self.events.append(('convert_stt',u.utterance_id))
        return u.path

class FakeModem:
    def __init__(self,events,answer=True,active=True):
        self.events=events; self.answer=answer; self.active=active; self.closed=False
    def __enter__(self): self.events.append(('modem_open',None)); return self
    def __exit__(self,*args): self.closed=True; self.events.append(('hangup',None))
    def initialize(self,**kwargs): self.events.append(('initialize',None))
    def configure_audio(self,*args): self.events.append(('configure',args))
    def dial(self,number): self.events.append(('dial',number)); return True
    def wait_for_answer(self,seconds): self.events.append(('answer',None)); return self.answer
    def call_is_active(self): return self.active

class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.folder=Path(self.tmp.name); self.events=[]
        self.s=settings(self.folder); self.s.input_idle_seconds=.03
        self.store=Store(self.folder/'calls.db')

    def run_call(self,scenario,single=True,**kwargs):
        req=request(single)
        cid=self.store.enqueue(req); self.store.claim()
        self.audio=FakeAudio(scenario,self.events)
        self.modem=FakeModem(self.events,answer=kwargs.pop('answer',True),active=kwargs.pop('active',True))
        self.providers=FakeProviders(scenario,self.events,**kwargs)
        self.runner=CallRunner(self.s,self.store,self.providers,
            modem_factory=lambda *a,**k:self.modem,audio_factory=lambda *a,**k:self.audio)
        self.cid=cid
        self.runner(cid,req,threading.Event())
        return self.store.get(cid)

    def test_complete_call_prewarms_before_dial_and_hangs_up(self):
        console=io.StringIO()
        with redirect_stdout(console):
            result=self.run_call([{'play':1,'text':'네 관계자입니다'},{'play':2,'text':'네 맞습니다'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertEqual(result['answers'][0]['value'],'새봄식당')
        content_logs=[json.loads(line) for line in console.getvalue().splitlines()]
        self.assertEqual([entry['text'] for entry in content_logs if entry['stage']=='user.transcript'],
                         ['네 관계자입니다','네 맞습니다'])
        self.assertTrue(any(entry['stage']=='assistant.response' for entry in content_logs))
        self.assertEqual({entry['stage'] for entry in content_logs},
                         {'assistant.response','user.transcript'})
        self.assertEqual({entry['call_id'] for entry in content_logs},{self.cid})
        log_file=next((self.folder/'calls'/self.cid).glob('call-*.jsonl'))
        events=[json.loads(line) for line in log_file.read_text().splitlines()]
        stt=[entry for entry in events if entry['stage']=='stt' and entry['status']=='ok']
        llm=[entry for entry in events if entry['stage']=='llm.interpret' and entry['status']=='ok']
        self.assertEqual([entry['output']['text'] for entry in stt],['네 관계자입니다','네 맞습니다'])
        self.assertTrue(all(entry['input']['audio_file'].endswith('.wav') for entry in stt))
        self.assertEqual(llm[0]['input']['transcript'],'네 관계자입니다')
        self.assertEqual(llm[0]['output']['identity'],'YES')
        names=[e[0] for e in self.events]
        self.assertLess(names.index('tts'),names.index('dial'))
        self.assertLess(names.index('convert_tts'),names.index('dial'))
        self.assertLess(names.index('answer'),names.index('play'))
        self.assertLess(names.index('capture'),names.index('play'))
        self.assertLess(names.index('convert_stt'),names.index('stt'))
        self.assertTrue(self.audio.closed); self.assertTrue(self.modem.closed)
        self.assertEqual(names[-1],'hangup')

    def test_backchannel_is_preserved_without_stopping_playback(self):
        result=self.run_call([{'play':1,'text':'네네'},{'play':1,'text':'네 관계자입니다'},{'play':2,'text':'네 맞습니다'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertEqual(self.providers.inputs[0],'네네 네 관계자입니다')
        first_jev=self.events.index(('jev','네네'))
        second_stt=next(i for i,e in enumerate(self.events) if e==('stt','u-2.wav'))
        self.assertNotIn('stop',[e[0] for e in self.events[first_jev:second_stt]])
        self.assertEqual(sum('네네' in t for t in self.providers.inputs),1)
        log_file=next((self.folder/'calls'/self.cid).glob('call-*.jsonl'))
        entries=[json.loads(line) for line in log_file.read_text().splitlines()]
        jev=next(entry for entry in entries if entry['stage']=='jev' and entry['status']=='ok')
        self.assertEqual(jev['input']['utterance'],'네네')
        self.assertIn('assistantSpeech',jev['input'])
        self.assertIn('conversation',jev['input'])
        self.assertEqual(jev['output']['verdict'],'BACKCHANNEL')

    def test_interrupt_stops_only_after_jev_and_reuses_transcript(self):
        result=self.run_call([{'play':1,'text':'끊어주세요'}])
        self.assertEqual(result['status'],'FAILED')
        self.assertEqual(result['failureReason'],'USER_REQUESTED_STOP')
        names=[e[0] for e in self.events]
        self.assertLess(names.index('stt'),names.index('jev'))
        self.assertLess(names.index('jev'),names.index('stop'))
        self.assertEqual(self.providers.inputs,['끊어주세요'])
        self.assertEqual(len([n for n in names if n=='stt']),1)
        self.assertTrue(self.modem.closed)

    def test_irrelevant_transcript_during_playback_does_not_interrupt_or_reach_llm(self):
        result=self.run_call([{'play':1,'text':'감사합니다','irrelevant':True},
                              {'play':1,'text':'네 관계자입니다'},
                              {'play':2,'text':'네 맞습니다'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertEqual(self.providers.inputs,['네 관계자입니다','네 맞습니다'])
        first_jev=self.events.index(('jev','감사합니다'))
        second_stt=next(i for i,e in enumerate(self.events) if e==('stt','u-2.wav'))
        self.assertNotIn('stop',[e[0] for e in self.events[first_jev:second_stt]])
        log_file=next((self.folder/'calls'/self.cid).glob('call-*.jsonl'))
        entries=[json.loads(line) for line in log_file.read_text().splitlines()]
        self.assertTrue(any(e['stage']=='turn.ignored' and e['text']=='감사합니다' for e in entries))

    def test_irrelevant_transcript_after_playback_is_ignored(self):
        result=self.run_call([{'play':1,'text':'감사합니다','irrelevant':True,'after_playback':True},
                              {'play':1,'text':'네 관계자입니다'},
                              {'play':2,'text':'네 맞습니다'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertNotIn('감사합니다',self.providers.inputs)
        log_file=next((self.folder/'calls'/self.cid).glob('call-*.jsonl'))
        entries=[json.loads(line) for line in log_file.read_text().splitlines()]
        jev=next(e for e in entries if e['stage']=='jev' and e['status']=='ok')
        self.assertFalse(jev['input']['overlapping'])
        self.assertEqual(jev['output']['verdict'],'IGNORE')

    def test_irrelevant_transcripts_do_not_exhaust_turn_limit(self):
        self.s.max_turns=2
        result=self.run_call([{'play':1,'text':'감사합니다','irrelevant':True},
                              {'play':1,'text':'감사합니다','irrelevant':True},
                              {'play':1,'text':'네 관계자입니다'},
                              {'play':2,'text':'네 맞습니다'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertEqual(self.providers.inputs,['네 관계자입니다','네 맞습니다'])

    def test_partial_stop_retains_answer(self):
        result=self.run_call([{'play':1,'text':'네 관계자'},{'play':2,'text':'네 맞아요'},{'play':3,'text':'끊어주세요'}],single=False)
        self.assertEqual(result['failureReason'],'USER_REQUESTED_STOP')
        self.assertEqual(len(result['answers']),1)

    def test_stop_after_all_answers_is_completed(self):
        result=self.run_call([{'play':1,'text':'네 관계자'},{'play':2,'text':'네 맞아요'},{'play':3,'text':'끊어주세요'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertEqual(len(result['answers']),1)

    def test_correction_during_final_readback_reopens_last_answer(self):
        result=self.run_call([{'play':1,'text':'네 관계자'},{'play':2,'text':'네 맞아요'},
                             {'play':3,'text':'아니요 다온식당이에요'},{'play':4,'text':'네 맞아요'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertEqual(result['answers'][0]['value'],'다온식당')

    def test_tts_preparation_failure_never_dials(self):
        with self.assertRaises(RuntimeError): self.run_call([],fail_tts=True)
        self.assertNotIn('dial',[e[0] for e in self.events])

    def test_no_answer_releases_modem(self):
        with self.assertRaisesRegex(CallFailure,'NO_ANSWER_OR_REJECTED'): self.run_call([],answer=False)
        self.assertTrue(self.modem.closed)
        self.assertFalse(self.audio.closed) # never opened
        self.assertNotIn('play',[e[0] for e in self.events])

    def test_no_response_says_goodbye_and_fails(self):
        result=self.run_call([])
        self.assertEqual(result['failureReason'],'INPUT_TIMEOUT')
        self.assertEqual(len([e for e in self.events if e[0]=='play']),2)
        self.assertTrue(self.modem.closed)

    def test_remote_hangup_fails_and_cleans_up(self):
        result=self.run_call([],active=False)
        self.assertEqual(result['failureReason'],'REMOTE_HANGUP')
        self.assertTrue(self.audio.closed); self.assertTrue(self.modem.closed)

    def test_jev_failure_uses_cached_apology_and_cleans_up(self):
        with self.assertRaises(RuntimeError): self.run_call([{'play':1,'text':'끊어주세요'}],fail_jev=True)
        self.assertTrue(self.modem.closed); self.assertTrue(self.audio.closed)
        self.assertEqual(len([e for e in self.events if e[0]=='play']),2)

    def test_stale_yes_does_not_confirm_new_field(self):
        result=self.run_call([{'play':1,'text':'네 관계자'},{'play':2,'text':'네', 'started':.001},
                             {'play':3,'text':'네 맞아요'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertEqual(len(result['answers']),1)
        self.assertEqual(self.audio.plays,4) # greeting, question, clarification, goodbye

if __name__=='__main__': unittest.main()
