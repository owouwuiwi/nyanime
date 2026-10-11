"""Bound release artifacts and keep reviewed native inputs independent of caches."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class CiArtifactStorageTest(unittest.TestCase):
    def test_builds_restore_reviewed_reader_engines_before_compiling(self):
        for filename, compile_step in (('build_push.yml', 'Compile preview sources'),
                                       ('build_pull_request.yml', 'Build app')):
            with self.subTest(workflow=filename):
                workflow = (ROOT / '.github/workflows' / filename).read_text()
                self.assertIn('python3 -m scripts.restore_reader_quality_native', workflow)
                engine = workflow.index('      - name: Restore verified reader image engines')
                build = workflow.index('      - name: ' + compile_step)
                self.assertLess(engine, build)
                self.assertNotIn('actions/download-artifact', workflow)

    def test_publication_does_not_depend_on_artifact_quota_and_failed_runs_keep_diagnostics(self):
        workflow = (ROOT / '.github/workflows/build_push.yml').read_text()
        self.assertNotIn('Keep APKs available from the workflow', workflow)
        bundle = workflow.split('- name: Hand off verified public release files', 1)[1].split('- name:', 1)[0]
        self.assertIn('python3 -m scripts.stage_publication --directory dist/publication', bundle)
        self.assertNotIn('actions/upload-artifact', bundle)
        self.assertIn('PUBLICATION_STAGING: dist/publication-staging.json', workflow)
        diagnostics = workflow.split('- name: Preserve build diagnostics', 1)[1].split('- name:', 1)[0]
        self.assertIn('continue-on-error: true', diagnostics)
        self.assertIn('retention-days: 3', diagnostics)
        self.assertEqual(len(re.findall(r'uses: actions/upload-artifact@', workflow)), 1)

if __name__ == '__main__':
    unittest.main()
