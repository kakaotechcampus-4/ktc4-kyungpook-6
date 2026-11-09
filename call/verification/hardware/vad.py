"""8kHz S16_LE PCM의 RMS 임계값으로 발화 구간을 검출합니다."""

import math
import sys
from array import array
from collections import deque
from dataclasses import dataclass

SAMPLE_RATE = 8000
FRAME_SECONDS = 0.02
FRAME_BYTES = 320  # 20 ms * 8000 samples/sec * 2 bytes/sample


def pcm_rms(pcm: bytes) -> float:
    samples = array('h')
    samples.frombytes(pcm)
    if sys.byteorder != 'little':
        samples.byteswap()
    return math.sqrt(sum(sample * sample for sample in samples) / len(samples))


@dataclass(frozen=True)
class SpeechSegment:
    pcm: bytes
    reason: str
    voiced_seconds: float
    peak_rms: float

    @property
    def duration_seconds(self) -> float:
        return len(self.pcm) / (SAMPLE_RATE * 2)


class SpeechSegmenter:
    def __init__(self, threshold=500.0, silence_seconds=1.5,
                 max_seconds=30.0, min_speech_seconds=0.12, pre_roll_seconds=0.3):
        self.threshold = threshold
        self.silence_frames = math.ceil(silence_seconds / FRAME_SECONDS)
        self.max_frames = math.ceil(max_seconds / FRAME_SECONDS)
        self.min_frames = math.ceil(min_speech_seconds / FRAME_SECONDS)
        self.pre_roll = deque(maxlen=max(self.min_frames, math.ceil(pre_roll_seconds / FRAME_SECONDS)))
        self.active = False
        self._frames = []
        self._consecutive = 0
        self._quiet = 0
        self._voiced = 0
        self._peak = 0.0

    def feed(self, pcm: bytes) -> SpeechSegment | None:
        if len(pcm) != FRAME_BYTES:
            raise ValueError(f'PCM 프레임은 {FRAME_BYTES}바이트여야 합니다.')
        rms = pcm_rms(pcm)
        loud = rms >= self.threshold
        if not self.active:
            self.pre_roll.append(pcm)
            self._consecutive = self._consecutive + 1 if loud else 0
            if self._consecutive < self.min_frames:
                return None
            self.active = True
            self._frames = list(self.pre_roll)
            self.pre_roll.clear()
            self._voiced = self._consecutive
            self._quiet = 0
            self._peak = rms
        else:
            self._frames.append(pcm)
            self._quiet = 0 if loud else self._quiet + 1
            self._voiced += int(loud)
            self._peak = max(self._peak, rms)
        reason = None
        if self._quiet >= self.silence_frames:
            reason = 'silence'
        elif len(self._frames) >= self.max_frames:
            reason = 'max_duration'
        if reason is None:
            return None
        result = SpeechSegment(b''.join(self._frames), reason,
                               self._voiced * FRAME_SECONDS, self._peak)
        self.active = False
        self._frames = []
        self._consecutive = 0
        return result
