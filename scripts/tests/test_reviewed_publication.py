"""Bot publication cannot change a reviewed version, signer or published file."""
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

root = Path(__file__).resolve().parents[2]
script = root / 'scripts/publish_release.py'
if not script.exists():
    script = root / 'distribution/scripts/publish_release.py'
spec = importlib.util.spec_from_file_location('reviewed_publisher', script)
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class ReviewedPublicationTest(unittest.TestCase):
    def metadata(self):
        version = '1.2.3.4'
        source = 'Nyanime-Source-' + version + '.zip'
        manifest = 'Nyanime-Native-Manifest-' + version + '.json'
        native = 'Nyanime-Native-1.2.3.3-fixture.tar.gz'
        declaration = {'sourcePackages': {native: {'sha256': 'a' * 64,
            'url': f'https://github.com/{publisher.PUBLIC}/releases/download/v1.2.3.3/{native}'}}}
        metadata = {'schema': 4, 'versionName': version, 'versionCode': 200,
            'channel': 'recommended', 'certificateSha256': publisher.CERTIFICATE,
            'applicationId': publisher.APPLICATION_ID, 'assetPrefix': publisher.ASSET_PREFIX,
            'sourceRevision': 'b' * 40, 'nativeManifest': manifest,
            'sha256': {f'{publisher.ASSET_PREFIX}-{version}-{abi}.apk': 'c' * 64 for abi in publisher.ABIS},
            'sourceSha256': {source: 'd' * 64, manifest: 'e' * 64, native: 'a' * 64}}
        return metadata, declaration

    def inputs(self, mutate=None, properties=None):
        metadata, declaration = self.metadata()
        if mutate:
            mutate(metadata, declaration)
        properties = properties or 'versionName=1.2.3.4\nversionCode=200\nchannel=recommended'
        with patch.object(publisher.Path, 'read_text', return_value=json.dumps(metadata)), \
                patch.object(publisher, 'git', side_effect=[properties, json.dumps(declaration)]):
            return publisher.reviewed_release_inputs({'version': '1.2.3.4', 'phase': 'prepare'})

    def test_expected_uploads_include_all_architectures_and_checksums(self):
        metadata, uploads, reused = self.inputs()
        self.assertEqual(len(uploads), 8)
        self.assertEqual(len(reused), 1)
        expected = ''.join(f'{digest}  {name}\n' for name, digest in metadata['sha256'].items()).encode()
        self.assertEqual(uploads['SHA256SUMS'], hashlib.sha256(expected).hexdigest())

    def test_source_reuse_requires_exact_hash_and_public_url(self):
        def wrong_hash(_, declaration):
            next(iter(declaration['sourcePackages'].values()))['sha256'] = '0' * 64
        def wrong_host(_, declaration):
            next(iter(declaration['sourcePackages'].values()))['url'] = 'https://example.invalid/file'
        for mutation in [wrong_hash, wrong_host]:
            with self.subTest(mutation=mutation.__name__), self.assertRaises(ValueError):
                self.inputs(mutation)

    def test_immutable_tag_must_match_version_code_and_channel(self):
        for properties in ['versionName=1.2.3.3\nversionCode=200\nchannel=recommended',
                           'versionName=1.2.3.4\nversionCode=199\nchannel=recommended',
                           'versionName=1.2.3.4\nversionCode=200\nchannel=preview']:
            with self.subTest(properties=properties), self.assertRaises(ValueError):
                self.inputs(properties=properties)

    def test_signing_identity_and_architectures_are_fixed(self):
        for key, value in [('certificateSha256', '0' * 64), ('applicationId', 'generic.changed'),
                           ('sha256', {}), ('sourceRevision', 'invalid')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.inputs(lambda metadata, _: metadata.update({key: value}))

    def test_invalid_versions_and_phases_fail_before_reading_metadata(self):
        for payload in [{'version': '../../file', 'phase': 'prepare'},
                        {'version': '1.2.3.4', 'phase': 'delete'}, {'version': None, 'phase': 'prepare'}]:
            with self.subTest(payload=payload), patch.object(publisher.Path, 'read_text') as read, \
                    self.assertRaises(ValueError):
                publisher.reviewed_release_inputs(payload)
            read.assert_not_called()

    def release(self, **changes):
        result = {'id': 42, 'tag_name': 'v1.2.3.4', 'draft': True, 'assets': [],
                  'author': {'login': 'github-actions[bot]', 'type': 'Bot'}}
        result.update(changes)
        return result

    def run_release(self, phase, api, release=None, **extra):
        metadata, _, _ = self.inputs()
        with patch.dict(publisher.os.environ, {'GITHUB_TOKEN': 'fixture-token'}), \
                patch.object(publisher, 'reviewed_release_inputs', return_value=(metadata, {'SHA256SUMS': 'a' * 64}, {})), \
                patch.object(publisher, 'request', side_effect=api):
            publisher.reviewed_release({'version': '1.2.3.4', 'phase': phase, **extra})

    def test_prepare_creates_only_bot_draft_for_existing_tag(self):
        calls = []
        def api(path, token, method='GET', data=None):
            calls.append((path, method, data))
            return [] if method == 'GET' else self.release()
        self.run_release('prepare', api, notes='Reviewed release 1.2.3.4')
        self.assertEqual([method for _, method, _ in calls], ['GET', 'POST'])
        self.assertTrue(calls[-1][2]['draft'])
        self.assertEqual(calls[-1][2]['tag_name'], 'v1.2.3.4')
        self.assertEqual(calls[-1][2]['target_commitish'], 'main')

    def test_prepare_never_replaces_a_published_release(self):
        calls = []
        def api(path, token, method='GET', data=None):
            calls.append(method)
            return [self.release(draft=False)]
        with self.assertRaises(ValueError):
            self.run_release('prepare', api)
        self.assertEqual(calls, ['GET'])

    def test_non_bot_draft_cannot_be_published(self):
        with self.assertRaises(ValueError):
            self.run_release('publish', lambda *args: [self.release(author={'login': 'fixture-user', 'type': 'User'})])

    def test_missing_or_duplicate_assets_cannot_be_published(self):
        for assets in [[], [{'name': 'unexpected'}], [{'name': 'SHA256SUMS'}] * 2]:
            with self.subTest(assets=assets), self.assertRaises(ValueError):
                self.run_release('publish', lambda *args: [self.release(assets=assets)])

    def test_published_verified_bot_files_are_immutable(self):
        asset = {'name': 'SHA256SUMS', 'state': 'uploaded', 'digest': 'sha256:' + 'a' * 64,
                 'uploader': {'login': 'github-actions[bot]'}}
        calls = []
        def api(path, token, method='GET', data=None):
            calls.append(method)
            return [self.release(draft=False, assets=[asset])]
        self.run_release('publish', api)
        self.assertEqual(calls, ['GET'])

    def publish_fixture(self, bad_bytes=False, validation_error=False, existing_bot=False):
        metadata, _ = self.metadata()
        source_name = 'Nyanime-Source-1.2.3.4.zip'
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w') as package:
            package.writestr('Nyanime-1.2.3.4/README.md', 'Generic reviewed source')
        data = {source_name: archive.getvalue(), 'SHA256SUMS': b'checked fixture checksum'}
        expected = {name: hashlib.sha256(content).hexdigest() for name, content in data.items()}
        assets = [{'id': i + 1, 'name': name, 'state': 'uploaded', 'digest': 'sha256:' + expected[name],
                   'uploader': {'login': 'github-actions[bot]' if existing_bot else 'fixture-user'}}
                  for i, name in enumerate(data)]
        release = self.release(assets=assets, body='Reviewed release 1.2.3.4')
        events = []
        def api(path, token, method='GET', data=None, binary=False):
            events.append((method, path))
            if path.endswith('/latest'):
                return {'tag_name': 'v1.2.3.3'}
            if '?per_page=' in path:
                return [release]
            if binary:
                asset = next(item for item in assets if str(item['id']) == path.rsplit('/', 1)[1])
                return b'wrong bytes' if bad_bytes else fixture[asset['name']]
            if method == 'PATCH':
                release.update(data)
            return release
        fixture = data
        def upload(command, **kwargs):
            name = Path(command[4]).name
            asset = next(item for item in assets if item['name'] == name)
            asset['uploader']['login'] = 'github-actions[bot]'
        with tempfile.TemporaryDirectory() as temporary, \
                patch.dict(publisher.os.environ, {'GITHUB_TOKEN': 'fixture-token', 'RUNNER_TEMP': temporary}), \
                patch.object(publisher, 'reviewed_release_inputs', return_value=(metadata, expected, {})), \
                patch.object(publisher, 'request', side_effect=api), \
                patch.object(publisher, 'validate_bundle', side_effect=ValueError('Invalid native inventory') if validation_error else None) as validate, \
                patch.object(publisher.subprocess, 'run', side_effect=upload) as uploads:
            if bad_bytes or validation_error:
                with self.assertRaises(ValueError):
                    publisher.reviewed_release({'version': '1.2.3.4', 'phase': 'publish'})
                self.assertFalse(any(method in {'DELETE', 'PATCH'} for method, _ in events))
                uploads.assert_not_called()
            else:
                publisher.reviewed_release({'version': '1.2.3.4', 'phase': 'publish'})
                validate.assert_called_once()
                self.assertEqual(uploads.call_count, 0 if existing_bot else 2)
                self.assertFalse(release['draft'])
                self.assertFalse(release['prerelease'])
                self.assertEqual(release['make_latest'], 'true')

    def test_all_bytes_and_native_inventory_verified_before_draft_changes(self):
        self.publish_fixture(bad_bytes=True)
        self.publish_fixture(validation_error=True)

    def test_reviewed_draft_is_published_only_after_bot_upload_verification(self):
        self.publish_fixture()

    def test_retry_reuses_matching_bot_assets_without_replacement(self):
        self.publish_fixture(existing_bot=True)


if __name__ == '__main__':
    unittest.main()
