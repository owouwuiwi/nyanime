import hashlib
import importlib.util
from pathlib import Path
import tempfile
import struct
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('native_distribution', Path(__file__).parents[1] / 'verify_native_distribution.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NativeDistributionTest(unittest.TestCase):
    @staticmethod
    def dex(defined, referenced=()):
        names = list(dict.fromkeys([*defined, *referenced]))
        data = bytearray(112 + len(names) * 8 + len(defined) * 32)
        data[:8] = b'dex\n035\x00'
        def word(offset, value): struct.pack_into('<I', data, offset, value)
        word(40, 0x12345678)
        word(56, len(names)); word(60, 112)
        types = 112 + len(names) * 4
        word(64, len(names)); word(68, types)
        classes = types + len(names) * 4
        word(96, len(defined)); word(100, classes)
        for index, name in enumerate(names):
            encoded = ('L' + name.replace('.', '/') + ';').encode()
            assert len(encoded) < 128
            word(112 + index * 4, len(data))
            word(types + index * 4, index)
            data.extend(bytes([len(encoded)]) + encoded + b'\x00')
        for index, name in enumerate(defined): word(classes + index * 32, names.index(name))
        word(32, len(data))
        return bytes(data)

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

    def test_required_jni_class_is_defined_in_a_secondary_dex(self):
        self.manifest['components']['fixture']['requiredJavaClasses'] = ['fixture.bridge.NativeEntry']
        with zipfile.ZipFile(self.apk, 'a') as archive:
            archive.writestr('classes.dex', self.dex(['fixture.Main']))
            archive.writestr('classes2.dex', self.dex(['fixture.bridge.NativeEntry']))
        self.assertEqual(self.check()['nativeLibraries'], 1)

    def test_string_reference_does_not_replace_removed_jni_definition(self):
        self.manifest['components']['fixture']['requiredJavaClasses'] = ['fixture.bridge.NativeEntry']
        with zipfile.ZipFile(self.apk, 'a') as archive:
            archive.writestr('classes.dex', self.dex(['fixture.Main'], ['fixture.bridge.NativeEntry']))
        with self.assertRaisesRegex(ValueError, 'classes removed or renamed'):
            self.check()

    def test_invalid_dex_never_passes_jni_validation(self):
        self.manifest['components']['fixture']['requiredJavaClasses'] = ['fixture.bridge.NativeEntry']
        with zipfile.ZipFile(self.apk, 'a') as archive:
            archive.writestr('classes.dex', self.dex(['fixture.Main'])[:-1])
        with self.assertRaisesRegex(ValueError, 'DEX size'):
            self.check()

    def test_invalid_jni_abi_declaration_is_rejected(self):
        self.manifest['components']['fixture']['requiredJavaClasses'] = '../fixture'
        with self.assertRaisesRegex(ValueError, 'required Java ABI'):
            self.check()


if __name__ == '__main__':
    unittest.main()
