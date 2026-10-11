import hashlib
import json
import shutil
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.ci_release_storage import release_build_intermediates, release_storage
from scripts.public_distribution import ABIS


class CiReleaseStorageTest(unittest.TestCase):
    def unsigned_fixture(self, root):
        directory = root / 'app/build/outputs/apk/preview'
        directory.mkdir(parents=True)
        for abi in ABIS:
            with zipfile.ZipFile(directory / f'app-{abi}-preview.apk', 'w') as apk:
                apk.writestr('AndroidManifest.xml', b'manifest')
                apk.writestr('classes.dex', b'dex')
        for name in ('intermediates', 'tmp', 'generated', 'kotlin', 'reports', 'test-results'):
            path = root / 'app/build' / name
            path.mkdir()
            (path / 'keep').write_text('test fixture')

    def test_before_signing_removes_intermediates_but_preserves_apks_and_diagnostics(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.unsigned_fixture(root)
            release_build_intermediates(root)
            self.assertFalse((root / 'app/build/intermediates').exists())
            self.assertFalse((root / 'app/build/tmp').exists())
            self.assertEqual(len(list((root / 'app/build/outputs/apk/preview').glob('*.apk'))), 5)
            self.assertTrue((root / 'app/build/reports/keep').is_file())
            self.assertTrue((root / 'app/build/test-results/keep').is_file())

    def test_missing_incomplete_or_corrupt_apk_refuses_early_cleanup(self):
        for problem in ('missing', 'incomplete', 'corrupt'):
            with self.subTest(problem=problem), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.unsigned_fixture(root)
                apk = root / 'app/build/outputs/apk/preview/app-universal-preview.apk'
                if problem == 'missing':
                    apk.unlink()
                elif problem == 'corrupt':
                    apk.write_bytes(b'not an APK')
                else:
                    with zipfile.ZipFile(apk, 'w') as incomplete:
                        incomplete.writestr('AndroidManifest.xml', b'missing dex')
                with self.assertRaises((ValueError, FileNotFoundError, zipfile.BadZipFile)):
                    release_build_intermediates(root)
                self.assertTrue((root / 'app/build/intermediates/keep').is_file())

    def test_early_cleanup_is_after_native_checks_and_before_signing(self):
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/build_push.yml').read_text()
        self.assertLess(workflow.index('- name: Verify packaged native libraries'),
                        workflow.index('- name: Reclaim verified intermediates before signing'))
        self.assertLess(workflow.index('- name: Reclaim verified intermediates before signing'),
                        workflow.index('- name: Verify APK signatures and prepare OTA assets'))
        self.assertIn('mv "$apk" "dist/numeric/Nyanime-Production-${version}-${abi}.apk"', workflow)

    def fixture(self, root):
        assets = root / 'dist/numeric'
        assets.mkdir(parents=True)
        hashes = {}
        for abi in ABIS:
            name = f'Nyanime-Production-1.2.3.4-{abi}.apk'
            data = b'signed fixture ' + abi.encode()
            (assets / name).write_bytes(data)
            hashes[name] = hashlib.sha256(data).hexdigest()
        (assets / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in hashes.items()))
        (root / 'app/build/intermediates').mkdir(parents=True)
        (root / 'app/build/intermediates/temporary').write_text('large completed build')
        (root / 'app/source.kt').write_text('preserved source')
        return hashes

    def test_prepare_removes_only_completed_build_after_all_apks_verify(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.fixture(root)
            release_storage(root, '1.2.3.4')
            self.assertFalse((root / 'app/build').exists())
            self.assertTrue((root / 'app/source.kt').is_file())
            self.assertEqual(len(list((root / 'dist/numeric').glob('*.apk'))), 5)

    def test_corrupt_or_missing_retained_apk_prevents_any_cleanup(self):
        for missing in (False, True):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.fixture(root)
                apk = root / 'dist/numeric/Nyanime-Production-1.2.3.4-universal.apk'
                apk.unlink() if missing else apk.write_bytes(b'corrupted')
                with self.assertRaises((ValueError, FileNotFoundError)):
                    release_storage(root, '1.2.3.4')
                self.assertTrue((root / 'app/build/intermediates/temporary').is_file())

    def test_uploaded_bundle_cleanup_preserves_metadata_and_documents(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            hashes = self.fixture(root)
            bundle = root / 'dist/publication'
            shutil.copytree(root / 'dist/numeric', bundle / 'numeric')
            (bundle / 'publication.json').write_text(json.dumps({'versionName': '1.2.3.4', 'sha256': hashes}))
            (bundle / 'notes.md').write_text('preserved release notes')
            release_storage(root, '1.2.3.4', after_upload=True)
            self.assertFalse((root / 'dist/numeric').exists())
            self.assertFalse((bundle / 'numeric').exists())
            self.assertTrue((bundle / 'notes.md').is_file())
            self.assertTrue((bundle / 'publication.json').is_file())

    def test_linked_build_directory_is_never_removed(self):
        with tempfile.TemporaryDirectory() as temporary, tempfile.TemporaryDirectory() as other:
            root = Path(temporary)
            self.fixture(root)
            shutil.rmtree(root / 'app/build')
            outside = Path(other)
            (outside / 'keep').write_text('outside workspace')
            try:
                (root / 'app/build').symlink_to(outside, target_is_directory=True)
            except OSError:
                self.skipTest('Directory symlinks unavailable')
            with self.assertRaises(ValueError):
                release_storage(root, '1.2.3.4')
            self.assertTrue((outside / 'keep').is_file())
