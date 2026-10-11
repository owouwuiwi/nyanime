import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from scripts.verify_license_catalog import (
    asset_name, directory_reader, validate, validate_bundled, verify_apk, verify_bundled,
)


class LicenseCatalogTest(unittest.TestCase):
    def test_all_declared_license_texts_are_required(self):
        catalog = {'libraries': [{'uniqueId': 'example:component', 'licenses': ['A', 'B']}],
                   'licenses': {'A': {'content': 'License A'}}}
        self.assertEqual(validate(catalog), ['example:component: missing license text for B'])
        catalog['licenses']['B'] = {'content': 'License B'}
        self.assertEqual(validate(catalog), [])

    def test_missing_metadata_and_whitespace_text_do_not_pass(self):
        self.assertTrue(validate({'libraries': [], 'licenses': {}}))
        self.assertEqual(validate({'libraries': [{'uniqueId': 'example:component'}],
                                  'licenses': {'A': {'content': 'License A'}}}),
                         ['example:component: missing license declaration'])
        self.assertTrue(validate({'libraries': [{'uniqueId': 'example:component', 'licenses': ['A']}],
                                  'licenses': {'A': {'content': '   '}}}))

    def test_malformed_license_data_cannot_be_counted_as_text(self):
        for content in (None, 123, {}, []):
            self.assertTrue(validate({'libraries': [{'uniqueId': 'example:component', 'licenses': ['A']}],
                                      'licenses': {'A': {'content': content}}}))
        for catalog in ([], {'libraries': {}}, {'libraries': [None]},
                        {'libraries': [{'licenses': [None]}]}):
            self.assertTrue(validate(catalog))


class BundledLicenseTest(unittest.TestCase):
    def setUp(self):
        self.entry = {'name': 'Example component', 'website': 'https://example.org',
                      'licenseName': 'Example license', 'asset': 'licenses/example.txt',
                      'notice': 'licenses/example-notice.txt'}
        self.assets = {'licenses/example.txt': b'Original license',
                       'licenses/example-notice.txt': b'Original copyright'}

    def test_license_and_copyright_notice_are_both_required(self):
        self.assertEqual(validate_bundled([self.entry], self.assets.__getitem__), [])
        del self.assets['licenses/example-notice.txt']
        self.assertTrue(validate_bundled([self.entry], self.assets.__getitem__))

    def test_blank_or_invalid_utf8_text_is_rejected(self):
        for raw in (b'  \n', b'\xff'):
            self.assets['licenses/example.txt'] = raw
            self.assertTrue(validate_bundled([self.entry], self.assets.__getitem__))

    def test_reviewed_license_hash_detects_changed_text(self):
        self.entry['assetSha256'] = hashlib.sha256(self.assets['licenses/example.txt']).hexdigest()
        self.assertEqual(validate_bundled([self.entry], self.assets.__getitem__), [])
        self.assets['licenses/example.txt'] += b' changed'
        self.assertTrue(validate_bundled([self.entry], self.assets.__getitem__))

    def test_missing_metadata_or_empty_registry_is_rejected(self):
        for field in ('name', 'website', 'licenseName', 'asset'):
            incomplete = {key: value for key, value in self.entry.items() if key != field}
            self.assertTrue(validate_bundled([incomplete], self.assets.__getitem__))
        for catalog in ([], {}, [None]):
            self.assertTrue(validate_bundled(catalog, self.assets.__getitem__))

    def test_script_source_bytes_are_checked_when_the_registry_pins_them(self):
        self.entry['sourceAsset'] = 'scripts/example.js'
        self.assets['scripts/example.js'] = b'// original runtime'
        self.entry['sourceSha256'] = hashlib.sha256(self.assets['scripts/example.js']).hexdigest()
        self.assertEqual(validate_bundled([self.entry], self.assets.__getitem__), [])
        self.assets['scripts/example.js'] = b'// replaced runtime'
        self.assertTrue(validate_bundled([self.entry], self.assets.__getitem__))

    def test_asset_paths_cannot_escape_or_use_windows_separators(self):
        for path in ('../secret', '/absolute', 'C:/secret', 'licenses\\secret',
                     'licenses/../secret', 'licenses//example.txt', ''):
            with self.assertRaises(ValueError):
                asset_name(path)

    def test_missing_or_malformed_registry_is_reported(self):
        self.assertTrue(verify_bundled(self.assets.__getitem__, ['licenses/missing.json'])[0])
        self.assets['licenses/catalog.json'] = b'not JSON'
        self.assertTrue(verify_bundled(self.assets.__getitem__, ['licenses/catalog.json'])[0])

    def test_conflicting_asset_roots_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            roots = [Path(temp) / 'one', Path(temp) / 'two']
            for root in roots:
                (root / 'licenses').mkdir(parents=True)
                (root / 'licenses/example.txt').write_bytes(b'Same text')
            self.assertEqual(directory_reader(roots)('licenses/example.txt'), b'Same text')
            (roots[1] / 'licenses/example.txt').write_bytes(b'Different text')
            with self.assertRaisesRegex(ValueError, 'Conflicting'):
                directory_reader(roots)('licenses/example.txt')

    def test_packaged_renamed_inventory_and_each_notice_are_verified(self):
        raw = b'{"libraries": [{"uniqueId": "example:component"}]}'
        registry = json.dumps([self.entry]).encode()
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / 'fixture.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('res/a1.json', raw)
                archive.writestr('assets/licenses/catalog.json', registry)
                for name, data in self.assets.items():
                    archive.writestr('assets/' + name, data)
            self.assertEqual(verify_apk(apk, raw, ['licenses/catalog.json']), ([], 1))
            self.assertTrue(verify_apk(apk, raw + b' ', ['licenses/catalog.json'])[0])
            self.assertTrue(verify_apk(apk, raw, ['licenses/absent.json'])[0])
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('res/a1.json', raw)
                archive.writestr('assets/licenses/catalog.json', registry)
                archive.writestr('assets/licenses/example.txt', b'Original license')
            self.assertTrue(verify_apk(apk, raw, ['licenses/catalog.json'])[0])
