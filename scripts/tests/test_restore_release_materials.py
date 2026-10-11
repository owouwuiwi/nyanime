import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from scripts.restore_release_materials import restore


class ReleaseMaterialsTest(unittest.TestCase):
    def fixture(self, content=b'fixture source'):
        return {'sourcePackages': {'fixture.tar.gz': {'sha256': hashlib.sha256(content).hexdigest(),
                'privateMirrorRelease': 'native-fixture'}}}

    def test_verified_local_material_needs_no_network(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'fixture.tar.gz').write_bytes(b'fixture source')
            with patch('scripts.restore_release_materials.subprocess.run') as run:
                restore(self.fixture(), root, 'example/private')
            run.assert_not_called()

    def test_changed_mirror_is_never_published_as_a_verified_material(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def download(command, **kwargs):
                (Path(command[-1]) / 'fixture.tar.gz').write_bytes(b'changed source')
            with patch('scripts.restore_release_materials.subprocess.run', side_effect=download):
                with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                    restore(self.fixture(), root, 'example/private')
            self.assertFalse((root / 'fixture.tar.gz').exists())

    def test_failed_download_does_not_leave_a_material_for_retry(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with patch('scripts.restore_release_materials.subprocess.run', side_effect=subprocess.CalledProcessError(1, 'gh')):
                with self.assertRaises(subprocess.CalledProcessError):
                    restore(self.fixture(), root, 'example/private')
            self.assertFalse(list(root.iterdir()))

    def test_valid_download_is_verified_then_moved_atomically(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def download(command, **kwargs):
                (Path(command[-1]) / 'fixture.tar.gz').write_bytes(b'fixture source')
            with patch('scripts.restore_release_materials.subprocess.run', side_effect=download):
                restore(self.fixture(), root, 'example/private')
            self.assertEqual((root / 'fixture.tar.gz').read_bytes(), b'fixture source')
