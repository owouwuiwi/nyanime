#!/usr/bin/env python3
"""Build a self-targeted APK using actual compiled DeltaPatch/DeltaRecord classes.

No main-app modifications or production signing key. Test APK has no launcher.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    parser.add_argument('--stdlib', type=Path, required=True)
    parser.add_argument('--apksig', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--debug-keystore', type=Path, default=Path.home() / '.android/debug.keystore')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    classes, dex = output / 'classes', output / 'dex'
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    tools = args.sdk / 'build-tools/36.0.0'
    android = args.sdk / 'platforms/android-37.0/android.jar'
    java, javac = args.java_home / 'bin/java.exe', args.java_home / 'bin/javac.exe'
    source = Path(__file__).with_name('DeltaDeviceProbe.java')
    package = 'nyanime.qa.deltaprobe'
    manifest = output / 'AndroidManifest.xml'
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="' + package + '">'
        '<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36"/>'
        '<queries><package android:name="it.owouwuiwi.nyanime"/></queries>'
        '<application android:label="Delta verification" android:testOnly="true" android:debuggable="true"/>'
        '<instrumentation android:name="nyanime.qa.DeltaDeviceProbe" android:targetPackage="' + package + '"/></manifest>', encoding='utf-8')
    log = output / 'build.log'
    log.write_text('', encoding='utf-8')
    def run(command):
        result = subprocess.run([str(item) for item in command], capture_output=True, text=True, encoding='utf-8', errors='replace')
        with log.open('a', encoding='utf-8') as stream:
            stream.write(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError('Probe build failed: ' + str(log))
    classpath = os.pathsep.join(map(str, [android, args.classes, args.stdlib, args.apksig]))
    run([javac, '-encoding', 'UTF-8', '-source', '11', '-target', '11', '-classpath', classpath, '-d', classes, source])
    compiled = args.classes / 'eu/kanade/tachiyomi/data/updater/delta'
    inputs = [compiled / 'DeltaPatch.class', compiled / 'DeltaRecord.class']
    inputs.extend(compiled.glob('DeltaPatch$*.class'))
    run([java, '-Xmx512m', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '29',
         '--lib', android, '--output', dex, args.stdlib, args.apksig, *inputs, *classes.rglob('*.class')])
    unsigned, aligned, apk = output / 'unsigned.apk', output / 'aligned.apk', output / 'delta-probe.apk'
    run([tools / 'aapt.exe', 'package', '-f', '-M', manifest, '-I', android, '-F', unsigned])
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        for file in dex.glob('*.dex'):
            archive.write(file, file.name)
        root = Path(__file__).resolve().parents[3]
        for name in ('LICENSE', 'NOTICE'):
            archive.write(root / name, 'assets/licenses/' + name)
    run([tools / 'zipalign.exe', '-f', '4', unsigned, aligned])
    signer = [java, '-jar', tools / 'lib/apksigner.jar']
    run([*signer, 'sign', '--ks', args.debug_keystore, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android',
         '--key-pass', 'pass:android', '--v4-signing-enabled', 'false', '--out', apk, aligned])
    run([*signer, 'verify', apk])
    report = {'apk': str(apk), 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'selfTargeted': True,
              'appCompiledClasses': {file.name: hashlib.sha256(file.read_bytes()).hexdigest() for file in inputs},
              'javaSourceSha256': hashlib.sha256(source.read_bytes()).hexdigest()}
    (output / 'verified.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
