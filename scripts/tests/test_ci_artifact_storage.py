"""Bound release artifacts and exercise the native-cache eviction fallback."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
BASH = (
    'C:/Program Files/Git/bin/bash.exe' if Path('C:/Program Files/Git/bin/bash.exe').is_file() else None) or shutil.which('bash')


class CiArtifactStorageTest(unittest.TestCase):
    def workflows(self):
        return ((ROOT / '.github/workflows/build_push.yml').read_text(),
                (ROOT / '.github/workflows/build_telegram_native.yml').read_text())

    def test_native_cache_keys_and_paths_are_identical_for_both_jobs(self):
        workflow, native = self.workflows()
        self.assertIn('path: .local/telegram-native/jniLibs/${{ matrix.abi }}', native)
        self.assertIn("key: telegram-native-${{ runner.os }}-${{ matrix.abi }}-${{ hashFiles('scripts/build_telegram_native.py') }}", native)
        for abi in ABIS:
            self.assertIn(f'path: .local/telegram-native/jniLibs/{abi}', workflow)
            self.assertIn("key: telegram-native-${{ runner.os }}-" + abi + "-${{ hashFiles('scripts/build_telegram_native.py') }}", workflow)
        self.assertNotIn('actions/upload-artifact', native)
        self.assertNotIn('actions/download-artifact', workflow)

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

    @unittest.skipUnless(BASH, 'Bash is required for the Linux cache fallback')
    def fallback(self, hits, exit_code=0):
        workflow, _ = self.workflows()
        step = workflow.split('- name: Rebuild Telegram engines evicted between jobs\n', 1)[1].split('      - name:', 1)[0]
        script = textwrap.dedent(step.split('        run: |\n', 1)[1])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            commands = root / 'commands'
            commands.mkdir()
            for name, body in [('sudo', 'exit 0'), ('python3', f'printf "%s\\n" "$*" >> "$BUILD_LOG"\nexit {exit_code}')]:
                file = commands / name
                file.write_text('#!/usr/bin/env bash\n' + body + '\n', encoding='utf8', newline='\n')
                file.chmod(0o755)
            sdk = root / 'sdk/cmdline-tools/latest/bin'
            sdk.mkdir(parents=True)
            manager = sdk / 'sdkmanager'
            manager.write_text('#!/usr/bin/env bash\nexit 0\n', encoding='utf8', newline='\n')
            manager.chmod(0o755)
            (root / 'fallback.sh').write_text(script, encoding='utf8', newline='\n')
            environment = os.environ.copy()
            environment.update({'PATH': str(commands) + os.pathsep + environment['PATH'],
                'ANDROID_HOME': (root / 'sdk').as_posix(), 'BUILD_LOG': (root / 'builds.txt').as_posix()})
            environment.update(dict(zip(('CACHE_ARM64', 'CACHE_ARMV7', 'CACHE_X86', 'CACHE_X64'), hits)))
            result = subprocess.run([BASH, 'fallback.sh'], cwd=root, env=environment,
                capture_output=True, text=True, timeout=10)
            log = root / 'builds.txt'
            return result, log.read_text().splitlines() if log.exists() else []

    def test_only_evicted_engine_is_rebuilt(self):
        result, calls = self.fallback(('true', 'true', 'false', 'true'))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ['scripts/build_telegram_native.py --abi x86 --jobs 2'])

    def test_complete_cache_loss_rebuilds_every_abi(self):
        result, calls = self.fallback(('', '', '', ''))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, [f'scripts/build_telegram_native.py --abi {abi} --jobs 2' for abi in ABIS])

    def test_cache_hits_do_not_recompile_native_code(self):
        result, calls = self.fallback(('true',) * 4)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, [])

    def test_rebuild_failure_stops_before_packaging_incomplete_engines(self):
        result, calls = self.fallback(('false',) * 4, exit_code=23)
        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertEqual(len(calls), 1)


if __name__ == '__main__':
    unittest.main()
