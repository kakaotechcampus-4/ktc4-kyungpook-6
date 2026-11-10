import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

from pydantic import SecretStr

from verification.cache_tts import build_cache, main
from verification.providers import Providers, tts_cache_path


class FakeAudio:
    def __init__(self):
        self.calls = []

    def prepare_playback(self, source, folder, *, force=False):
        self.calls.append((source, force))
        target = source.with_suffix('.8k.wav')
        target.write_bytes(b'wav:' + source.read_bytes())
        return target


class CacheTTSTests(unittest.TestCase):
    def test_dynamic_templates_skip_and_force_rebuild(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'tts-static'
            settings = SimpleNamespace(elevenlabs_voice_id='voice', tts_model='model',
                                       elevenlabs_api_key=SecretStr('key'), http_timeout=5)
            response = Mock(content=b'first')
            client = Mock()
            client.post.return_value = response
            provider = Providers(settings, client=client)
            audio = FakeAudio()
            policy = {'templates': {'hello': ['안녕하세요.', '반갑습니다.'],
                                    'again': '안녕하세요.',
                                    'variable': '상호는 {storeName}입니다.'}}

            with redirect_stdout(io.StringIO()):
                first = build_cache(policy, folder, provider, audio)
            self.assertEqual((first.total, first.synthesized, first.converted, first.skipped),
                             (2, 2, 2, 0))
            self.assertEqual(client.post.call_count, 2)
            self.assertEqual(len(list(folder.glob('*.mp3'))), 2)

            with redirect_stdout(io.StringIO()):
                second = build_cache(policy, folder, provider, audio)
            self.assertEqual((second.synthesized, second.converted, second.skipped), (0, 0, 2))
            self.assertEqual(client.post.call_count, 2)

            # New variable-free variants are discovered without a hard-coded list.
            policy['templates']['extra'] = ['추가 문구.', '{storeName}님께.']
            with redirect_stdout(io.StringIO()):
                changed = build_cache(policy, folder, provider, audio)
            self.assertEqual((changed.total, changed.synthesized, changed.skipped), (3, 1, 2))
            self.assertEqual(client.post.call_count, 3)

            response.content = b'second'
            with redirect_stdout(io.StringIO()):
                forced = build_cache(policy, folder, provider, audio, force=True)
            self.assertEqual((forced.total, forced.synthesized, forced.converted, forced.skipped),
                             (3, 3, 3, 0))
            self.assertEqual(client.post.call_count, 6)
            mp3 = tts_cache_path('안녕하세요.', folder, 'voice', 'model')
            self.assertEqual(mp3.read_bytes(), b'second')
            self.assertEqual(mp3.with_suffix('.8k.wav').read_bytes(), b'wav:second')

    def test_missing_wav_reuses_existing_mp3(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'tts-static'
            folder.mkdir()
            settings = SimpleNamespace(elevenlabs_voice_id='voice', tts_model='model',
                                       elevenlabs_api_key=SecretStr('key'), http_timeout=5)
            provider = Providers(settings, client=Mock())
            audio = FakeAudio()
            mp3 = tts_cache_path('고정 문구', folder, 'voice', 'model')
            mp3.write_bytes(b'existing')
            with redirect_stdout(io.StringIO()):
                counts = build_cache({'templates': {'only': '고정 문구'}}, folder, provider, audio)
            self.assertEqual((counts.synthesized, counts.converted, counts.skipped), (0, 1, 0))
            provider.http.post.assert_not_called()
            self.assertEqual(mp3.with_suffix('.8k.wav').read_bytes(), b'wav:existing')

    def test_cli_rejects_bad_agent_variable_before_tts(self):
        import json
        import os
        from unittest.mock import patch
        policy=json.loads((Path(__file__).resolve().parents[1]/'agent.json').read_text())
        policy['templates']['goodbye']='감사합니다. {missingField}'
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'agent.json'
            path.write_text(json.dumps(policy,ensure_ascii=False))
            with patch.dict(os.environ,{'PERSONA_FILE':str(path)},clear=False):
                with self.assertRaisesRegex(ValueError,'goodbye'):
                    main([])


if __name__ == '__main__':
    unittest.main()
