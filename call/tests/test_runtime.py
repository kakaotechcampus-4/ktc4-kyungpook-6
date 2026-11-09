"""Exercise actual call orchestration with deterministic providers and fake hardware."""
import io
import hashlib
import json
import tempfile
import threading
import time
import unittest
from contextlib import contextmanager, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from verification.hardware.audio import Utterance
from verification.dialogue import Dialogue
from verification.hardware.telemetry import EventLog
from verification.models import Interpretation
from verification.runtime import CallRunner, CallFailure
from verification.runtime import Session
from verification.store import Store
from verification.worker import Worker
from test_verification import request, settings, wav

class FakeProviders:
    def __init__(self, scenario, events, *, fail_tts=False, fail_jev=False):
        self.scenario,self.events=scenario,events
        self.fail_tts,self.fail_jev=fail_tts,fail_jev
        self.inputs=[]; self.states=[]; self.classified=[]

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
        self.classified.append(text); self.states.append(state)
        entry=next((entry for entry in self.scenario if entry['text']==text),{})
        ignored=entry.get('irrelevant',False)
        verdict='IGNORE' if ignored else 'PROCESS' if not overlapping else 'BACKCHANNEL' if text=='네네' else 'INTERRUPT'
        control=entry.get('control','STOP' if '끊어' in text else 'WAIT' if '잠시' in text else 'CONTINUE')
        answer=entry.get('answer','VALUE' if '다온식당' in text or '누구세요' in text else
                         'NO' if text.strip()=='아니요' else 'UNCLEAR' if text.strip()=='음' else 'YES')
        return {'verdict':verdict,'scores':{'irrelevant':.99 if ignored else .01},
                'control':control,'control_confidence':entry.get('control_confidence',.99),
                'answer':answer,'answer_confidence':entry.get('answer_confidence',.99)}

    def interpret(self,dialogue,text):
        self.inputs.append(text); self.states.append(dialogue.state()); self.events.append(('llm',text))
        if '끊어' in text: return Interpretation(control='STOP')
        if '잠시' in text: return Interpretation(control='WAIT')
        if '다온식당' in text: return Interpretation(kind='VALUE',value='다온식당',evidence='다온식당')
        if dialogue.stage=='IDENTITY': return Interpretation(identity='YES')
        return Interpretation(kind='YES')

