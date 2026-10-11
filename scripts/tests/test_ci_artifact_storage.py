"""Bound release artifacts and keep reviewed native inputs independent of caches."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class CiArtifactStorageTest(unittest.TestCase):
    def workflows(self):
        return ((ROOT / '.github/workflows/build_push.yml').read_text(),
                (ROOT / '.github/workflows/build_telegram_native.yml').read_text())

    def test_release_uses_reviewed_native_inputs_and_maintenance_stays_separate(self):
        workflow, native = self.workflows()
        self.assertIn('run: python3 -m scripts.restore_telegram_native', workflow)
        self.assertLess(workflow.index('Restore verified Telegram engine'), workflow.index('Compile preview sources'))
        self.assertNotIn('needs: telegram', workflow)
        self.assertNotIn('scripts/build_telegram_native.py --abi', workflow)
        self.assertNotIn('actions/download-artifact', workflow)
        self.assertIn('scripts/build_telegram_native.py --abi', native)
        self.assertNotIn('actions/upload-artifact', native)

    def test_publication_does_not_depend_on_artifact_quota_and_failed_runs_keep_diagnostics(self):
        workflow, _ = self.workflows()
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
