"""연속 전화 PCM 수신 + 비동기 재생. 직렬 Voice over USB 지원."""

import os
import queue
import selectors
import subprocess
import tempfile
import threading
import time
import wave
import serial
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

from .telemetry import EventLog
from .vad import FRAME_BYTES, SAMPLE_RATE, SpeechSegmenter, pcm_rms


@dataclass(frozen=True)
class Utterance:
    path: Path
    utterance_id: int
    duration_seconds: float
    completed_at: float
    started_at: float = 0.0


def wav_duration(path: Path) -> float:
    with wave.open(str(path), 'rb') as source:
        return source.getnframes() / source.getframerate()


def terminate_process(process) -> None:
    if process.poll() is None:
        try:
            process.terminate()
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=2)


class SerialPCMPlayer:
    """USB NMEA 포트에 8kHz 16-bit PCM을 100ms 단위로 보냅니다."""

    def __init__(self, port, path: Path):
        self.port = port
        self.path = path
        self.returncode = None
        self.error = ''
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name='phone-serial-playback', daemon=True)
        self._thread.start()

    def _run(self):
        try:
            with wave.open(str(self.path), 'rb') as source:
                if (source.getframerate(), source.getnchannels(), source.getsampwidth()) != (8000, 1, 2):
                    raise ValueError('직렬 재생은 8kHz 16-bit mono PCM이 필요합니다.')
                deadline = time.monotonic()
                while not self._stop.is_set():
                    chunk = source.readframes(800)
                    if not chunk:
                        break
                    if len(chunk) < 1600:
                        chunk = chunk.ljust(1600, b'\0')
                    if self.port.write(chunk) != len(chunk):
                        raise RuntimeError("직렬 PCM 데이터 일부만 전송되었습니다.")
                    deadline += 0.1
                    self._stop.wait(max(0, deadline - time.monotonic()))
            self.returncode = 0
        except Exception as exc:
            self.error = str(exc)
            self.returncode = 1

    def poll(self):
        return self.returncode

    def terminate(self):
        self._stop.set()

    def wait(self, timeout=None):
        self._thread.join(timeout)
        if self._thread.is_alive():
            raise subprocess.TimeoutExpired('serial playback', timeout)
        return self.returncode

    def kill(self):
        self.terminate()


