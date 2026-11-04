import json
import math
import shutil
import os
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

from verification.hardware.audio import PhoneAudio, Utterance
from verification.hardware.telemetry import EventLog
from verification.hardware.vad import FRAME_BYTES, SpeechSegmenter, pcm_rms

SILENCE = bytes(FRAME_BYTES)
VOICE = struct.pack('<160h', *([1000] * 160))


def write_wav(path, frames=8000):
    with wave.open(str(path), 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(8000)
        output.writeframes(bytes(frames * 2))


class VADTests(unittest.TestCase):
    def feed(self, detector, frame, count):
        return [result for _ in range(count) if (result := detector.feed(frame)) is not None]

    def test_silence_and_brief_noise_do_not_produce_utterance(self):
        detector = SpeechSegmenter()
        self.assertEqual(self.feed(detector, SILENCE, 1000), [])
        self.assertEqual(self.feed(detector, VOICE, 3), [])
        self.assertEqual(self.feed(detector, SILENCE, 100), [])
        self.assertFalse(detector.active)

    def test_exactly_one_point_five_seconds_of_silence_ends_speech(self):
        detector = SpeechSegmenter()
        self.feed(detector, SILENCE, 20)
        self.feed(detector, VOICE, 10)
        self.assertTrue(detector.active)
        self.assertEqual(self.feed(detector, SILENCE, 74), [])
        segment = detector.feed(SILENCE)
        self.assertEqual(segment.reason, 'silence')
        self.assertAlmostEqual(segment.voiced_seconds, 0.2)
        self.assertIn(VOICE, segment.pcm)
        self.assertGreater(segment.duration_seconds, 1.7)  # pre-roll retained
        self.assertFalse(detector.active)

    def test_speech_resuming_resets_silence_timer(self):
        detector = SpeechSegmenter(silence_seconds=1)
        self.feed(detector, VOICE, 10)
        self.assertEqual(self.feed(detector, SILENCE, 49), [])
        self.feed(detector, VOICE, 1)
        self.assertEqual(self.feed(detector, SILENCE, 49), [])
        self.assertIsNotNone(detector.feed(SILENCE))

    def test_continuous_sound_is_capped_and_next_segment_is_preserved(self):
        detector = SpeechSegmenter(max_seconds=2)
        results = self.feed(detector, VOICE, 200)
        self.assertEqual(len(results), 2)
        self.assertTrue(all(s.reason == 'max_duration' and s.duration_seconds == 2 for s in results))

    def test_threshold_is_applied_to_little_endian_pcm(self):
        self.assertEqual(pcm_rms(VOICE), 1000)
        detector = SpeechSegmenter(threshold=1001)
        self.assertEqual(self.feed(detector, VOICE, 1000), [])
        self.assertFalse(detector.active)

    def test_invalid_frame_size_is_rejected(self):
        with self.assertRaises(ValueError):
            SpeechSegmenter().feed(b'\x00')


class AudioTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name)

    def test_capture_produces_speech_while_playback_process_is_running(self):
        real_popen = subprocess.Popen
        processes = []
        commands = []
        trigger = self.folder / 'speak'
        # 실제 파이프/프로세스/수신 스레드 사용. 장치 대신 합성 PCM 전송.
        producer = r'''
import os, pathlib, struct, sys, time
trigger = pathlib.Path(sys.argv[1])
quiet = bytes(320)
voice = struct.pack('<160h', *([1000] * 160))
while not trigger.exists():
    os.write(1, quiet)
    time.sleep(.005)
for frame in [voice] * 10 + [quiet] * 75:
    os.write(1, frame[:137])
    os.write(1, frame[137:])
    time.sleep(.005)
while True:
    os.write(1, quiet)
    time.sleep(.005)
'''

        def popen(command, **kwargs):
            commands.append(command)
            script = producer if command[0] == 'arecord' else 'import time; time.sleep(60)'
            process = real_popen([sys.executable, '-u', '-c', script, str(trigger)], **kwargs)
            processes.append(process)
            return process

        def convert(command, **kwargs):
            write_wav(Path(command[-1]))

        with EventLog(self.folder) as log:
            audio = PhoneAudio('test-device', log=log)
            with patch('verification.hardware.audio.subprocess.Popen', side_effect=popen), \
                 patch('verification.hardware.audio.subprocess.run', side_effect=convert):
                with audio.capture(self.folder):
                    reader = audio._reader
                    audio.play(self.folder / 'source.mp3', self.folder, turn=1)
                    trigger.touch()
                    utterance = audio.next_utterance(timeout=3)
                    self.assertIsNotNone(utterance)
                    self.assertTrue(audio.is_playing)
                    self.assertIsNone(processes[0].poll())
                    self.assertIsNone(processes[1].poll())
                    with wave.open(str(utterance.path), 'rb') as recording:
                        self.assertEqual(recording.getframerate(), 8000)
                        self.assertIn(VOICE, recording.readframes(recording.getnframes()))
            self.assertFalse(reader.is_alive())
            self.assertTrue(all(p.poll() is not None for p in processes))
            self.assertNotIn('-d', commands[0])
            self.assertEqual(commands[0][commands[0].index('-t') + 1], 'raw')
            entries = [json.loads(line) for line in log.path.read_text().splitlines()]
            segment = next(e for e in entries if e['stage'] == 'audio.utterance')
            self.assertEqual(segment['end_reason'], 'silence')
            converted = next(e for e in entries if e['stage'] == 'audio.tts_conversion' and e['status'] == 'ok')
            self.assertEqual(converted['audio_seconds'], 1)
            playback = next(e for e in entries if e['stage'] == 'audio.playback' and e['status'] == 'interrupted')
            self.assertGreater(playback['duration_ms'], 0)

    def test_serial_voice_port_captures_while_sending_pcm(self):
        read_fd, write_fd = os.pipe()
        started = threading.Event()
        stop = threading.Event()
        writes = []

        class Port:
            def fileno(self):
                return read_fd

            def write(self, data):
                writes.append(bytes(data))
                return len(data)

            def close(self):
                pass

        def produce():
            try:
                while not started.is_set() and not stop.is_set():
                    os.write(write_fd, SILENCE)
                    time.sleep(0.02)
                for frame in [VOICE] * 10 + [SILENCE] * 75:
                    if stop.is_set():
                        return
                    os.write(write_fd, frame)
                    time.sleep(0.02)
            finally:
                os.close(write_fd)

        producer = threading.Thread(target=produce)
        producer.start()
        self.addCleanup(stop.set)
        self.addCleanup(os.close, read_fd)
        self.addCleanup(producer.join, 3)
        with patch('verification.hardware.audio.serial.Serial', return_value=Port()), \
             patch('verification.hardware.audio.subprocess.run', side_effect=lambda command, **kwargs: write_wav(Path(command[-1]))):
            audio = PhoneAudio('unused', voice_port='/dev/ttyUSB1')
            with audio.capture(self.folder):
                audio.play(self.folder / 'source.mp3', self.folder, turn=1)
                started.set()
                utterance = audio.next_utterance(timeout=4)
                self.assertIsNotNone(utterance)
                with wave.open(str(utterance.path), 'rb') as source:
                    self.assertIn(VOICE, source.readframes(source.getnframes()))
                self.assertTrue(writes)
                self.assertTrue(all(len(chunk) == 1600 for chunk in writes))
        stop.set()

    def test_capture_process_failure_is_propagated_and_cleaned_up(self):
        real_popen = subprocess.Popen
        processes = []

        def popen(command, **kwargs):
            p = real_popen([sys.executable, '-c', 'import sys; sys.stderr.write("device busy"); sys.exit(1)'], **kwargs)
            processes.append(p)
            return p

        audio = PhoneAudio('test-device')
        with patch('verification.hardware.audio.subprocess.Popen', side_effect=popen):
            with self.assertRaisesRegex(RuntimeError, '통화 입력 처리 실패') as failure:
                with audio.capture(self.folder):
                    self.fail('capture startup should fail')
        self.assertIn('device busy', str(failure.exception.__cause__))
        self.assertIsNotNone(processes[0].poll())
        self.assertIsNone(audio._capture)

    @unittest.skipUnless(shutil.which('ffmpeg'), 'ffmpeg executable is not available')
    def test_real_ffmpeg_resampling_in_both_directions(self):
        raw = self.folder / 'source.wav'
        with wave.open(str(raw), 'wb') as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(8000)
            output.writeframes(struct.pack('<8000h', *[
                int(2000 * math.sin(2 * math.pi * 440 * n / 8000)) for n in range(8000)]))
        audio = PhoneAudio('unused')
        path = audio.prepare_for_stt(Utterance(raw, 1, 1, 0))
        with wave.open(str(path), 'rb') as source:
            self.assertEqual((source.getframerate(), source.getnchannels(), source.getsampwidth()), (16000, 1, 2))
            self.assertAlmostEqual(source.getnframes() / source.getframerate(), 1, places=2)
        downsampled = audio.prepare_playback(path, self.folder)
        with wave.open(str(downsampled), 'rb') as source:
            self.assertEqual((source.getframerate(), source.getnchannels(), source.getsampwidth()), (8000, 1, 2))
            self.assertAlmostEqual(source.getnframes() / source.getframerate(), 1, places=2)

    @patch('verification.hardware.audio.subprocess.run')
    def test_stt_conversion_keeps_filter_and_sixteen_khz(self, run):
        utterance = Utterance(self.folder / 'utterance-1.wav', 1, 2, 0)
        output = PhoneAudio('capture').prepare_for_stt(utterance)
        command = run.call_args.args[0]
        self.assertEqual(output.name, 'utterance-1-16k.wav')
        self.assertIn('16000', command)
        self.assertIn('highpass=f=100,lowpass=f=3600,afftdn=nr=10', command)
        self.assertEqual(run.call_args.kwargs['timeout'], 30)

    def test_jsonl_writes_from_multiple_threads_and_records_errors(self):
        with EventLog(self.folder) as log:
            threads = [threading.Thread(target=lambda: [log.emit('worker', index=i) for i in range(20)])
                       for _ in range(3)]
            for worker in threads:
                worker.start()
            for worker in threads:
                worker.join()
            with self.assertRaises(ValueError):
                with log.span('failure'):
                    raise ValueError('실패')
        entries = [json.loads(line) for line in log.path.read_text().splitlines()]
        self.assertEqual(len(entries), 62)
        self.assertEqual(entries[-1]['status'], 'error')
        self.assertEqual(entries[-1]['error'], '실패')
        self.assertIn('duration_ms', entries[-1])


if __name__ == '__main__':
    unittest.main()
