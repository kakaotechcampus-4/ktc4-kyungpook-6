"""Build the shared TTS cache for variable-free agent.json templates."""

import argparse
import json
import os
import shutil
import tempfile
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace

from pydantic import SecretStr

from .config import Settings
from .dialogue import static_template_texts
from .hardware.audio import PhoneAudio
from .providers import Providers, tts_cache_path


@dataclass
class CacheCounts:
    total: int = 0
    synthesized: int = 0
    converted: int = 0
    skipped: int = 0


def load_local_env(path: Path) -> None:
    """Load ordinary KEY=VALUE entries without executing the .env as shell code."""
    if not path.is_file():
        return
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:].strip()
        key, separator, value = line.partition('=')
        if not separator or not key.strip():
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
            value = value[1:-1]
        os.environ.setdefault(key.strip(), value)


def build_cache(policy: dict, folder: Path, provider: Providers, audio: PhoneAudio,
                *, force: bool = False) -> CacheCounts:
    texts = list(dict.fromkeys(static_template_texts(policy).values()))
    folder.mkdir(parents=True, exist_ok=True)
    counts = CacheCounts(total=len(texts))
    settings = provider.settings
    for index, text in enumerate(texts, 1):
        mp3 = tts_cache_path(text, folder, settings.elevenlabs_voice_id, settings.tts_model)
        wav = mp3.with_suffix('.8k.wav')
        mp3_ready = mp3.is_file() and mp3.stat().st_size > 0
        wav_ready = wav.is_file() and wav.stat().st_size > 0
        if not force and mp3_ready and wav_ready:
            counts.skipped += 1
            continue
        if force:
            # Keep the previous pair intact if synthesis or conversion fails.
            with tempfile.TemporaryDirectory(prefix='.tts-rebuild-', dir=folder) as temporary:
                staging = Path(temporary)
                new_mp3 = provider.synthesize(text, staging)
                new_wav = audio.prepare_playback(new_mp3, staging)
                os.replace(new_mp3, mp3)
                os.replace(new_wav, wav)
            counts.synthesized += 1
            counts.converted += 1
        else:
            source = provider.synthesize(text, folder)
            audio.prepare_playback(source, folder, force=not wav_ready)
            counts.synthesized += int(not mp3_ready)
            counts.converted += int(not wav_ready)
        print(f'[{index}/{counts.total}] {text}', flush=True)
    return counts


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description='agent.json의 공용 TTS 캐시를 생성합니다.')
    parser.add_argument('-f', '--force', action='store_true',
                        help='기존 MP3와 WAV를 포함해 모든 문구를 다시 생성합니다.')
    args = parser.parse_args(argv)

    load_local_env(Path('.env'))
    api_key = os.getenv('ELEVENLABS_API_KEY', '').strip()
    voice_id = os.getenv('ELEVENLABS_VOICE_ID', '').strip()
    if not api_key or not voice_id:
        parser.error('ELEVENLABS_API_KEY와 ELEVENLABS_VOICE_ID가 필요합니다 (.env 또는 환경 변수).')
    if shutil.which('ffmpeg') is None:
        parser.error('MP3를 통화용 WAV로 변환하려면 ffmpeg가 필요합니다.')

    defaults = Settings.model_fields
    persona_file = Path(os.getenv('PERSONA_FILE', str(defaults['persona_file'].default)))
    data_dir = Path(os.getenv('DATA_DIR', str(defaults['data_dir'].default)))
    policy = json.loads(persona_file.read_text(encoding='utf-8'))
    settings = SimpleNamespace(
        elevenlabs_api_key=SecretStr(api_key), elevenlabs_voice_id=voice_id,
        tts_model=os.getenv('TTS_MODEL', defaults['tts_model'].default),
        http_timeout=float(os.getenv('HTTP_TIMEOUT', defaults['http_timeout'].default)),
    )
    provider = Providers(settings)
    try:
        counts = build_cache(policy, data_dir / 'tts-static', provider, PhoneAudio(''),
                              force=args.force)
    finally:
        provider.close()
    print(f'완료: 고유 문구 {counts.total}개, 새 TTS 합성 {counts.synthesized}개, '
          f'WAV 생성 {counts.converted}개, 재사용 {counts.skipped}개.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
