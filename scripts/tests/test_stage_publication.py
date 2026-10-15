"""Verified private release transport must not expose source or trust arbitrary assets."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
import urllib.error
import zipfile
from unittest.mock import patch

from scripts import stage_publication as staging
from scripts import request_publication as publication
from scripts.public_distribution import prepare_bundle, ABIS
from scripts.release_notes import release_notes

spec = importlib.util.spec_from_file_location('staging_publisher', Path(__file__).resolve().parents[2] / 'distribution/scripts/publish_release.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class StagePublicationTest(unittest.TestCase):
    def bundle(self, root):
        apks = root / 'apks'
        apks.mkdir()
        for abi in ABIS:
            (apks / f'Nyanime-Production-1.2.3.4-{abi}.apk').write_bytes(b'signed generic fixture ' + abi.encode())
        notes = root / 'notes.md'
        notes.write_text(release_notes('## 1.2.3.4: Update\n### Correzioni\n- Fixed.\n', None, 'v1.2.3.4'), encoding='utf-8')
        def documents(target, *_):
            (target / 'CHANGELOG.md').write_text('Generic changes.\n')
        with patch('scripts.public_distribution.export_documents', side_effect=documents):
            prepare_bundle(root / 'bundle', apks, '1.2.3.4', 200, 'preview', notes, 'HEAD')
        return root / 'bundle'

    def environment(self):
        return {'GITHUB_REPOSITORY': staging.SOURCE_REPOSITORY, 'GITHUB_RUN_ID': '123',
                'GITHUB_RUN_ATTEMPT': '2', 'GITHUB_SHA': 'a' * 40, 'GITHUB_TOKEN': 'private-test-token'}

    def test_archive_is_accepted_by_public_validator_and_has_only_allowed_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = self.bundle(root)
            metadata, digest = staging.make_archive(bundle, root / 'transfer.zip')
            self.assertEqual(digest, hashlib.sha256((root / 'transfer.zip').read_bytes()).hexdigest())
            publisher.unpack(root / 'transfer.zip', root / 'unpacked')
            self.assertEqual(publisher.validate_bundle(root / 'unpacked'), metadata)

    def test_source_private_paths_and_corrupt_apks_are_rejected_before_upload(self):
        for problem in ('source', 'extra', 'corrupt', 'missing', 'nested-output'):
            with self.subTest(problem=problem), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                bundle = self.bundle(root)
                output = root / 'transfer.zip'
                if problem in ('source', 'extra'):
                    target = bundle / ('public/app/source.kt' if problem == 'source' else 'private.txt')
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text('private fixture')
                elif problem == 'corrupt':
                    (bundle / 'numeric/Nyanime-Production-1.2.3.4-universal.apk').write_bytes(b'corrupted')
                elif problem == 'missing':
                    (bundle / 'notes.md').unlink()
                else:
                    output = bundle / 'transfer.zip'
                with self.assertRaises((ValueError, FileNotFoundError)):
                    staging.make_archive(bundle, output)
                self.assertFalse(output.exists())

    def test_staging_upload_is_private_verified_and_retries_do_not_replace_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = self.bundle(root)
            output = root / 'staging.json'
            release = {'id': 456, 'draft': True, 'tag_name': 'ci-publication-123-2',
                       'target_commitish': 'a' * 40, 'assets': []}
            created = False
            def api(path, token, method='GET', data=None, binary=False):
                nonlocal created
                self.assertEqual(token, 'private-test-token')
                self.assertIn(staging.SOURCE_REPOSITORY, path)
                if '/actions/runs/' in path:
                    return {'head_sha': 'a' * 40, 'head_branch': 'main', 'event': 'push', 'run_attempt': 2}
                if path.endswith('/releases?per_page=100'):
                    return [release] if created else []
                if '/tags/' in path:
                    raise urllib.error.HTTPError('https://example.invalid', 404, 'Not found', {}, None)
                if method == 'POST':
                    created = True
                    self.assertTrue(data['draft'])
                    self.assertEqual(data['target_commitish'], 'a' * 40)
                return release
            def upload(arguments, **kwargs):
                self.assertEqual(arguments[-2:], ['--repo', staging.SOURCE_REPOSITORY])
                self.assertEqual(kwargs['env']['GH_TOKEN'], 'private-test-token')
                file = Path(arguments[4])
                release['assets'] = [{'id': 789, 'name': file.name, 'state': 'uploaded',
                    'size': file.stat().st_size, 'digest': 'sha256:' + hashlib.sha256(file.read_bytes()).hexdigest()}]
            with patch.dict(os.environ, self.environment(), clear=True), patch.object(staging, 'api', side_effect=api), \
                    patch.object(staging.subprocess, 'run', side_effect=upload) as command:
                stage = staging.stage_bundle(bundle, output)
                self.assertEqual(stage['asset_id'], 789)
                self.assertEqual(json.loads(output.read_text()), stage)
                staging.stage_bundle(bundle, output)
                self.assertEqual(command.call_count, 1)
                release['assets'][0]['digest'] = 'sha256:' + '0' * 64
                with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                    staging.stage_bundle(bundle, output)
                self.assertEqual(command.call_count, 1)

    def test_unapproved_build_is_refused_without_upload(self):
        for change in ({'head_branch': 'dev'}, {'event': 'pull_request'}, {'head_sha': 'b' * 40}, {'run_attempt': 1}):
            run = {'head_sha': 'a' * 40, 'head_branch': 'main', 'event': 'push', 'run_attempt': 2} | change
            with self.subTest(change=change), patch.dict(os.environ, self.environment(), clear=True), \
                    patch.object(staging, 'api', return_value=run), patch.object(staging.subprocess, 'run') as upload:
                with self.assertRaises(ValueError):
                    staging.stage_bundle(Path('nonexistent'), Path('staging.json'))
                upload.assert_not_called()

    def transfer(self):
        data = b'verified generic archive fixture'
        event = {'source_run': 123, 'source_attempt': 2, 'release_id': 456, 'asset_id': 789,
                 'bundle_sha256': hashlib.sha256(data).hexdigest(), 'version': '1.2.3.4'}
        run = {'head_sha': 'a' * 40, 'run_attempt': 2}
        asset = {'id': 789, 'name': 'nyanime-publication-123-2.zip', 'state': 'uploaded', 'size': len(data)}
        release = {'draft': True, 'tag_name': 'ci-publication-123-2', 'target_commitish': 'a' * 40, 'assets': [asset]}
        return data, event, run, release

    def test_public_bot_validates_download_and_keeps_old_artifacts_supported(self):
        data, event, run, release = self.transfer()
        with patch.object(publisher, 'request', side_effect=[release, data]) as request:
            self.assertEqual(publisher.download_bundle(event, run, 'private-test-token'), data)
            self.assertTrue(request.call_args.kwargs['binary'])
        with patch.object(publisher, 'request', side_effect=[{'artifacts': [
                {'id': 789, 'expired': False, 'name': 'nyanime-publication-fixture'}]}, data]):
            self.assertEqual(publisher.download_bundle({'source_run': 123, 'artifact_id': 789}, run, 'test-token'), data)

    def test_public_bot_rejects_foreign_draft_wrong_attempt_and_incomplete_assets(self):
        for problem in ('draft', 'tag', 'revision', 'attempt', 'name', 'state', 'missing', 'size'):
            data, event, run, release = self.transfer()
            if problem == 'draft': release['draft'] = False
            elif problem == 'tag': release['tag_name'] = 'unrelated-release'
            elif problem == 'revision': release['target_commitish'] = 'b' * 40
            elif problem == 'attempt': event['source_attempt'] = 3
            elif problem == 'missing': release['assets'] = []
            elif problem == 'size': release['assets'][0]['size'] = 2_000_000_001
            else: release['assets'][0][problem] = 'unrelated'
            with self.subTest(problem=problem), patch.object(publisher, 'request', return_value=release) as request:
                with self.assertRaises(ValueError):
                    publisher.download_bundle(event, run, 'test-token')
                self.assertFalse(any(call.kwargs.get('binary') for call in request.call_args_list))

    def test_download_checksum_or_length_mismatch_never_reaches_publication(self):
        for problem in ('hash', 'length'):
            data, event, run, release = self.transfer()
            if problem == 'hash': event['bundle_sha256'] = '0' * 64
            else: release['assets'][0]['size'] += 1
            with self.subTest(problem=problem), patch.object(publisher, 'request', side_effect=[release, data]):
                with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                    publisher.download_bundle(event, run, 'test-token')

    def test_dispatch_uses_private_release_and_cleans_up_only_after_public_success(self):
        for conclusion in ('success', 'failure'):
            with self.subTest(conclusion=conclusion), tempfile.TemporaryDirectory() as temporary:
                file = Path(temporary) / 'stage.json'
                data, event, run, release = self.transfer()
                file.write_text(json.dumps(event | {'source_sha': 'a' * 40}))
                environment = self.environment() | {'PUBLIC_RELEASE_TOKEN': 'public-test-token',
                    'PUBLICATION_STAGING': str(file), 'VERSION': '1.2.3.4', 'TAG': 'v1.2.3.4', 'LEGACY_TAG': 'r9000'}
                calls = []
                dispatched = False
                def api(path, token, method='GET', data=None):
                    nonlocal dispatched
                    calls.append((path, method))
                    self.assertNotIn('/artifacts', path)
                    if method == 'POST':
                        dispatched = True
                        self.assertEqual(data['client_payload'], event)
                    if '/workflows/' in path:
                        if not dispatched:
                            return {'workflow_runs': []}
                        return {'workflow_runs': [{'id': 999, 'display_title': 'Publish Nyanime 1.2.3.4 from build 123',
                            'status': 'completed', 'conclusion': conclusion, 'html_url': 'https://example.invalid/run'}]}
                    return None
                with patch.dict(os.environ, environment, clear=True), patch.object(publication, 'api', side_effect=api), \
                        patch.object(publication.subprocess, 'run', return_value=types.SimpleNamespace(returncode=1, stdout='')):
                    if conclusion == 'success': publication.main()
                    else:
                        with self.assertRaises(SystemExit): publication.main()
                deletes = [path for path, method in calls if method == 'DELETE']
                self.assertEqual(deletes, [f'repos/{staging.SOURCE_REPOSITORY}/releases/456'] if conclusion == 'success' else [])

    def test_staging_metadata_cannot_dispatch_another_revision_or_unknown_fields(self):
        for change in ({'source_sha': 'b' * 40}, {'source_run': 124}, {'bundle_sha256': 'invalid'}, {'secret': 'private'}):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                file = Path(temporary) / 'stage.json'
                _, event, _, _ = self.transfer()
                file.write_text(json.dumps(event | {'source_sha': 'a' * 40} | change))
                with self.assertRaises(ValueError):
                    publication.staging_payload(file, 123, '1.2.3.4', 'a' * 40)

    def test_redirects_never_forward_private_credentials_to_storage(self):
        import urllib.request
        original = urllib.request.Request('https://api.github.com/asset', headers={'Authorization': 'Bearer secret'})
        redirected = publication.SafeRedirect().redirect_request(original, None, 302, '', {}, 'https://storage.example.invalid/file')
        self.assertIsNone(redirected.get_header('Authorization'))


if __name__ == '__main__':
    unittest.main()