class PhoneAudio:
    def __init__(self, device: str, max_utterance_seconds: float = 30, *,
                 capture_device: str | None = None, playback_device: str | None = None,
                 silence_seconds: float = 1.5, speech_threshold: float = 500,
                 log: EventLog | None = None, voice_port: str | None = None):
        self.voice_port = voice_port
        self.capture_device = capture_device or device
        self.playback_device = playback_device or device
        self.max_utterance_seconds = max_utterance_seconds
        self.silence_seconds = silence_seconds
        self.speech_threshold = speech_threshold
        self.log = log if log is not None else EventLog()
        self._capture = None
        self._capture_stderr = None
        self._reader = None
        self._player = None
        self._player_stderr = None
        self._player_fields = {}
        self._player_started = 0.0
        self._player_duration = 0.0
        self._stop = threading.Event()
        self._ready = threading.Event()
        self._speaking = threading.Event()
        self._error = None
        self._utterances = queue.Queue(maxsize=20)

    @contextmanager
    def capture(self, folder: Path):
        """첫 PCM 프레임을 확인한 뒤 반환하고, 컨텍스트 종료 시 모든 프로세스를 정리."""
        try:
            self._start_capture(folder)
            yield self
        finally:
            self.close()

    def _start_capture(self, folder: Path) -> None:
        if self._capture is not None:
            raise RuntimeError('통화 녹음이 이미 실행 중입니다.')
        self._stop.clear()
        self._ready.clear()
        self._speaking.clear()
        self._error = None
        self._utterances = queue.Queue(maxsize=20)
        with self.log.span('audio.capture_start', device=self.voice_port or self.capture_device):
            if self.voice_port:
                self._capture = serial.Serial(self.voice_port, 115200, timeout=0.2, write_timeout=2)
            else:
                self._capture_stderr = tempfile.TemporaryFile()
                self._capture = subprocess.Popen(
                    ['arecord', '-q', '-D', self.capture_device, '-t', 'raw',
                     '-f', 'S16_LE', '-r', str(SAMPLE_RATE), '-c', '1',
                     '--buffer-time=100000', '--period-time=20000'],
                    stdout=subprocess.PIPE, stderr=self._capture_stderr, bufsize=0,
                )
            self._reader = threading.Thread(target=self._read_pcm, args=(folder,),
                                            name='phone-capture', daemon=True)
            self._reader.start()
            if not self._ready.wait(timeout=6):
                raise RuntimeError('전화 PCM 포트에서 입력을 받지 못했습니다. VOICE_PORT와 QPCMV 상태를 확인하세요.')
            self.check_health()

    def _capture_error(self, message: str) -> RuntimeError:
        if self.voice_port:
            return RuntimeError(f'{message}: {self.voice_port}')
        self._capture_stderr.seek(0)
        detail = self._capture_stderr.read(4000).decode(errors='replace').strip()
        return RuntimeError(f'{message}: {detail}')

    def _read_pcm(self, folder: Path) -> None:
        segmenter = SpeechSegmenter(self.speech_threshold, self.silence_seconds,
                                    self.max_utterance_seconds)
        pending = bytearray()
        sequence = 0
        speech_started = 0.0
        last_data = time.monotonic()
        level_started = last_data
        level_peak = 0.0
        # 임계값 조정을 위해 1초 간격으로 입력 RMS peak를 기록합니다.
        try:
            with selectors.DefaultSelector() as selector:
                stream = self._capture if self.voice_port else self._capture.stdout
                selector.register(stream, selectors.EVENT_READ)
                while not self._stop.is_set():
                    if not selector.select(timeout=0.2):
                        if time.monotonic() - last_data > 5:
                            raise self._capture_error('PCM 입력이 5초 이상 중단되었습니다')
                        continue
                    data = os.read(stream.fileno(), 4096)
                    if not data:
                        if self._stop.is_set():
                            return
                        raise self._capture_error('PCM 스트림이 종료되었습니다')
                    last_data = time.monotonic()
                    pending.extend(data)
                    while len(pending) >= FRAME_BYTES:
                        frame = bytes(pending[:FRAME_BYTES])
                        del pending[:FRAME_BYTES]
                        self._ready.set()
                        was_active = segmenter.active
                        result = segmenter.feed(frame)
                        if segmenter.active:
                            self._speaking.set()
                        else:
                            self._speaking.clear()
                        level_peak = max(level_peak, pcm_rms(frame))
                        if not was_active and segmenter.active:
                            speech_started = time.monotonic() - 0.12
                            self.log.emit('audio.speech_start', utterance_id=sequence + 1)
                        if time.monotonic() - level_started >= 1:
                            self.log.emit('audio.input_level', peak_rms=round(level_peak, 1),
                                          threshold=self.speech_threshold)
                            level_started, level_peak = time.monotonic(), 0.0
                        if result is None:
                            continue
                        sequence += 1
                        completed_at = time.monotonic()
                        path = folder / f'utterance-{sequence:04d}.wav'
                        with wave.open(str(path), 'wb') as output:
                            output.setnchannels(1)
                            output.setsampwidth(2)
                            output.setframerate(SAMPLE_RATE)
                            output.writeframes(result.pcm)
                        utterance = Utterance(path, sequence, result.duration_seconds, completed_at, speech_started)
                        try:
                            self._utterances.put_nowait(utterance)
                        except queue.Full as exc:
                            raise RuntimeError('처리 대기 발화가 20개를 초과했습니다.') from exc
                        self.log.emit('audio.utterance', utterance_id=sequence,
                                      audio_seconds=result.duration_seconds,
                                      voiced_seconds=result.voiced_seconds,
                                      silence_seconds=self.silence_seconds,
                                      peak_rms=round(result.peak_rms, 1), end_reason=result.reason,
                                      queue_depth=self._utterances.qsize())
        except Exception as exc:
            if not self._stop.is_set():
                self._error = exc
                self.log.emit('audio.capture', status='error', error_type=type(exc).__name__,
                              error=str(exc))
        finally:
            self._ready.set()

    def next_utterance(self, timeout: float = 0.2) -> Utterance | None:
        self.check_health()
        try:
            utterance = self._utterances.get(timeout=timeout)
        except queue.Empty:
            self.check_health()
            return None
        self.log.emit('audio.dequeue', utterance_id=utterance.utterance_id,
                      queue_wait_ms=round((time.monotonic() - utterance.completed_at) * 1000, 2))
        return utterance

    def prepare_for_stt(self, utterance: Utterance) -> Path:
        clean_path = utterance.path.with_name(utterance.path.stem + '-16k.wav')
        with self.log.span('audio.stt_conversion', utterance_id=utterance.utterance_id):
            subprocess.run(
                ['ffmpeg', '-nostdin', '-y', '-loglevel', 'error', '-i', str(utterance.path),
                 '-af', 'highpass=f=100,lowpass=f=3600,afftdn=nr=10',
                 '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', str(clean_path)],
                check=True, timeout=30,
            )
        return clean_path

    def prepare_playback(self, source: Path, folder: Path, *, turn: int = 0) -> Path:
        """Predecode before dialing; content cache avoids repeated FFmpeg work."""
        phone_path = source.with_suffix('.8k.wav')
        if not phone_path.exists():
            with self.log.span('audio.tts_conversion', turn=turn) as result:
                descriptor, temporary = tempfile.mkstemp(prefix=phone_path.stem + '.',
                                                          suffix='.wav', dir=phone_path.parent)
                os.close(descriptor)
                try:
                    subprocess.run(
                        ['ffmpeg', '-nostdin', '-y', '-loglevel', 'error', '-i', str(source),
                         '-ar', '8000', '-ac', '1', '-c:a', 'pcm_s16le', temporary],
                        check=True, timeout=30,
                    )
                    result['audio_seconds'] = wav_duration(Path(temporary))
                    os.replace(temporary, phone_path)
                finally:
                    Path(temporary).unlink(missing_ok=True)
        return phone_path

    def play(self, source: Path, folder: Path, *, turn: int = 0) -> None:
        self.play_prepared(self.prepare_playback(source, folder, turn=turn), turn=turn)

    def play_prepared(self, phone_path: Path, *, turn: int = 0) -> None:
        self.stop_playback()
        self._player_duration = wav_duration(phone_path)
        self._player_stderr = None if self.voice_port else tempfile.TemporaryFile()
        self._player_started = time.monotonic()
        self._player_fields = {'turn': turn, 'audio_seconds': self._player_duration}
        self.log.emit('audio.playback', status='started', **self._player_fields)
        try:
            if self.voice_port:
                self._player = SerialPCMPlayer(self._capture, phone_path)
            else:
                self._player = subprocess.Popen(
                    ['aplay', '-q', '-D', self.playback_device, str(phone_path)],
                    stdout=subprocess.DEVNULL, stderr=self._player_stderr,
                )
        except BaseException as exc:
            self.log.emit('audio.playback', status='error', error=str(exc), **self._player_fields)
            if self._player_stderr is not None:
                self._player_stderr.close()
            self._player_stderr = None
            raise

    def _finish_playback(self, interrupted=False) -> None:
        code = self._player.returncode
        if self.voice_port:
            error = self._player.error
        else:
            self._player_stderr.seek(0)
            error = self._player_stderr.read(4000).decode(errors='replace').strip()
            self._player_stderr.close()
        self._player_stderr = None
        self._player = None
        status = 'interrupted' if interrupted else ('ok' if code == 0 else 'error')
        self.log.emit('audio.playback', status=status, returncode=code,
                      duration_ms=round((time.monotonic() - self._player_started) * 1000, 2),
                      error=error, **self._player_fields)
        if not interrupted and code != 0:
            raise RuntimeError(f'전화 오디오 재생 실패 ({code}): {error}')

    def check_health(self) -> None:
        if self._error is not None:
            raise RuntimeError('통화 입력 처리 실패') from self._error
        if self._player is not None:
            if self._player.poll() is not None:
                self._finish_playback()
            elif time.monotonic() - self._player_started > self._player_duration + 5:
                self.stop_playback()
                raise RuntimeError('전화 오디오 재생 시간이 제한을 초과했습니다.')

    @property
    def is_speaking(self) -> bool:
        return self._speaking.is_set()

    @property
    def is_playing(self) -> bool:
        self.check_health()
        return self._player is not None

    def stop_playback(self) -> None:
        if self._player is not None:
            interrupted = self._player.poll() is None
            terminate_process(self._player)
            self._finish_playback(interrupted=interrupted)

    def close(self) -> None:
        self._stop.set()
        self._speaking.clear()
        try:
            self.stop_playback()
        finally:
            try:
                if self._capture is not None:
                    if not self.voice_port:
                        terminate_process(self._capture)
                if self._reader is not None:
                    self._reader.join(timeout=3)
                    if self._reader.is_alive():
                        raise RuntimeError('녹음 스레드를 종료하지 못했습니다.')
            finally:
                # A live reader may still be inside os.read; never close or reuse its fd.
                if self._reader is None or not self._reader.is_alive():
                    if self._capture is not None:
                        if self.voice_port:
                            self._capture.close()
                        else:
                            self._capture.stdout.close()
                    if self._capture_stderr is not None:
                        self._capture_stderr.close()
                    self._capture = self._reader = self._capture_stderr = None
                    self.log.emit('audio.capture_stop')
