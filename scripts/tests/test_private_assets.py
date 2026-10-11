import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts.private_assets import verify_public_apk
from scripts.public_distribution import export_documents


class PrivateAssetsTest(unittest.TestCase):
    def test_any_private_apk_marker_still_blocks_distribution(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'private.apk'
            with zipfile.ZipFile(target, 'w') as archive:
                archive.writestr('assets/private/example.json', '{}')
            with self.assertRaisesRegex(ValueError, 'Public distribution blocked'):
                verify_public_apk(target)

    def test_ordinary_apk_assets_remain_allowed(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'ordinary.apk'
            with zipfile.ZipFile(target, 'w') as archive:
                archive.writestr('assets/licenses/example/LICENSE', 'license')
            verify_public_apk(target)

    def test_source_export_checks_before_writing_any_files(self):
        contents = io.BytesIO()
        with zipfile.ZipFile(contents, 'w') as archive:
            archive.writestr('README.md', 'Ordinary documentation')
            archive.writestr('feature/example/src/main/assets/private/marker.json', '{}')
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            with patch('scripts.public_distribution.git', return_value=contents.getvalue()):
                with self.assertRaisesRegex(ValueError, 'Public source export blocked'):
                    export_documents(target)
            self.assertEqual([], list(target.iterdir()))

    def test_invalid_apk_is_not_silently_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'invalid.apk'
            target.write_bytes(b'invalid archive')
            with self.assertRaises(zipfile.BadZipFile):
                verify_public_apk(target)
