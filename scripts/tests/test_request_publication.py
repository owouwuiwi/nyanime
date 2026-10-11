"""A failed publisher retains the retry bundle; success releases storage last."""
import contextlib
import io
import types
import unittest
import urllib.error
from unittest.mock import patch

from scripts import request_publication as publication


class RequestPublicationTest(unittest.TestCase):
    def environment(self):
        return {
            'GITHUB_REPOSITORY': publication.SOURCE_REPOSITORY,
            'GITHUB_RUN_ID': '123', 'GITHUB_TOKEN': 'private-test-token',
            'PUBLIC_RELEASE_TOKEN': 'public-test-token',
            'PUBLICATION_ARTIFACT': 'publication-fixture', 'VERSION': '1.2.3.4',
            'TAG': 'v1.2.3.4', 'LEGACY_TAG': 'r9000', 'GITHUB_SHA': 'source-revision',
        }

    def publish(self, conclusion='success', delete_error=None, previous_sha=None, previous_runs=(), stale_polls=0):
        events = []
        dispatched = False
        polls = 0

        def api(path, token, method='GET', data=None):
            nonlocal dispatched, polls
            events.append((method, path))
            if path.endswith('/artifacts'):
                self.assertEqual(token, 'private-test-token')
                return {'artifacts': [{'id': 456, 'name': 'publication-fixture'}]}
            if path.endswith('/dispatches'):
                dispatched = True
                self.assertEqual(token, 'public-test-token')
                self.assertEqual(data['client_payload']['artifact_id'], 456)
                return None
            if '/workflows/publish.yml/runs?' in path:
                self.assertEqual(token, 'public-test-token')
                if not dispatched:
                    return {'workflow_runs': list(previous_runs)}
                polls += 1
                current = [] if polls <= stale_polls else [{'id': 999, 'display_title': 'Publish Nyanime 1.2.3.4 from build 123',
                    'status': 'completed', 'conclusion': conclusion, 'html_url': 'https://example.invalid/run'}]
                return {'workflow_runs': list(previous_runs) + current}
            if method == 'DELETE':
                self.assertEqual(token, 'private-test-token')
                if delete_error:
                    raise delete_error
                return None
            self.fail('Unexpected request: ' + path)

        def git(arguments, **kwargs):
            events.append(('git', arguments))
            if arguments[1] == 'rev-parse':
                return types.SimpleNamespace(returncode=0 if previous_sha else 1, stdout=previous_sha or '')
            return types.SimpleNamespace(returncode=0, stdout='')

        with patch.dict(publication.os.environ, self.environment(), clear=True), \
             patch.object(publication, 'api', side_effect=api), \
             patch.object(publication.subprocess, 'run', side_effect=git), \
             patch.object(publication.time, 'sleep'), contextlib.redirect_stdout(io.StringIO()) as output:
            try:
                publication.main()
            except SystemExit as error:
                return events, output.getvalue(), error
        return events, output.getvalue(), None

    def test_success_deletes_only_its_bundle_after_public_success_and_tags(self):
        events, output, error = self.publish()
        self.assertIsNone(error)
        self.assertEqual(events[-1], ('DELETE', f'repos/{publication.SOURCE_REPOSITORY}/actions/artifacts/456'))
        self.assertEqual(sum(method == 'DELETE' for method, _ in events), 1)
        self.assertEqual(sum(method == 'git' and arguments[1] == 'push'
            for method, arguments in events), 1)
        self.assertIn('Public OTA release verified:', output)

    def test_failed_publication_preserves_bundle_without_creating_tags(self):
        for conclusion in ('failure', 'cancelled', 'timed_out'):
            with self.subTest(conclusion=conclusion):
                events, _, error = self.publish(conclusion=conclusion)
                self.assertIsInstance(error, SystemExit)
                self.assertFalse(any(method in ('DELETE', 'git') for method, _ in events))

    def test_retry_waits_for_a_new_run_even_when_the_previous_run_succeeded(self):
        previous = {'id': 998, 'display_title': 'Publish Nyanime 1.2.3.4 from build 123',
                    'status': 'completed', 'conclusion': 'success', 'html_url': 'https://example.invalid/old-run'}
        events, _, error = self.publish(previous_runs=[previous], stale_polls=1)
        self.assertIsNone(error)
        polls = [index for index, (method, path) in enumerate(events)
                 if method == 'GET' and '/workflows/publish.yml/runs?' in path]
        self.assertEqual(len(polls), 3)
        first_git = next(index for index, (method, _) in enumerate(events) if method == 'git')
        self.assertGreater(first_git, polls[-1])
        self.assertEqual(events[-1][0], 'DELETE')

    def test_previous_success_cannot_hide_a_failed_retry_or_delete_its_bundle(self):
        previous = {'id': 998, 'display_title': 'Publish Nyanime 1.2.3.4 from build 123',
                    'status': 'completed', 'conclusion': 'success', 'html_url': 'https://example.invalid/old-run'}
        events, _, error = self.publish(conclusion='failure', previous_runs=[previous])
        self.assertIsInstance(error, SystemExit)
        self.assertFalse(any(method in ('DELETE', 'git') for method, _ in events))

    def test_conflicting_private_tag_preserves_bundle_for_investigation(self):
        events, _, error = self.publish(previous_sha='different-source')
        self.assertIsInstance(error, SystemExit)
        self.assertFalse(any(method == 'DELETE' for method, _ in events))

    def test_cleanup_permission_failure_does_not_hide_successful_release(self):
        failure = urllib.error.HTTPError('https://api.github.com/artifacts/456', 403, 'Forbidden', {}, None)
        events, output, error = self.publish(delete_error=failure)
        self.assertIsNone(error)
        self.assertEqual(sum(method == 'DELETE' for method, _ in events), 1)
        self.assertIn('::warning::', output)
        self.assertIn('Public OTA release verified:', output)
        self.assertNotIn('private-test-token', output)

    def test_transient_cleanup_failure_is_retried_and_bounded(self):
        failure = urllib.error.HTTPError('https://api.github.com/artifacts/456', 503, 'Unavailable', {}, None)
        with patch.object(publication, 'api', side_effect=[failure, OSError('network'), None]) as api, \
             patch.object(publication.time, 'sleep') as sleep, contextlib.redirect_stdout(io.StringIO()) as output:
            publication.remove_published_artifact(456, 'private-test-token')
        self.assertEqual(api.call_count, 3)
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [2, 4])
        self.assertNotIn('::warning::', output.getvalue())

    def test_already_deleted_bundle_is_successful_and_does_not_retry(self):
        failure = urllib.error.HTTPError('https://api.github.com/artifacts/456', 404, 'Not found', {}, None)
        with patch.object(publication, 'api', side_effect=failure) as api, \
             patch.object(publication.time, 'sleep') as sleep, contextlib.redirect_stdout(io.StringIO()) as output:
            publication.remove_published_artifact(456, 'private-test-token')
        self.assertEqual(api.call_count, 1)
        sleep.assert_not_called()
        self.assertEqual(output.getvalue(), '')

    def test_timeout_keeps_artifact_available_for_retry(self):
        events = []
        def api(path, token, method='GET', data=None):
            events.append(method)
            if path.endswith('/artifacts'):
                return {'artifacts': [{'id': 456, 'name': 'publication-fixture'}]}
            if '/workflows/publish.yml/runs?' in path:
                return {'workflow_runs': []}
            return None
        with patch.dict(publication.os.environ, self.environment(), clear=True), \
             patch.object(publication, 'api', side_effect=api), \
             patch.object(publication.time, 'monotonic', side_effect=[0, 0, 1201]), \
             patch.object(publication.time, 'sleep'), patch.object(publication.subprocess, 'run') as git:
            with self.assertRaisesRegex(SystemExit, 'artifact remains'):
                publication.main()
        self.assertNotIn('DELETE', events)
        git.assert_not_called()

    def test_public_checkout_cannot_dispatch_or_delete_private_material(self):
        with patch.dict(publication.os.environ, {'GITHUB_REPOSITORY': publication.PUBLIC_REPOSITORY}, clear=True), \
             patch.object(publication, 'api') as api:
            with self.assertRaises(SystemExit):
                publication.main()
        api.assert_not_called()


if __name__ == '__main__':
    unittest.main()
