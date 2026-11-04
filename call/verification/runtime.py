"""One call, continuously recording while STT/Jev/LLM/TTS run."""
import json
import logging
import threading
import time
from pathlib import Path
from .dialogue import Dialogue
from .hardware.audio import PhoneAudio, wav_duration
from .hardware.modem import EP06Modem
from .hardware.telemetry import EventLog

logger = logging.getLogger(__name__)

class CallFailure(Exception):
    pass

class CallRunner:
    def __init__(self, settings, store, providers, *, modem_factory=EP06Modem, audio_factory=PhoneAudio):
        self.s, self.store, self.providers = settings, store, providers
        self.modem_factory, self.audio_factory = modem_factory, audio_factory

    def __call__(self, call_id, request, stop: threading.Event):
        s = self.s
        folder = s.data_dir / 'calls' / call_id
        folder.mkdir(parents=True, exist_ok=True)
        with EventLog(folder) as log:
            dialogue = Dialogue(request, s.persona_file)
            audio = self.audio_factory('', s.max_utterance_seconds, voice_port=s.voice_port,
                silence_seconds=s.silence_seconds, speech_threshold=s.speech_rms_threshold, log=log)
            cache = folder / 'tts'
            cache.mkdir()
            session = Session(s, self.store, self.providers, audio, dialogue, log, cache, call_id, stop)
            # All synthesis AND 8kHz conversion finish before ATD. Failure cannot place a silent call.
            session.prepare(dialogue.last_text)
            for name in ('goodbye','stopped','wrongTarget','timeout','error'):
                session.prepare(dialogue.template(name))
            # First question is deterministic and can be cached before dialing as well.
            original_stage = dialogue.stage
            dialogue._start_item()
            session.prepare(dialogue.question())
            dialogue.stage = original_stage
            if stop.is_set():
                raise CallFailure('SERVER_SHUTDOWN')
            with self.modem_factory(s.at_port, log=log) as modem:
                session.modem = modem
                modem.initialize(reset_before_call=s.ep06_reset_before_call)
                modem.configure_audio(s.ep06_audio_mode,s.ep06_rx_gain,s.ep06_mic_gain,s.ep06_sidetone)
                if stop.is_set():
                    raise CallFailure('SERVER_SHUTDOWN')
                self.store.started(call_id)
                if not modem.dial(request.target.phoneNumber):
                    raise CallFailure('CONNECTION_FAILED')
                with log.span('modem.wait_for_answer'):
                    if not modem.wait_for_answer(seconds=s.answer_timeout):
                        raise CallFailure('NO_ANSWER_OR_REJECTED')
                self.store.connected(call_id)
                with audio.capture(folder):
                    try:
                        reason = session.run()
                    except Exception as exc:
                        # A pre-generated apology is available even when a model/API is failing.
                        log.emit('call.error', error_type=type(exc).__name__)
                        try:
                            session.say_goodbye(dialogue.template('error'))
                        except Exception:
                            pass
                        raise
            # Success only after playback and modem cleanup have finished.
            self.store.finish(call_id, reason)

