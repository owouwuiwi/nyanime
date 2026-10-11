import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from scripts import local_build
from scripts.local_build import command


class LocalBuildTest(unittest.TestCase):
    def test_development_keeps_incremental_cache_and_daemon(self):
        result = command('dev', [':domain:testDebugUnitTest'])
        self.assertIn('--daemon', result)
        self.assertIn('--build-cache', result)
        self.assertIn('-Pkotlin.incremental=true', result)
        self.assertIn(':domain:testDebugUnitTest', result)
        self.assertIn('-Penable-updater', result)

    def test_final_verification_requires_tests_boundary_and_format_without_discarding_valid_caches(self):
        result = command('verify', [':app:testPreviewUnitTest', ':app:assemblePreview'])
        self.assertIn('--build-cache', result)
        self.assertIn('-Pkotlin.incremental=true', result)
        self.assertIn('spotlessCheck', result)
        self.assertIn(':app:verifyOrdinaryRuntimeBoundary', result)
        self.assertIn(':domain:testDebugUnitTest', result)
        self.assertEqual(1, result.count(':app:testPreviewUnitTest'))
        self.assertNotIn('clean', result)
        self.assertNotIn('-x', result)

    def test_explicit_cold_fallback_does_not_reuse_cache_or_skip_checks(self):
        result = command('verify', [':app:assemblePreview'], cold=True)
        self.assertIn('--no-build-cache', result)
        self.assertIn('-Pkotlin.incremental=false', result)
        self.assertIn('--rerun-tasks', result)
        self.assertIn(':app:testPreviewUnitTest', result)
        with self.assertRaises(ValueError):
            command('dev', [':app:assemblePreview'], cold=True)

    def test_tasks_cannot_smuggle_a_probe_or_disable_tests(self):
        for tasks in [[], ['-x'], ['-PcloudSyncProbe=true'], [':app:assemblePreview;echo test']]:
            with self.assertRaises(ValueError):
                command('verify', tasks)

    def test_local_io_policy_only_changes_output_cache_not_task_execution(self):
        default = command('dev', [':app:testPreviewUnitTest'])
        archived = command('dev', [':app:testPreviewUnitTest'], archive_class_outputs=True)
        self.assertNotIn('--init-script', archived)
        if os.name == 'nt':
            self.assertEqual(default[:-2], archived)
            self.assertEqual('--init-script', default[-2])
            self.assertIn('local-output-cache.init.gradle', default[-1])
        else:
            self.assertEqual(default, archived)
        self.assertNotIn('--init-script', command('verify', [':app:assemblePreview'], cold=True))

    def test_a_successful_process_is_not_build_evidence_if_source_changes(self):
        for operation, expected in [("pass", 0), ("open('source.kt', 'w').write('changed')", 3),
                                    ("open('new.kt', 'w').write('new source')", 3), ("raise SystemExit(5)", 5)]:
            with self.subTest(operation=operation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / '.gitignore').write_text('.local/\n')
                (root / 'source.kt').write_text('original')
                subprocess.run(['git', 'init', '-q', str(root)], check=True)
                subprocess.run(['git', 'add', '.'], cwd=root, check=True)
                subprocess.run(['git', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test',
                                'commit', '-qm', 'Fixture'], cwd=root, check=True)
                with patch.object(local_build, 'ROOT', root), \
                        patch.object(local_build, 'command', return_value=[sys.executable, '-c', operation]), \
                        patch.object(sys, 'argv', ['local_build', '--mode', 'dev', ':fixture']):
                    self.assertEqual(expected, local_build.main())
                report = json.loads(next((root / '.local/builds').glob('*/report.json')).read_text())
                self.assertEqual(expected, report['exitCode'])
                self.assertEqual(expected != 3, report['sourceUnchanged'])
