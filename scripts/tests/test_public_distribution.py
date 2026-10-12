import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch
from scripts.public_distribution import allowed_document, allowed_source, export_documents, public_text, prepare_bundle

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).resolve().parents[2] / 'distribution/scripts/publish_release.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)

class PublicDistributionTest(unittest.TestCase):
    def test_new_bundles_reject_previous_certificate_package_prefix_and_schema(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apks = root / 'apks'
            apks.mkdir()
            for abi in publisher.ABIS:
                (apks / f'Nyanime-Production-1.2.3.4-{abi}.apk').write_bytes(b'generic fixture')
            notes = root / 'notes.md'
            notes.write_text('- Updated distribution.\n')
            def docs(target, *_):
                (target / 'CHANGELOG.md').write_text('Generic release notes.')
            with patch('scripts.public_distribution.export_documents', side_effect=docs):
                prepare_bundle(root / 'bundle', apks, '1.2.3.4', 200, 'recommended', notes, 'HEAD')
            file = root / 'bundle/publication.json'
            original = json.loads(file.read_text())
            self.assertNotIn('legacyTag', original)
            for field, value in [('certificateSha256', 'F' * 64), ('applicationId', 'app.example.previous'),
                                 ('assetPrefix', 'Nyanime'), ('schema', 1), ('versionCode', True)]:
                with self.subTest(field=field):
                    file.write_text(json.dumps(original | {field: value}))
                    with self.assertRaises(ValueError):
                        publisher.validate_bundle(root / 'bundle')
            self.assertFalse(publisher.validate_name('numeric/Nyanime-1.2.3.4-universal.apk'))

    def test_production_tag_order_stays_below_frozen_bridge_across_future_releases(self):
        import os
        import types
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'releases').mkdir()
            (root / 'releases/1.0.0.0.json').write_text(json.dumps({'schema': 1, 'legacyTag': 'r100'}))
            (root / 'releases/2.0.0.0.json').write_text(json.dumps({'schema': 2}))
            previous = Path.cwd()
            try:
                os.chdir(root)
                with patch.object(publisher.subprocess, 'run', return_value=types.SimpleNamespace(returncode=0)), \
                        patch.object(publisher, 'git', return_value='2026-01-01T12:00:00+00:00') as git:
                    self.assertEqual(publisher.canonical_snapshot_date(), '2026-01-01T11:59:59+00:00')
                    git.assert_called_once_with('log', '-1', '--format=%cI', 'refs/tags/r100')
            finally:
                os.chdir(previous)

    def test_source_and_private_material_never_enter_public_docs(self):
        for name in ['app/src/main/Example.kt', 'docs/qa/device.md', '.local/config.properties',
                     'scripts/release_version.py', '../README.md', 'docs/example.py']:
            self.assertFalse(allowed_document(name), name)
        for name in ['README.md', 'docs/privacy.md', '.github/assets/nyanime.svg', 'LICENSE']:
            self.assertTrue(allowed_document(name), name)

    def test_local_paths_are_redacted(self):
        self.assertEqual(public_text('Use C:\\Users\\Example\\workspace\\build.log'), 'Use [local workspace]')

    def test_archive_rejects_traversal_private_paths_symlinks_and_duplicate_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ['../README.md', '/README.md', 'public/.local/Example.kt', 'public/docs/example.py']:
                archive = root / 'bundle.zip'
                with zipfile.ZipFile(archive, 'w') as package:
                    package.writestr(name, 'content')
                with self.assertRaises(ValueError):
                    publisher.unpack(archive, root / 'output')
            with zipfile.ZipFile(archive, 'w') as package:
                entry = zipfile.ZipInfo('public/README.md')
                entry.external_attr = 0o120777 << 16
                package.writestr(entry, '/private/file')
            with self.assertRaises(ValueError):
                publisher.unpack(archive, root / 'output')
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                with zipfile.ZipFile(archive, 'w') as package:
                    package.writestr('notes.md', 'first')
                    package.writestr('notes.md', 'second')
            with self.assertRaises(ValueError):
                publisher.unpack(archive, root / 'output')

    def test_redirect_never_sends_source_credential_to_artifact_storage(self):
        import urllib.request
        original = urllib.request.Request('https://api.github.com/artifact', headers={'Authorization': 'Bearer secret'})
        redirect = publisher.SafeRedirect().redirect_request(original, None, 302, '', {}, 'https://storage.example.invalid/file')
        self.assertIsNone(redirect.get_header('Authorization'))

    def test_complete_bundle_requires_exact_abis_and_verifies_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apks = root / 'apks'
            apks.mkdir()
            for abi in publisher.ABIS:
                (apks / f'Nyanime-Production-1.2.3.4-{abi}.apk').write_bytes(b'signed test fixture ' + abi.encode())
            notes = root / 'notes.md'
            notes.write_text('## Changes\n\n- Updated publication.\n')
            from unittest.mock import patch
            def docs(target, *_):
                (target / 'CHANGELOG.md').write_text('Release changes.')
            with patch('scripts.public_distribution.export_documents', side_effect=docs):
                prepare_bundle(root / 'bundle', apks, '1.2.3.4', 200, 'preview', notes, 'HEAD')
            self.assertEqual(publisher.validate_bundle(root / 'bundle')['versionCode'], 200)
            source = root / 'bundle/numeric/Nyanime-Source-1.2.3.4.zip'
            saved = source.read_bytes()
            source.write_bytes(b'corrupted')
            with self.assertRaisesRegex(ValueError, 'Source hash mismatch'):
                publisher.validate_bundle(root / 'bundle')
            source.write_bytes(saved)
            apk = root / 'bundle/numeric/Nyanime-Production-1.2.3.4-universal.apk'
            apk.write_bytes(b'corrupted')
            with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                publisher.validate_bundle(root / 'bundle')

    def test_source_policy_preserves_code_but_excludes_local_material_and_signing_keys(self):
        for name in ['app/src/main/Example.kt', 'scripts/release_version.py', 'tools/native/README.md', 'LICENSE']:
            self.assertTrue(allowed_source(name), name)
            self.assertTrue(publisher.validate_name('public/' + name), name)
        for name in ['app/build/output.txt', 'app/local.properties', '.local/secret', 'tools/key.p12',
                     'gradle/../local.properties', 'app/signing.jks', 'app/bin.apk']:
            self.assertFalse(allowed_source(name), name)
            try:
                result = publisher.validate_name('public/' + name)
            except ValueError:
                result = False
            self.assertFalse(result, name)

    def test_source_export_preserves_binary_assets_code_and_copyright(self):
        archive = io.BytesIO()
        assets = {'.github/assets/logo.png': b'\x89PNG\r\n\x1a\n',
                  'app/src/main/Example.kt': b'// Copyright Original Author\npackage example\n',
                  'LICENSE': b'Original licence and attribution\n',
                  'NOTICE': b'Copyright Original Author\n',
                  '.local/secret.txt': b'excluded fixture'}
        with zipfile.ZipFile(archive, 'w') as snapshot:
            for name, value in assets.items():
                snapshot.writestr(name, value)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch('scripts.public_distribution.git', return_value=archive.getvalue()):
                export_documents(root)
            for name, value in assets.items():
                if name.startswith('.local/'):
                    self.assertFalse((root / name).exists())
                else:
                    self.assertEqual((root / name).read_bytes(), value)

    def test_oldest_updater_needs_legacy_release_before_numeric_release(self):
        def release(tag, name):
            return {'tag_name': tag, 'assets': [{'name': name, 'browser_download_url': 'https://example.invalid/app.apk'}]}
        alias = release('r9000', 'app-arm64-v8a-preview.apk')
        canonical = release('v1.2.3.4', 'Nyanime-Production-1.2.3.4-arm64-v8a.apk')
        self.assertEqual(publisher.legacy_first([alias, canonical]), 'r9000')
        self.assertEqual(publisher.legacy_first([canonical, alias]), 'v1.2.3.4')

    def test_publication_resumes_draft_when_by_tag_only_finds_published_releases(self):
        import hashlib
        import urllib.error
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            name = 'Nyanime-Production-1.2.3.4-universal.apk'
            file = directory / name
            file.write_bytes(b'verified generic fixture')
            draft = {'id': 42, 'tag_name': 'v1.2.3.4', 'draft': True, 'assets': [
                {'name': name, 'digest': 'sha256:' + hashlib.sha256(file.read_bytes()).hexdigest()}]}
            calls = []
            def api(path, token, method='GET', data=None):
                calls.append((path, method))
                if '/tags/' in path:
                    raise urllib.error.HTTPError('https://example.invalid', 404, 'Not found', {}, None)
                if path.endswith('?per_page=100'):
                    return [draft]
                if path.endswith('/latest'):
                    return {'tag_name': 'v1.2.3.3'}
                return draft
            with patch.dict(publisher.os.environ, {'GITHUB_TOKEN': 'fixture-token'}), \
                    patch.object(publisher, 'request', side_effect=api), \
                    patch.object(publisher.subprocess, 'run') as upload:
                publisher.published('v1.2.3.4', directory, 'Notes.',
                                    {'versionName': '1.2.3.4', 'channel': 'recommended'}, 'fixture', True)
            upload.assert_not_called()
            self.assertFalse(any(method == 'POST' for _, method in calls))
            self.assertTrue(any(method == 'PATCH' and path.endswith('/42') for path, method in calls))

    def test_retry_preserves_existing_checksum_order(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apks = root / 'apks'
            apks.mkdir()
            import hashlib
            names = [f'Nyanime-Production-1.2.3.4-{abi}.apk' for abi in publisher.ABIS]
            for name in names:
                (apks / name).write_bytes(name.encode())
            checksums = ''.join(f'{hashlib.sha256((apks / name).read_bytes()).hexdigest()}  {name}\n' for name in reversed(names))
            (apks / 'SHA256SUMS').write_text(checksums)
            notes = root / 'notes.md'
            notes.write_text('- Verified release.\n')
            def docs(target, *_):
                (target / 'CHANGELOG.md').write_text('Release changes.')
            with patch('scripts.public_distribution.export_documents', side_effect=docs):
                prepare_bundle(root / 'bundle', apks, '1.2.3.4', 200, 'preview', notes, 'HEAD')
            self.assertEqual((root / 'bundle/numeric/SHA256SUMS').read_text(), checksums)
            publisher.validate_bundle(root / 'bundle')
            (apks / 'SHA256SUMS').write_text(checksums.replace(names[0], 'unexpected.apk'))
            with patch('scripts.public_distribution.export_documents', side_effect=docs):
                with self.assertRaisesRegex(ValueError, 'manifest does not match'):
                    prepare_bundle(root / 'invalid', apks, '1.2.3.4', 200, 'preview', notes, 'HEAD')

    def test_legacy_alias_numeric_rollover_and_old_promotion_keep_newest_first(self):
        today = datetime.now(timezone.utc).isoformat()
        for current in ('r10000', 'r9998'):
            requests = []
            def api(path, token, method='GET', data=None):
                requests.append((path, method, data))
                self.assertEqual(token, 'ref-token' if method == 'PATCH' else 'test-token')
                if '/releases?' in path:
                    return [{'tag_name': 'r10000', 'id': 1, 'created_at': today},
                            {'tag_name': 'r9999', 'id': 2, 'created_at': today}]
                if '/git/ref/' in path:
                    return {'object': {'type': 'commit', 'sha': 'previous'}}
                if method == 'POST':
                    self.assertEqual(data['tree'], 'same-documentation-tree')
                    self.assertEqual(data['parents'], ['previous'])
                    self.assertLess(datetime.fromisoformat(data['author']['date']).date(), datetime.now(timezone.utc).date())
                    return {'sha': 'archived'}
                if '/git/commits/' in path:
                    return {'tree': {'sha': 'same-documentation-tree'}}
                return None
            with patch.dict(publisher.os.environ, {'GITHUB_TOKEN': 'test-token', 'PUBLIC_REF_TOKEN': 'ref-token'}), patch.object(publisher, 'request', side_effect=api):
                self.assertEqual(publisher.archive_previous_alias_dates(current), 'r10000')
            patches = [(path, data) for path, method, data in requests if method == 'PATCH']
            self.assertEqual(len(patches), 2)
            self.assertTrue(patches[0][0].endswith('tags/r9999'))
            self.assertEqual(patches[0][1], {'sha': 'archived', 'force': False})
            self.assertTrue(patches[1][0].endswith('releases/2'))

    def test_promoting_old_release_does_not_restore_old_documentation(self):
        import os
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'releases').mkdir()
            (root / 'README.md').write_text('Current documentation')
            (root / 'releases/2.0.0.0.json').write_text('{}')
            bundle = root / 'bundle/public'
            bundle.mkdir(parents=True)
            (bundle / 'README.md').write_text('Older documentation')
            previous = Path.cwd()
            def git(*args):
                if args == ('ls-files',):
                    return 'README.md\nreleases/2.0.0.0.json'
                if args == ('rev-parse', 'HEAD'):
                    return 'public-head'
                return ''
            try:
                os.chdir(root)
                with patch.object(publisher, 'git', side_effect=git):
                    publisher.commit_docs(root / 'bundle', {'versionName': '1.0.0.0', 'versionCode': 200, 'sha256': {}})
            finally:
                os.chdir(previous)
            self.assertEqual((root / 'README.md').read_text(), 'Current documentation')
            self.assertTrue((root / 'releases/1.0.0.0.json').is_file())

if __name__ == '__main__':
    unittest.main()
