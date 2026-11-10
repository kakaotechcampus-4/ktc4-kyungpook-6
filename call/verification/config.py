import os
from pathlib import Path
from pydantic import BaseModel, ConfigDict, Field, SecretStr, model_validator

class Settings(BaseModel):
    model_config = ConfigDict(extra='forbid')
    data_dir: Path = Path('data')
    persona_file: Path = Path(__file__).resolve().parent.parent / 'agent.json'
    api_key: SecretStr
    stt_api_key: SecretStr
    llm_api_key: SecretStr
    elevenlabs_api_key: SecretStr
    jev_api_key: SecretStr
    elevenlabs_voice_id: str = Field(min_length=1)
    stt_url: str = Field(min_length=1)
    llm_base_url: str = Field(min_length=1)
    jev_url: str = Field(min_length=1)
    stt_model: str = 'whisper-large-v3'
    llm_model: str = 'gpt-6.1-terra'
    tts_model: str = 'eleven_multilingual_v2'
    jev_model: str = 'typesafe/jev-latest'
    at_port: str = '/dev/ttyUSB2'
    voice_port: str = '/dev/ttyUSB1'
    modem_lock_file: Path = Path('/tmp/ep06-verification-modem.lock')
    ep06_reset_before_call: bool = False
    ep06_audio_mode: int = Field(default=3, ge=0, le=3)
    ep06_rx_gain: int = Field(default=8192, ge=0, le=65535)
    ep06_mic_gain: tuple[int, int] = (16384, 1024)
    ep06_sidetone: int = Field(default=0, ge=0, le=65535)
    silence_seconds: float = Field(default=0.6, ge=0.2, le=2)
    speech_rms_threshold: float = Field(default=500, gt=0, le=32768)
    max_utterance_seconds: float = Field(default=20, ge=3, le=60)
    input_idle_seconds: float = Field(default=25, ge=1, le=120)
    # The caller asked for a one-minute wait ceiling; a longer setting must fail at startup.
    wait_timeout_seconds: float = Field(default=60, ge=1, le=60)
    answer_timeout: float = Field(default=45, ge=1, le=120)
    max_call_seconds: float = Field(default=600, ge=10, le=1800)
    max_turns: int = Field(default=120, ge=2, le=300)
    queue_capacity: int = Field(default=1000, ge=1, le=10000)
    pending_max_age_seconds: float = Field(default=900, ge=60, le=86400)
    http_timeout: float = Field(default=20, ge=1, le=60)
    jev_timeout: float = Field(default=4, ge=0.5, le=60)
    # Each failure re-asks the question, so a transient Jev error costs a turn, not the call.
    jev_max_failures: int = Field(default=3, ge=1, le=5)
    jev_threshold: float = Field(default=0.7, ge=0.5, le=1)
    jev_stop_threshold: float = Field(default=0.9, ge=0.5, le=1)
    jev_readback_yes_threshold: float = Field(default=0.9, ge=0.5, le=1)

    @model_validator(mode='after')
    def validate_settings(self):
        if self.at_port == self.voice_port:
            raise ValueError('AT_PORT와 VOICE_PORT는 서로 달라야 합니다.')
        for name in ('api_key','stt_api_key','llm_api_key','elevenlabs_api_key','jev_api_key'):
            if not getattr(self, name).get_secret_value().strip():
                raise ValueError(f'{name}가 비어 있습니다.')
        if any(not 0 <= n <= 65535 for n in self.ep06_mic_gain):
            raise ValueError('EP06_MIC_GAIN 범위 오류')
        return self

    @classmethod
    def from_env(cls):
        values = {}
        for name in cls.model_fields:
            key = 'VERIFICATION_API_KEY' if name == 'api_key' else name.upper()
            if key in os.environ:
                values[name] = os.environ[key]
        if 'ep06_mic_gain' in values:
            values['ep06_mic_gain'] = tuple(int(n) for n in values['ep06_mic_gain'].split(','))
        return cls(**values)