class Session:
    def __init__(self, settings, store, providers, audio, dialogue, log, folder, call_id, stop):
        self.s, self.store, self.providers, self.audio = settings, store, providers, audio
        self.dialogue, self.log, self.folder, self.call_id, self.stop = dialogue, log, folder, call_id, stop
        self.prepared = {}
        self.playbacks = []
        self.backchannels = []
        self.modem = None
        self.turn = 0
        self.prompt_started = 0.0
        self.started = 0.0

    def prepare(self, text):
        if text not in self.prepared:
            with self.log.span('tts.prepare', turn=self.turn):
                source = self.providers.synthesize(text,self.folder)
                self.prepared[text] = self.audio.prepare_playback(source,self.folder,turn=self.turn)
        return self.prepared[text]

    def play(self, text):
        path = self.prepare(text)
        self.audio.play_prepared(path,turn=self.turn)
        self.prompt_started = time.monotonic()
        self.playbacks.append({'start':self.prompt_started, 'end':self.prompt_started+wav_duration(path),
                               'text':text,'state':self.dialogue.state()})
        self.log.emit('assistant.response',turn=self.turn,text=text)
        print(json.dumps({'call_id':self.call_id,'stage':'assistant.response',
                          'turn':self.turn,'text':text},ensure_ascii=False),flush=True)

    def interrupt(self):
        self.audio.stop_playback()
        if self.playbacks:
            self.playbacks[-1]['end'] = min(self.playbacks[-1]['end'],time.monotonic())

    def overlap(self, utterance):
        # Test at capture time, not after slow STT: playback may have ended by then.
        for speech in reversed(self.playbacks):
            if speech['start'] <= utterance.started_at < speech['end']:
                return speech
        return None

    def active(self):
        return self.modem.call_is_active()

    def say_goodbye(self, text):
        self.interrupt()
        self.play(text)
        last_check = time.monotonic()
        while self.audio.is_playing:
            if time.monotonic()-last_check >= 1:
                if not self.active():
                    return
                last_check = time.monotonic()
            time.sleep(0.03)

    def wait_input(self, finishing=False):
        idle_started = time.monotonic()
        last_check = 0.0
        while True:
            now = time.monotonic()
            if self.stop.is_set():
                raise CallFailure('SERVER_SHUTDOWN')
            if now-self.started >= self.s.max_call_seconds:
                return None, 'CALL_TIME_LIMIT'
            if now-last_check >= 1:
                if not self.active():
                    return None, 'REMOTE_HANGUP'
                last_check = time.monotonic()
            utterance = self.audio.next_utterance(timeout=0.05)
            if utterance is not None:
                return utterance, None
            if finishing and not self.audio.is_playing and not self.audio.is_speaking:
                return None, 'GOODBYE_FINISHED'
            if self.audio.is_playing or self.audio.is_speaking:
                idle_started = time.monotonic()
            elif time.monotonic()-idle_started >= self.s.input_idle_seconds:
                return None, 'INPUT_TIMEOUT'

    def run(self):
        self.started = time.monotonic()
        self.play(self.dialogue.last_text)
        terminal = False
        terminal_reason = None
        handled_turns = 0
        while handled_turns < self.s.max_turns or terminal:
            self.turn += 1
            utterance, reason = self.wait_input(finishing=terminal)
            if reason:
                if reason == 'GOODBYE_FINISHED':
                    return terminal_reason
                if terminal and reason == 'REMOTE_HANGUP':
                    return terminal_reason
                if reason != 'REMOTE_HANGUP':
                    self.say_goodbye(self.dialogue.template('timeout'))
                return reason
            with self.log.span('stt',turn=self.turn) as trace:
                prepared = self.audio.prepare_for_stt(utterance)
                trace['input'] = {'audio_file':prepared.name}
                text = self.providers.transcribe(prepared)
                trace['output'] = {'text':text}
            if text:
                self.log.emit('user.transcript',turn=self.turn,text=text)
                print(json.dumps({'call_id':self.call_id,'stage':'user.transcript',
                                  'turn':self.turn,'text':text},ensure_ascii=False),flush=True)
            if not self.active():
                return terminal_reason if terminal else 'REMOTE_HANGUP'
            if not text:
                continue
            speech = self.overlap(utterance)
            spoken_text = speech['text'] if speech else self.dialogue.last_text
            conversation = speech['state'] if speech else self.dialogue.state()
            with self.log.span('jev',turn=self.turn) as trace:
                trace['input'] = {'utterance':text, 'assistantSpeech':spoken_text,
                                  'conversation':conversation,'overlapping':bool(speech)}
                result = self.providers.classify_utterance(
                    text,spoken_text,conversation,overlapping=bool(speech))
                trace['output'] = result
            verdict = result['verdict']
            if verdict == 'IGNORE':
                self.log.emit('turn.ignored',turn=self.turn,reason='context_irrelevant',text=text)
                continue
            if speech:
                self.log.emit('barge_in',verdict=verdict,turn=self.turn)
                if verdict == 'BACKCHANNEL':
                    self.backchannels.append(text)
                    continue
                self.interrupt()
            handled_turns += 1
            # Include acknowledgments exactly once as part of the next user input.
            combined = ' '.join([*self.backchannels,text])
            self.backchannels.clear()
            with self.log.span('llm.interpret',turn=self.turn) as trace:
                trace['input'] = {'state':self.dialogue.state(), 'transcript':combined}
                decision = self.providers.interpret(self.dialogue,combined)
                trace['output'] = decision.model_dump(mode='json')
            if not self.active():
                return terminal_reason if terminal else 'REMOTE_HANGUP'
            if self.stop.is_set():
                raise CallFailure('SERVER_SHUTDOWN')
            # A delayed 'yes' captured before this prompt must not confirm the next field.
            if utterance.started_at < self.prompt_started and not decision.stop:
                self.log.emit('turn.stale',turn=self.turn)
                self.dialogue.history.append({'role':'user','content':combined})
                self.interrupt()
                self.play(self.dialogue.template("goodbye") if self.dialogue.complete else self.dialogue.question())
                continue
            reply = self.dialogue.apply(combined,decision)
            self.store.save_answers(self.call_id,self.dialogue.answers)
            self.interrupt()
            terminal, terminal_reason = reply.terminal, reply.reason
            self.play(reply.text)
            self.log.emit('turn.response_ready',turn=self.turn,
                since_utterance_end_ms=round((time.monotonic()-utterance.completed_at)*1000,2))
        self.say_goodbye(self.dialogue.template('timeout'))
        return 'TURN_LIMIT'
