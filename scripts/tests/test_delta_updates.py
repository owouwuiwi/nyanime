import gzip
import io
import hashlib
import json
from pathlib import Path
import random
import struct
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from scripts.delta_updates import (
    MAGIC, MANIFEST, apply_patch, create_patch, prepare_deltas, sha256, validate_manifest, worthwhile, fetch_latest_base,
)


class DeltaUpdatesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.base = self.root / 'base.apk'
        self.target = self.root / 'target.apk'
        self.patch = self.root / 'update.nyadelta'
        self.result = self.root / 'result.apk'

    def write_apk(self, path, items):
        with zipfile.ZipFile(path, 'w') as archive:
            for name, data, method in items:
                archive.writestr(name, data, compress_type=method)

    def fixture(self):
        unchanged = random.Random(42).randbytes(2 * 1024 * 1024)
        changed = random.Random(43).randbytes(200_000)
        self.write_apk(self.base, [('old-header', b'previous', 8), ('lib/native.so', unchanged, 0), ('classes.dex', changed, 8)])
        self.write_apk(self.target, [('new-header', b'new offset', 8), ('lib/native.so', unchanged, 0), ('classes.dex', changed + b'new code', 8)])

    def test_zip_payloads_move_but_reconstruction_is_byte_exact_and_small(self):
        self.fixture()
        record = create_patch(self.base, self.target, self.patch)
        self.assertTrue(worthwhile(record['patchSize'], record['targetSize']))
        self.assertLess(record['patchSize'], record['targetSize'] // 5)
        apply_patch(self.base, self.patch, self.result)
        self.assertEqual(self.target.read_bytes(), self.result.read_bytes())

    def test_patch_bytes_are_deterministic(self):
        self.fixture()
        create_patch(self.base, self.target, self.patch)
        other = self.root / 'other.nyadelta'
        create_patch(self.base, self.target, other)
        self.assertEqual(self.patch.read_bytes(), other.read_bytes())

    def test_changed_stored_entry_retains_unchanged_chunks(self):
        data = random.Random(44).randbytes(4 * 1024 * 1024)
        self.write_apk(self.base, [('lib.so', data, 0)])
        self.write_apk(self.target, [('lib.so', data[:131_072] + b'x' * 65_536 + data[196_608:], 0)])
        record = create_patch(self.base, self.target, self.patch)
        self.assertLess(record['patchSize'], 200_000)
        apply_patch(self.base, self.patch, self.result)
        self.assertEqual(sha256(self.result), sha256(self.target))

    def test_wrong_base_and_truncation_never_leave_partial_output(self):
        self.fixture()
        create_patch(self.base, self.target, self.patch)
        original = self.patch.read_bytes()
        for patch in [original[:20], original[:-10], original[:-1]]:
            with self.subTest(length=len(patch)):
                self.patch.write_bytes(patch)
                with self.assertRaises((ValueError, EOFError, OSError)):
                    apply_patch(self.base, self.patch, self.result)
                self.assertFalse(self.result.exists())
        self.patch.write_bytes(original)
        self.base.write_bytes(b'wrong base')
        with self.assertRaises(ValueError):
            apply_patch(self.base, self.patch, self.result)
        self.assertFalse(self.result.exists())

    def test_untrusted_commands_ranges_counts_sizes_hash_and_trailing_bytes(self):
        self.base.write_bytes(b'base')
        header = MAGIC + struct.pack('>QQ', 4, 4) + bytes.fromhex(sha256(self.base)) * 2
        payloads = [
            header + struct.pack('>I', 100_001),
            header + struct.pack('>I', 1) + b'\x02',
            header + struct.pack('>I', 1) + b'\x00' + struct.pack('>QQ', 3, 4),
            header + struct.pack('>I', 1) + b'\x01' + struct.pack('>Q', 5) + b'12345',
            header + struct.pack('>I', 1) + b'\x01' + struct.pack('>Q', 0),
            header + struct.pack('>I', 1) + b'\x01' + struct.pack('>Q', 4) + b'wrong',
            header + struct.pack('>I', 1) + b'\x01' + struct.pack('>Q', 4) + b'base' + b'\x00',
        ]
        for payload in payloads:
            with self.subTest(payload=payload[-20:]):
                self.patch.write_bytes(gzip.compress(payload))
                with self.assertRaises(ValueError):
                    apply_patch(self.base, self.patch, self.result)
                self.assertFalse(self.result.exists())

    def test_generator_manifest_is_checked_and_excludes_unhelpful_patches(self):
        self.fixture()
        old = self.root / 'previous'
        old.mkdir()
        self.base.replace(old / 'Nyanime-Production-1.0.0.0-arm64-v8a.apk')
        self.target.replace(self.root / 'Nyanime-Production-1.0.0.1-arm64-v8a.apk')
        manifest = prepare_deltas([old], self.root, '1.0.0.1', 201)
        hashes = {item['target']: item['targetSha256'] for item in manifest['patches']}
        self.assertEqual(1, len(validate_manifest(manifest, '1.0.0.1', 201, hashes)))
        self.assertTrue((self.root / MANIFEST).is_file())
        mutated = json.loads(json.dumps(manifest))
        mutated['patches'][0]['target'] = '../other.apk'
        with self.assertRaises(ValueError):
            validate_manifest(mutated, '1.0.0.1', 201, hashes)
        mutated = json.loads(json.dumps(manifest))
        mutated['patches'].append(mutated['patches'][0])
        with self.assertRaises(ValueError):
            validate_manifest(mutated, '1.0.0.1', 201, hashes)
        self.assertFalse(worthwhile(85, 100))
        self.assertFalse(worthwhile(10, 100))

    def test_output_never_overwrites_inputs_or_an_existing_file(self):
        self.fixture()
        with self.assertRaises(ValueError):
            create_patch(self.base, self.target, self.base)
        create_patch(self.base, self.target, self.patch)
        with self.assertRaises(ValueError):
            apply_patch(self.base, self.patch, self.target)

    def test_bundle_carries_pinned_delta_materials_and_publisher_rejects_tampering(self):
        import importlib.util
        from scripts.public_distribution import prepare_bundle, ABIS
        from scripts.release_notes import release_notes
        spec = importlib.util.spec_from_file_location('delta_publisher', Path(__file__).resolve().parents[2] / 'distribution/scripts/publish_release.py')
        publisher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(publisher)
        old, apks = self.root / 'old', self.root / 'apks'
        old.mkdir()
        apks.mkdir()
        self.fixture()
        self.base.replace(old / 'Nyanime-Production-1.0.0.0-arm64-v8a.apk')
        for abi in ABIS:
            (apks / f'Nyanime-Production-1.0.0.1-{abi}.apk').write_bytes(self.target.read_bytes())
        notes = self.root / 'notes.md'
        notes.write_text(release_notes('## 1.0.0.1: Update\n### Distribuzione\n- Verified generic update.\n', None, 'v1.0.0.1'), encoding='utf-8')
        def docs(target, *_):
            (target / 'CHANGELOG.md').write_text('Generic release.')
        with patch('scripts.public_distribution.export_documents', side_effect=docs):
            prepare_bundle(self.root / 'bundle', apks, '1.0.0.1', 201, 'recommended', notes, 'HEAD', delta_bases=[old])
        bundle = self.root / 'bundle'
        metadata = publisher.validate_bundle(bundle)
        self.assertEqual(2, len(metadata['deltaSha256']))
        self.assertEqual(5, len(metadata['sha256']))
        name = next(name for name in metadata['deltaSha256'] if name.endswith('.nyadelta'))
        (bundle / 'numeric' / name).write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'Delta hash mismatch'):
            publisher.validate_bundle(bundle)

    def test_prepare_refuses_stale_output_and_never_leaves_a_silent_old_manifest(self):
        (self.root / MANIFEST).write_text('{}')
        with self.assertRaises(ValueError):
            prepare_deltas([self.root], self.root, '1.0.0.1', 201)

    def test_auto_base_fetch_verifies_hashes_caches_bytes_and_excludes_unverified_cache_entries(self):
        payload = b'complete signed APK bytes'
        name = 'Nyanime-Production-1.0.0.0-arm64-v8a.apk'
        release = {'tag_name': 'v1.0.0.0', 'draft': False, 'assets': [{'name': name,
            'digest': 'sha256:' + hashlib.sha256(payload).hexdigest(), 'size': len(payload),
            'browser_download_url': 'https://github.com/owouwuiwi/nyanime/releases/download/v1.0.0.0/' + name}]}
        calls = []
        def open_url(url, timeout):
            value = url.full_url if hasattr(url, 'full_url') else url
            calls.append(value)
            return io.BytesIO(json.dumps(release).encode() if value.endswith('/latest') else payload)
        opener = type('Opener', (), {'open': staticmethod(open_url)})()
        with patch('scripts.delta_updates.urllib.request.build_opener', return_value=opener):
            bases = fetch_latest_base(self.root / 'cache', '1.0.0.1')
            self.assertEqual(1, len(bases))
            self.assertEqual(payload, (bases[0] / name).read_bytes())
            calls.clear()
            self.assertEqual(bases, fetch_latest_base(self.root / 'cache', '1.0.0.1'))
            self.assertEqual(1, len(calls))
            self.assertTrue(calls[0].endswith('/latest'))
            release['assets'][0]['digest'] = 'sha256:' + '0' * 64
            self.assertEqual([], fetch_latest_base(self.root / 'cache', '1.0.0.1'))
            self.assertFalse((bases[0] / name).exists())
            self.assertEqual({}, json.loads((bases[0] / 'verified-bases.json').read_text()))

    def test_auto_base_fetch_is_optional_when_offline_or_latest_is_not_older(self):
        opener = type('Opener', (), {'open': staticmethod(lambda *args, **kwargs: (_ for _ in ()).throw(OSError('offline')))})()
        with patch('scripts.delta_updates.urllib.request.build_opener', return_value=opener):
            self.assertEqual([], fetch_latest_base(self.root / 'cache', '1.0.0.1'))


if __name__ == '__main__':
    unittest.main()