class CachingFakeProviders(FakeProviders):
    def synthesize(self,text,folder):
        path=folder/(hashlib.sha256(text.encode()).hexdigest()+'.wav')
        if path.exists(): return path
        self.events.append(('tts',text))
        return wav(path,8000)

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
        provider_class=kwargs.pop('provider_class',FakeProviders)
        self.providers=provider_class(scenario,self.events,**kwargs)
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
        jev=[entry for entry in events if entry['stage']=='jev' and entry['status']=='ok']
        self.assertEqual([entry['output']['text'] for entry in stt],['네 관계자입니다','네 맞습니다'])
        self.assertTrue(all(entry['input']['audio_file'].endswith('.wav') for entry in stt))
        self.assertFalse(llm)
        self.assertEqual([entry['output']['answer'] for entry in jev],['YES','YES'])
        names=[e[0] for e in self.events]
        self.assertLess(names.index('tts'),names.index('dial'))
        self.assertLess(names.index('convert_tts'),names.index('dial'))
        self.assertLess(names.index('answer'),names.index('play'))
        self.assertLess(names.index('capture'),names.index('play'))
        self.assertLess(names.index('convert_stt'),names.index('stt'))
        self.assertTrue(self.audio.closed); self.assertTrue(self.modem.closed)
        self.assertEqual(names[-1],'hangup')

    def test_static_templates_reuse_tts_across_calls(self):
        dialogue=Dialogue(request(),self.s.persona_file)
        static_texts=set(dialogue.static_templates().values())
        self.run_call([],provider_class=CachingFakeProviders)
        first_id=self.cid
        first_tts={text for stage,text in self.events if stage=='tts'}
        self.assertTrue(static_texts <= first_tts)
        self.events.clear()
        self.run_call([],provider_class=CachingFakeProviders)
        second_id=self.cid
        second_tts={text for stage,text in self.events if stage=='tts'}
        self.assertEqual(second_tts,first_tts-static_texts)
        self.assertEqual(len(list((self.folder/'tts-static').glob('*.wav'))),len(static_texts))
        for call_id in (first_id,second_id):
            self.assertEqual(len(list((self.folder/'calls'/call_id/'tts').glob('*.wav'))),len(second_tts))

    def test_static_cache_tracks_agent_json_changes(self):
        policy=json.loads(self.s.persona_file.read_text())
        self.s.persona_file=self.folder/'agent.json'
        self.s.persona_file.write_text(json.dumps(policy,ensure_ascii=False))
        self.run_call([],provider_class=CachingFakeProviders)
        policy['templates']['goodbye']='{storeName} 확인을 마칩니다.'
        policy['templates']['timeout']='변경된 시간 초과 안내입니다.'
        policy['templates']['newStatic']='추가된 고정 안내입니다.'
        self.s.persona_file.write_text(json.dumps(policy,ensure_ascii=False))
        self.events.clear()
        self.run_call([],provider_class=CachingFakeProviders)
        dialogue=Dialogue(request(),self.s.persona_file)
        new_static=dialogue.template('newStatic')
        changed_static=dialogue.template('timeout')
        dynamic_goodbye=dialogue.template('goodbye')
        second_tts={text for stage,text in self.events if stage=='tts'}
        self.assertIn(new_static,second_tts)
        self.assertIn(changed_static,second_tts)
        self.assertIn(dynamic_goodbye,second_tts)
        self.assertFalse((set(dialogue.static_templates().values())-{new_static,changed_static}) & second_tts)
        filename=lambda text: hashlib.sha256(text.encode()).hexdigest()+'.wav'
        self.assertTrue((self.folder/'tts-static'/filename(new_static)).exists())
        self.assertTrue((self.folder/'tts-static'/filename(changed_static)).exists())
        self.assertFalse((self.folder/'tts-static'/filename(dynamic_goodbye)).exists())
        self.assertTrue((self.folder/'calls'/self.cid/'tts'/filename(dynamic_goodbye)).exists())

    def test_all_first_question_variants_are_prepared_before_dial(self):
        policy=json.loads(self.s.persona_file.read_text())
        original=policy['templates']['expected']
        policy['templates']['expected']=[original,original.replace('현재 등록된','지금 등록된')]
        self.s.persona_file=self.folder/'agent.json'
        self.s.persona_file.write_text(json.dumps(policy,ensure_ascii=False))
        self.run_call([])
        dialogue=Dialogue(request(),self.s.persona_file)
        dialogue._start_item()
        expected=set(dialogue.question_variants())
        before_dial=self.events[:next(i for i,event in enumerate(self.events) if event[0]=='dial')]
        self.assertTrue(expected <= {text for stage,text in before_dial if stage=='tts'})
        self.assertTrue(set(dialogue.template_variants('llmWait')) <=
                        {text for stage,text in before_dial if stage=='tts'})

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
        self.assertEqual(self.providers.inputs,[])
        self.assertEqual(self.providers.classified,['끊어주세요'])
        self.assertEqual(len([n for n in names if n=='stt']),1)
        self.assertTrue(self.modem.closed)

    def test_irrelevant_transcript_during_playback_does_not_interrupt_or_reach_llm(self):
        result=self.run_call([{'play':1,'text':'감사합니다','irrelevant':True},
                              {'play':1,'text':'네 관계자입니다'},
                              {'play':2,'text':'네 맞습니다'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertEqual(self.providers.inputs,[])
        self.assertEqual(self.providers.classified,['감사합니다','네 관계자입니다','네 맞습니다'])
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
        self.assertEqual(self.providers.inputs,[])
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
        self.assertEqual(self.providers.inputs,[])

    def test_partial_stop_retains_answer(self):
        result=self.run_call([{'play':1,'text':'네 관계자'},{'play':2,'text':'네 맞아요'},{'play':3,'text':'끊어주세요'}],single=False)
        self.assertEqual(result['failureReason'],'USER_REQUESTED_STOP')
        self.assertEqual(len(result['answers']),1)

    def test_wait_resumes_on_ready_signal_and_repeats_question(self):
        result=self.run_call([{'play':1,'text':'잠시만요'},
                              {'play':2,'text':'이제 말씀하세요','answer':'UNCLEAR',
                               'after_playback':True},
                              {'play':3,'text':'네 관계자입니다'},
                              {'play':4,'text':'네 맞습니다'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertEqual(result['answers'][0]['value'],'새봄식당')
        self.assertEqual(self.audio.plays,5)
        self.assertEqual(self.providers.inputs,[])
        acknowledgement='네 답변 가능하실때 다시 답변 부탁드립니다. 잠시 기다리겠습니다.'
        before_dial=self.events[:next(i for i,event in enumerate(self.events) if event[0]=='dial')]
        self.assertIn(('tts',acknowledgement),before_dial)
        log_file=next((self.folder/'calls'/self.cid).glob('call-*.jsonl'))
        entries=[json.loads(line) for line in log_file.read_text().splitlines()]
        self.assertEqual(len([e for e in entries if e['stage']=='turn.wait']),1)
        self.assertEqual(len([e for e in entries if e['stage']=='turn.wait_resumed']),1)
        self.assertEqual([e['text'] for e in entries if e['stage']=='assistant.response'
                          and e['text']==acknowledgement],[acknowledgement])

    def test_wait_expires_after_one_minute_limit(self):
        self.s.wait_timeout_seconds=1
        result=self.run_call([{'play':1,'text':'잠시만요'}])
        self.assertEqual(result['status'],'FAILED')
        self.assertEqual(result['failureReason'],'WAIT_TIMEOUT')
        self.assertEqual(self.audio.plays,3)
        self.assertEqual(self.providers.inputs,[])

    def test_repeated_wait_does_not_extend_deadline(self):
        audio=FakeAudio([],self.events)
        with EventLog(self.folder) as log:
            session=Session(self.s,self.store,FakeProviders([],self.events),audio,
                            Dialogue(request(),self.s.persona_file),
                            log,self.folder,'call',threading.Event())
            session.begin_wait('잠시만요')
            first=session.wait_started
            session.begin_wait('조금만 더 기다려 주세요')
            self.assertEqual(session.wait_started,first)

    def test_wait_accepts_speech_started_before_deadline(self):
        class DelayedSpeechAudio(FakeAudio):
            @property
            def is_speaking(self):
                return time.monotonic() < self.speech_end

            def next_utterance(self,timeout=.05):
                time.sleep(.02)
                if time.monotonic() >= self.speech_end and not self.sent:
                    self.sent=1
                    return Utterance(self.folder/'late.wav',1,.2,time.monotonic(),self.speech_started)
                return None

        self.s.wait_timeout_seconds=1
        audio=DelayedSpeechAudio([],self.events)
        audio.folder=self.folder
        with EventLog(self.folder) as log:
            session=Session(self.s,self.store,FakeProviders([],self.events),audio,
                            Dialogue(request(),self.s.persona_file),
                            log,self.folder,'call',threading.Event())
            session.modem=FakeModem(self.events)
            session.started=time.monotonic()
            session.begin_wait('잠시만요')
            session.wait_started-=.95
            audio.speech_started=time.monotonic()-.05
            audio.speech_end=time.monotonic()+.15
            utterance,reason=session.wait_input()
            self.assertIsNone(reason)
            self.assertEqual(utterance.utterance_id,1)
            self.assertGreater(utterance.completed_at,session.wait_started+1)

    def test_low_confidence_answer_uses_llm(self):
        result=self.run_call([{'play':1,'text':'네 관계자입니다','answer_confidence':.4},
                              {'play':2,'text':'네 맞습니다'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertEqual(self.providers.inputs,['네 관계자입니다'])

    def test_value_turn_speaks_wait_phrase_while_llm_interprets(self):
        result=self.run_call([{'play':1,'text':'네 관계자입니다'},
                              {'play':2,'text':'다온식당이에요'},
                              {'play':4,'text':'네 맞습니다'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertEqual(result['answers'][0]['value'],'다온식당')
        self.assertEqual(self.providers.inputs,['다온식당이에요'])
        llm_index=self.events.index(('llm','다온식당이에요'))
        self.assertLess(self.events.index(('play',3)),llm_index)
        self.assertLess(llm_index,self.events.index(('stop',3)))
        log_file=next((self.folder/'calls'/self.cid).glob('call-*.jsonl'))
        entries=[json.loads(line) for line in log_file.read_text().splitlines()]
        wait_texts=Dialogue(request(),self.s.persona_file).template_variants('llmWait')
        self.assertEqual(len([e for e in entries if e['stage']=='assistant.response'
                              and e['text'] in wait_texts]),1)
        self.assertFalse(any(e['stage']=='turn.stale' for e in entries))

    def test_wait_can_end_with_stop(self):
        result=self.run_call([{'play':1,'text':'잠시만요'},{'play':2,'text':'끊어주세요'}])
        self.assertEqual(result['failureReason'],'USER_REQUESTED_STOP')
        self.assertEqual(self.providers.inputs,[])

    def test_stop_during_four_field_call_never_resumes_questions(self):
        result=self.run_call([{'play':1,'text':'네 관계자'},
                              {'play':2,'text':'끊어주세요'},
                              {'play':3,'text':'네 맞아요'},
                              {'play':3,'text':'네 맞아요'},
                              {'play':3,'text':'네 맞아요'},
                              {'play':3,'text':'네 맞아요'}],single=False)
        self.assertEqual(result['status'],'FAILED')
        self.assertEqual(result['failureReason'],'USER_REQUESTED_STOP')
        self.assertEqual(result['answers'],[])
        self.assertEqual(self.providers.inputs,[])
        self.assertEqual(self.providers.classified,['네 관계자','끊어주세요'])
        self.assertEqual(self.audio.plays,3)

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

    def test_shutdown_during_preparation_never_dials(self):
        req=request()
        cid=self.store.enqueue(req)
        self.store.claim()
        stop=threading.Event()
        providers=FakeProviders([],self.events)
        synthesize=providers.synthesize
        def synthesize_then_stop(text,folder):
            result=synthesize(text,folder)
            stop.set()
            return result
        providers.synthesize=synthesize_then_stop
        runner=CallRunner(self.s,self.store,providers,
            modem_factory=lambda *a,**k:FakeModem(self.events),
            audio_factory=lambda *a,**k:FakeAudio([],self.events))
        with self.assertRaisesRegex(CallFailure,'SERVER_SHUTDOWN'):
            runner(cid,req,stop)
        self.assertNotIn('dial',[event[0] for event in self.events])

    def test_worker_retries_completed_result_when_store_finish_is_busy(self):
        import sqlite3
        req=request()
        cid=self.store.enqueue(req)
        scenario=[{'play':1,'text':'네 관계자'}, {'play':2,'text':'네 맞아요'}]
        audio=FakeAudio(scenario,self.events)
        modem=FakeModem(self.events)
        providers=FakeProviders(scenario,self.events)
        finish=self.store.finish
        attempts=[]
        def flaky_finish(call_id,reason=None):
            attempts.append(reason)
            if len(attempts)==1: raise sqlite3.OperationalError('busy')
            return finish(call_id,reason)
        self.store.finish=flaky_finish
        runner=CallRunner(self.s,self.store,providers,
            modem_factory=lambda *a,**k:modem,audio_factory=lambda *a,**k:audio)
        worker=Worker(self.store,runner,self.folder/'modem.lock')
        with redirect_stdout(io.StringIO()):
            worker.start()
            try:
                deadline=time.monotonic()+3
                while self.store.get(cid)['status']!='COMPLETED' and time.monotonic()<deadline:
                    time.sleep(.01)
                self.assertEqual(self.store.get(cid)['status'],'COMPLETED')
                self.assertEqual(attempts,[None,None])
                self.assertEqual(len(providers.classified),2)
            finally: worker.stop()

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

    def test_stale_reply_updates_last_assistant_and_alternating_history(self):
        result=self.run_call([{'play':1,'text':'네 관계자'},
                              {'play':2,'text':'네 맞아요'},
                              {'play':3,'text':'네', 'started':.001},
                              {'play':4,'text':'끊어주세요'}],single=False)
        self.assertEqual(result['failureReason'],'USER_REQUESTED_STOP')
        repeated=self.providers.states[-1]
        expected=Dialogue(request(False),self.s.persona_file)
        expected.apply('네 관계자',Interpretation(identity='YES'))
        expected.apply('네 맞아요',Interpretation(kind='YES'))
        self.assertEqual(repeated['lastAssistant'],expected.question())
        self.assertEqual([entry['role'] for entry in repeated['history']],
                         ['assistant','user','assistant','user','assistant','user'])

    def test_finished_playback_precedes_call_limit(self):
        audio=FakeAudio([],self.events)
        with EventLog(self.folder) as log:
            session=Session(self.s,self.store,None,audio,Dialogue(request(),self.s.persona_file),
                            log,self.folder,'call',threading.Event())
            session.started=time.monotonic()-self.s.max_call_seconds-1
            self.assertEqual(session.wait_input(finishing=True)[1],'GOODBYE_FINISHED')

    def test_call_limit_during_goodbye_keeps_completed_result(self):
        original=Session.wait_input
        def expire_during_goodbye(session, finishing=False, ignore_input=False):
            if finishing:
                return None, 'CALL_TIME_LIMIT'
            return original(session, finishing=finishing, ignore_input=ignore_input)
        with patch.object(Session,'wait_input',expire_during_goodbye):
            result=self.run_call([{'play':1,'text':'네 관계자'},
                                  {'play':2,'text':'네 맞아요'}])
        self.assertEqual(result['status'],'COMPLETED')
        self.assertIsNone(result['failureReason'])

if __name__=='__main__': unittest.main()
