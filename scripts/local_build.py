#!/usr/bin/env python3
"""Measured local builds. No upload, install, cache deletion or system settings changes.

dev keeps the Gradle daemon, task cache and incremental Kotlin compilation;
verify adds mandatory release checks with the same valid caches. --cold provides
the explicit nonincremental fallback. No profile disables tests.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def source_state():
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    diff = subprocess.check_output(['git', 'diff', 'HEAD', '--binary'], cwd=ROOT)
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z'], cwd=ROOT).split(b'\0')
    new_files = {name.decode('utf-8'): hashlib.sha256((ROOT / name.decode('utf-8')).read_bytes()).hexdigest()
                 for name in untracked if name and (ROOT / name.decode('utf-8')).is_file()}
    return {'revision': head, 'trackedDiffSha256': hashlib.sha256(diff).hexdigest(), 'untrackedSha256': new_files}


def command(mode, tasks, workers=2, cold=False, archive_class_outputs=False):
    if mode not in {'dev', 'verify'} or not 1 <= workers <= 8:
        raise ValueError('Invalid local build profile')
    if any(not re.fullmatch(r':?[A-Za-z][A-Za-z0-9:_-]*', task) for task in tasks):
        raise ValueError('Pass Gradle task paths, not unreviewed compiler or probe flags')
    if not tasks:
        raise ValueError('At least one task is required')
    if cold and mode != 'verify':
        raise ValueError('Cold fallback requires the verification profile')
    if mode == 'verify':
        tasks = list(dict.fromkeys(['spotlessCheck', ':app:verifyOrdinaryRuntimeBoundary',
                                   ':app:testPreviewUnitTest', ':domain:testDebugUnitTest', *tasks]))
    flags = (['--no-daemon', '--no-build-cache', '-Pkotlin.incremental=false', '--rerun-tasks'] if cold else
             ['--daemon', '--build-cache', '-Pkotlin.incremental=true'])
    flags += ['-Pkotlin.compiler.execution.strategy=in-process']
    wrapper = str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))
    result = [wrapper, *tasks, *flags, '-Penable-updater', '-PcloudSyncProbe=false', '-Pkotlin.build.report.output=file,json',
            '-Pkotlin.build.report.json.directory=' + (ROOT / '.local/kotlin-build-reports').as_posix(),
            '-Pkotlin.build.report.file.output_dir=' + (ROOT / '.local/kotlin-build-reports').as_posix(),
            '--profile', '--console=plain', f'--max-workers={workers}']
    if os.name == 'nt' and not archive_class_outputs and not cold:
        result += ['--init-script', str(ROOT / 'scripts/local-output-cache.init.gradle')]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['dev', 'verify'], required=True)
    parser.add_argument('--workers', type=int, default=2)
    parser.add_argument('--sign', action='store_true', help='Use the existing local production identity')
    parser.add_argument('--cold', action='store_true', help='Explicit nonincremental verification fallback; invalidates warmed Kotlin caches')
    parser.add_argument('--archive-class-outputs', action='store_true', help='Export all app class directories to the task cache; used for comparisons or faster filesystems')
    parser.add_argument('--output', type=Path)
    parser.add_argument('tasks', nargs='+')
    args = parser.parse_args()
    invocation = command(args.mode, args.tasks, args.workers, args.cold, args.archive_class_outputs)
    env = dict(os.environ)
    if args.sign:
        from scripts.release_signing import signing_environment
        env = signing_environment()
    epoch = subprocess.check_output(['git', 'show', '-s', '--format=%ct', 'HEAD'], cwd=ROOT, text=True).strip()
    env.setdefault('SOURCE_DATE_EPOCH', epoch)
    env['PYTHONUTF8'] = '1'
    started = datetime.now(timezone.utc)
    output = args.output or ROOT / '.local/builds' / started.strftime('%Y%m%d-%H%M%S-%f')
    output.mkdir(parents=True, exist_ok=False)
    initial = source_state()
    report = {'schema': 1, 'mode': args.mode, 'tasks': args.tasks, 'command': invocation,
              'startedAt': started.isoformat(), **initial,
              'sourceDateEpoch': env['SOURCE_DATE_EPOCH'], 'workers': args.workers, 'signed': args.sign,
              'cold': args.cold, 'archiveClassOutputs': args.archive_class_outputs}
    clock = time.monotonic()
    try:
        with (output / 'gradle.log').open('w', encoding='utf-8') as log:
            with subprocess.Popen(invocation, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, encoding='utf-8', errors='replace') as process:
                for line in process.stdout:
                    log.write(line)
                    log.flush()
                    print(line, end='', flush=True)
                report['exitCode'] = process.wait()
    finally:
        report['elapsedSeconds'] = round(time.monotonic() - clock, 3)
        report.setdefault('exitCode', -1)
        report['sourceAtEnd'] = source_state()
        report['sourceUnchanged'] = initial == report['sourceAtEnd']
        formatting_only = args.mode == 'dev' and all(task.split(':')[-1] == 'spotlessApply' for task in args.tasks)
        if not report['sourceUnchanged'] and report['exitCode'] == 0 and not formatting_only:
            report['exitCode'] = 3
            print('Source changed during the build; repeat verification before using its output.', flush=True)
        for directory, pattern in [('build/reports/profile', '*.html'), ('.local/kotlin-build-reports', '*.json')]:
            for file in (ROOT / directory).glob(pattern):
                if file.stat().st_mtime >= started.timestamp():
                    destination = output / ('profiles' if pattern == '*.html' else 'kotlin') / file.name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(file, destination)
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(f'Build evidence: {output}', flush=True)
    return report['exitCode']


if __name__ == '__main__':
    # Support both python -m scripts.local_build and python scripts/local_build.py.
    sys.path.insert(0, str(ROOT))
    raise SystemExit(main())
