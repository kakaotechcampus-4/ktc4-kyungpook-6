import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from pydantic import SecretStr

from verification.cache_tts import build_cache, main, prune_cache
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
    def test_quota_fallback_builds_local_cache_and_reuses_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'tts-static'
            settings = SimpleNamespace(elevenlabs_voice_id='voice', tts_model='model',
                                       elevenlabs_api_key=SecretStr('key'), http_timeout=5,
                                       local_tts_voice='ko', local_tts_speed=160)
            response = Mock(status_code=402)
            client = Mock()
            client.post.return_value = response
            provider = Providers(settings, client=client)
            audio = FakeAudio()
            policy = {'templates': {'hello': ['안녕하세요.', '반갑습니다.']}}
            def local_audio(command, **kwargs):
                Path(command[command.index('-w')+1]).write_bytes(b'RIFF-local-audio')
            with patch('verification.providers.subprocess.run',side_effect=local_audio) as run:
                with redirect_stdout(io.StringIO()):
                    first = build_cache(policy, folder, provider, audio)
                    second = build_cache(policy, folder, provider, audio)
                    forced = build_cache(policy, folder, provider, audio, force=True)
            self.assertEqual((first.synthesized, first.converted), (2, 2))
            self.assertEqual((second.synthesized, second.skipped), (0, 2))
            self.assertEqual((forced.synthesized, forced.converted), (2, 2))
            self.assertEqual(client.post.call_count, 1)
            self.assertEqual(run.call_count, 4)
            self.assertEqual(len(list(folder.glob('*.mp3'))), 0)
            self.assertEqual(len(list(folder.glob('*.wav'))), 4)

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

    def test_stale_entries_are_pruned_unless_kept(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'tts-static'
            settings = SimpleNamespace(elevenlabs_voice_id='voice', tts_model='model',
                                       elevenlabs_api_key=SecretStr('key'), http_timeout=5)
            client = Mock()
            client.post.return_value = Mock(content=b'audio')
            provider = Providers(settings, client=client)
            audio = FakeAudio()
            policy = {'templates': {'hello': '안녕하세요.', 'bye': '감사합니다.'}}
            with redirect_stdout(io.StringIO()):
                build_cache(policy, folder, provider, audio)
            stale = tts_cache_path('감사합니다.', folder, 'voice', 'model')
            kept = tts_cache_path('안녕하세요.', folder, 'voice', 'model')
            foreign = folder / 'notes.txt'
            foreign.write_text('캐시 파일이 아니다')
            leftover = folder / (stale.stem + '.1234.wav')
            leftover.write_bytes(b'temp')

            # A template dropped from agent.json leaves both of its files behind.
            del policy['templates']['bye']
            with redirect_stdout(io.StringIO()):
                keeping = build_cache(policy, folder, provider, audio, prune=False)
            self.assertEqual(keeping.removed, 0)
            self.assertTrue(stale.exists())

            with redirect_stdout(io.StringIO()) as console:
                pruned = build_cache(policy, folder, provider, audio)
            self.assertEqual(pruned.removed, 2)
            self.assertIn(stale.name, console.getvalue())
            self.assertFalse(stale.exists())
            self.assertFalse(stale.with_suffix('.8k.wav').exists())
            self.assertTrue(kept.exists())
            self.assertTrue(kept.with_suffix('.8k.wav').exists())
            # Only the two content-addressed names are pruned.
            self.assertTrue(foreign.exists())
            self.assertTrue(leftover.exists())

    def test_changed_voice_prunes_the_previous_voice(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'tts-static'
            policy = {'templates': {'hello': '안녕하세요.'}}
            client = Mock()
            client.post.return_value = Mock(content=b'audio')
            audio = FakeAudio()
            for voice in ('voice-old', 'voice-new'):
                settings = SimpleNamespace(elevenlabs_voice_id=voice, tts_model='model',
                                           elevenlabs_api_key=SecretStr('key'), http_timeout=5)
                with redirect_stdout(io.StringIO()):
                    build_cache(policy, folder, Providers(settings, client=client), audio)
            self.assertEqual(sorted(path.name for path in folder.iterdir()),
                             sorted([tts_cache_path('안녕하세요.', folder, 'voice-new', 'model').name,
                                     tts_cache_path('안녕하세요.', folder, 'voice-new',
                                                    'model').with_suffix('.8k.wav').name]))

    def test_prune_cache_keeps_everything_it_is_told_to_keep(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            digest = 'a' * 64
            mp3 = folder / f'{digest}.mp3'
            mp3.write_bytes(b'audio')
            (folder / f'{digest}.8k.wav').write_bytes(b'wav')
            self.assertEqual(prune_cache(folder, {digest}), [])
            self.assertEqual(len(prune_cache(folder, set())), 2)
            self.assertFalse(list(folder.iterdir()))

    def test_prune_cache_removes_stale_local_audio_pair(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            digest = 'f' * 64
            local = folder / f'{digest}.wav'
            phone = folder / f'{digest}.8k.wav'
            local.write_bytes(b'local')
            phone.write_bytes(b'phone')
            self.assertEqual(prune_cache(folder, {digest}), [])
            self.assertEqual(prune_cache(folder, set()), [phone, local])
            self.assertFalse(local.exists())
            self.assertFalse(phone.exists())

    def test_prune_never_reaches_outside_the_cache_folder(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / 'tts-static'
            folder.mkdir()
            outside = root / ('b' * 64 + '.mp3')
            outside.write_bytes(b'cache folder\'s sibling')
            nested = folder / 'nested'
            nested.mkdir()
            buried = nested / ('c' * 64 + '.mp3')
            buried.write_bytes(b'subfolders are not walked')
            # A stale cache name pointing out of the folder: the link goes, its target stays.
            link = folder / ('d' * 64 + '.mp3')
            link.symlink_to(outside)

            self.assertEqual([path.name for path in prune_cache(folder, set())], [link.name])
            self.assertFalse(link.exists())
            self.assertTrue(outside.is_file())
            self.assertTrue(buried.is_file())

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
