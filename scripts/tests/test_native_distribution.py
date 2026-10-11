import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('native_distribution', Path(__file__).parents[1] / 'verify_native_distribution.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NativeDistributionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = self.root / 'sources.tar.gz'
        self.source.write_bytes(b'synthetic source archive')
        self.apk = self.root / 'app.apk'
        self.name = 'lib/arm64-v8a/libfixture.so'
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr(self.name, b'synthetic native')
        self.manifest = {'schemaVersion': 1, 'sourcePackages': {
            self.source.name: {'sha256': module.sha256(self.source)},
        }, 'components': {'fixture': {
            'license': 'MIT', 'sourceIdentity': 'immutable revision', 'buildRecipe': 'build.sh',
            'sourcePackages': [self.source.name],
            'libraries': {self.name: hashlib.sha256(b'synthetic native').hexdigest()},
        }}}

    def check(self):
        return module.validate(self.apk, self.manifest, self.root)

    def test_complete_inventory(self):
        self.assertEqual(self.check()['nativeLibraries'], 1)

    def test_undeclared_native(self):
        with zipfile.ZipFile(self.apk, 'a') as archive:
            archive.writestr('lib/arm64-v8a/libother.so', b'other')
        with self.assertRaisesRegex(ValueError, 'without corresponding sources'):
            self.check()

    def test_changed_source_archive(self):
        self.source.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'corresponding sources'):
            self.check()

    def test_changed_native(self):
        self.manifest['components']['fixture']['libraries'][self.name] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'binary changed'):
            self.check()

    def test_missing_build_recipe(self):
        del self.manifest['components']['fixture']['buildRecipe']
        with self.assertRaisesRegex(ValueError, 'build recipe'):
            self.check()

    def test_duplicate_ownership(self):
        self.manifest['components']['duplicate'] = self.manifest['components']['fixture']
        with self.assertRaisesRegex(ValueError, 'Duplicate native ownership'):
            self.check()

    def test_source_path_traversal(self):
        self.manifest['sourcePackages']['../sources.tar.gz'] = self.manifest['sourcePackages'].pop(self.source.name)
        with self.assertRaisesRegex(ValueError, 'plain filename'):
            self.check()

    def test_missing_architecture_entry(self):
        self.manifest['components']['fixture']['libraries']['lib/arm64-v8a/libmissing.so'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'inventory differs'):
            self.check()


if __name__ == '__main__':
    unittest.main()
