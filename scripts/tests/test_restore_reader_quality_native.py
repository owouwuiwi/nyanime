import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

from scripts.build_reader_quality_native import ABIS, NCNN, ARCHIVE_SHA256, NDK
from scripts.restore_reader_quality_native import restore


class ReaderImageMirrorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root/'vendor/native').mkdir(parents=True)
        (self.root/'tools/native/reader-quality').mkdir(parents=True)
        self.archive = self.root/'vendor/native/fixture.zip'
        self.files = {abi+'/'+name: ('fixture:'+abi+':'+name).encode()
                      for abi in ABIS for name in ('libnyanime_reader_quality.so', 'provenance.json')}
        self.write_archive(self.files)

    def write_archive(self, files):
        with zipfile.ZipFile(self.archive, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in files.items():
                archive.writestr(name, content)
        self.manifest = {'schemaVersion': 1, 'artifact': 'vendor/native/fixture.zip', 'ncnn': NCNN,
                         'ncnnArchiveSha256': ARCHIVE_SHA256, 'ndk': NDK,
                         'sha256': hashlib.sha256(self.archive.read_bytes()).hexdigest(),
                         'files': {name: hashlib.sha256(data).hexdigest() for name, data in self.files.items()}}
        self.save_manifest()

    def save_manifest(self):
        (self.root/'tools/native/reader-quality/artifact.json').write_text(json.dumps(self.manifest))

    def test_validated_stage_installs_once_and_repeated_restore_preserves_existing_inputs(self):
        with patch('scripts.restore_reader_quality_native.verify_directory') as verify:
            restore(self.root)
            first = {name: (self.root/'.local/reader-quality-native/jniLibs'/name).stat().st_mtime_ns
                     for name in self.files}
            restore(self.root)
        self.assertEqual(verify.call_count, 4)
        for name, data in self.files.items():
            path = self.root/'.local/reader-quality-native/jniLibs'/name
            self.assertEqual(path.read_bytes(), data)
            self.assertEqual(path.stat().st_mtime_ns, first[name])

    def test_modified_archive_is_rejected_before_any_installation(self):
        self.archive.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            restore(self.root)
        self.assertFalse((self.root/'.local/reader-quality-native').exists())

    def test_undeclared_and_traversal_entries_are_rejected(self):
        self.write_archive(self.files | {'../outside': b'fixture'})
        with self.assertRaisesRegex(ValueError, 'unsafe reader image mirror entry'):
            restore(self.root)

    def test_duplicate_entries_are_rejected_even_with_an_updated_archive_hash(self):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.archive, 'a') as archive:
                archive.writestr(next(iter(self.files)), b'duplicate')
        self.manifest['sha256'] = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            restore(self.root)

    def test_changed_payload_cannot_replace_reviewed_bytes(self):
        self.write_archive(self.files | {next(iter(self.files)): b'changed'})
        with self.assertRaisesRegex(ValueError, 'entry changed'):
            restore(self.root)

    def test_oversized_payload_is_rejected_before_extraction(self):
        self.write_archive(self.files | {next(iter(self.files)): bytes(21*1024*1024)})
        with self.assertRaisesRegex(ValueError, 'Oversized'):
            restore(self.root)

    def test_recipe_and_toolchain_must_match(self):
        for field in ('ncnn', 'ncnnArchiveSha256', 'ndk', 'schemaVersion'):
            with self.subTest(field=field):
                original = self.manifest[field]
                self.manifest[field] = 'unreviewed'
                self.save_manifest()
                with self.assertRaisesRegex(ValueError, 'source identity'):
                    restore(self.root)
                self.manifest[field] = original

    def test_failed_elf_validation_preserves_previous_engine(self):
        target = self.root/'.local/reader-quality-native/jniLibs'/next(iter(self.files))
        target.parent.mkdir(parents=True)
        target.write_bytes(b'previous verified engine')
        with patch('scripts.restore_reader_quality_native.verify_directory', side_effect=ValueError('Invalid ELF')):
            with self.assertRaisesRegex(ValueError, 'Invalid ELF'):
                restore(self.root)
        self.assertEqual(target.read_bytes(), b'previous verified engine')

    def test_artifact_path_cannot_leave_vendor_directory(self):
        self.manifest['artifact'] = '../outside.zip'
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, 'leaves vendor'):
            restore(self.root)


if __name__ == '__main__':
    unittest.main()
