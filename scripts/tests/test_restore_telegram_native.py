import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts.restore_telegram_native import restore
from scripts.build_telegram_native import ABIS, TDLIB, OPENSSL, NDK


class NativeMirrorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'vendor/native').mkdir(parents=True)
        (self.root / 'tools/native').mkdir(parents=True)
        self.archive = self.root / 'vendor/native/fixture.zip'
        self.files = {abi + '/' + name: ('fixture:' + abi + ':' + name).encode()
                      for abi in ABIS for name in ('libtdjsonjava.so', 'provenance.json')}
        self.write_archive(self.files)

    def write_archive(self, files):
        with zipfile.ZipFile(self.archive, 'w') as archive:
            for name, content in files.items():
                archive.writestr(name, content)
        self.manifest = {'artifact': 'vendor/native/fixture.zip', 'tdlib': TDLIB,
                         'openssl': OPENSSL, 'ndk': NDK,
                         'sha256': hashlib.sha256(self.archive.read_bytes()).hexdigest(),
                         'files': {name: hashlib.sha256(content).hexdigest() for name, content in self.files.items()}}
        self.save_manifest()

    def save_manifest(self):
        (self.root / 'tools/native/telegram-artifact.json').write_text(json.dumps(self.manifest))

    def test_exact_payload_is_validated_before_install_and_repeated_restore_is_safe(self):
        with patch('scripts.restore_telegram_native.verify_directory') as verify:
            restore(self.root)
            restore(self.root)
        self.assertEqual(verify.call_count, 4)
        for name, data in self.files.items():
            self.assertEqual((self.root / '.local/telegram-native/jniLibs' / name).read_bytes(), data)

    def test_changed_archive_is_rejected(self):
        self.archive.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            restore(self.root)

    def test_extra_archive_entry_is_rejected_even_when_archive_hash_is_updated(self):
        self.write_archive(self.files | {'../unexpected': b'fixture'})
        with self.assertRaisesRegex(ValueError, 'unsafe native mirror entry'):
            restore(self.root)

    def test_changed_entry_is_rejected(self):
        self.write_archive(self.files | {next(iter(self.files)): b'changed native'})
        with self.assertRaisesRegex(ValueError, 'entry changed'):
            restore(self.root)

    def test_source_identity_must_match_reviewed_build_recipe(self):
        self.manifest['tdlib'] = '0' * 40
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, 'source identity'):
            restore(self.root)

    def test_failed_native_validation_does_not_replace_existing_inputs(self):
        target = self.root / '.local/telegram-native/jniLibs/arm64-v8a/libtdjsonjava.so'
        target.parent.mkdir(parents=True)
        target.write_bytes(b'previous reviewed native')
        with patch('scripts.restore_telegram_native.verify_directory', side_effect=ValueError('Invalid ELF')):
            with self.assertRaisesRegex(ValueError, 'Invalid ELF'):
                restore(self.root)
        self.assertEqual(target.read_bytes(), b'previous reviewed native')


if __name__ == '__main__':
    unittest.main()
