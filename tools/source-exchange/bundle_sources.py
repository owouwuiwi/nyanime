#!/usr/bin/env python3
"""Bundle the exact JNI source and locked Cargo closure, including original notices."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile


def bundle(native, tools, staging, output):
    native, tools, staging, output = (Path(p).resolve() for p in (native, tools, staging, output))
    if staging.exists() or output.exists():
        raise ValueError('Use a fresh staging directory and archive path')
    if not (native / 'Cargo.lock').is_file() or not (native / 'android/provenance.json').is_file():
        raise ValueError('Verified native sources and provenance are required')
    staging.mkdir(parents=True)
    source = staging / 'native'
    shutil.copytree(native, source, ignore=shutil.ignore_patterns('target', 'vendor', '.git'))
    recipe = staging / 'rebuild-recipe'
    shutil.copytree(tools, recipe, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    subprocess.run(['cargo', 'vendor', '--locked', '--versioned-dirs', str(source / 'vendor')],
                   cwd=native, check=True, stdout=subprocess.DEVNULL)
    (source / '.cargo').mkdir(exist_ok=True)
    (source / '.cargo/config.toml').write_text(
        '[source.crates-io]\nreplace-with = "vendored-sources"\n\n'
        '[source.vendored-sources]\ndirectory = "vendor"\n', encoding='utf-8')
    versions = subprocess.check_output(['rustc', '-Vv'], text=True)
    (staging / 'toolchain.txt').write_text(versions + '\nAndroid NDK r27c, 27.2.12479018; API 26\n')
    (staging / 'README.md').write_text('''# Nyanime source exchange native sources

This archive accompanies the source-exchange JNI binaries identified by
`native/android/provenance.json`. It contains the modified native source,
locked Cargo dependency source trees, original licenses and notices, and the
Android build/packaging recipe. The application's Java/Kotlin JNI binding is
also supplied in the matching Nyanime application source archive.

Use Linux, the Rust compiler identified in toolchain.txt, rustup Android targets,
and the official Android NDK r27c. Extract to a fresh Linux filesystem directory.
The NDK and platform/toolchain components are obtained separately from their
official distributions. Keep their own licenses and notices.

From native/, run `cargo test --locked --offline` and then:
`CARGO_NET_OFFLINE=true bash ../rebuild-recipe/build-android.sh "$PWD" /path/to/android-ndk-r27c`.
Rustup must already have the four Android standard-library targets installed.
The included Cargo configuration uses the vendored source for offline builds.
Outputs appear in target/<triple>/release/libnyanime_source_exchange.so.

Original Apache/MIT/BSD/MPL and other component terms remain applicable. The
provenance describes the actual build inputs and outputs, not a promise that
different compiler paths or environments reproduce identical binary bytes.
''', encoding='utf-8')
    inputs = {}
    for p in sorted(staging.rglob('*')):
        if p.is_symlink():
            raise ValueError('Unexpected symlink in source closure: ' + str(p))
        if p.is_file():
            inputs[p.relative_to(staging).as_posix()] = hashlib.sha256(p.read_bytes()).hexdigest()
    (staging / 'archive-inputs.json').write_text(json.dumps(inputs, indent=2, sort_keys=True) + '\n')
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('xb') as stream, gzip.GzipFile(filename='', mode='wb', fileobj=stream, mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w', format=tarfile.PAX_FORMAT) as archive:
            for p in sorted(staging.rglob('*')):
                name = p.relative_to(staging).as_posix()
                info = archive.gettarinfo(str(p), name)
                info.uid = info.gid = 0
                info.uname = info.gname = ''
                info.mtime = 0
                if p.is_file():
                    with p.open('rb') as data:
                        archive.addfile(info, data)
                else:
                    archive.addfile(info)
    digest = hashlib.file_digest(output.open('rb'), 'sha256').hexdigest()
    return {'archive': str(output), 'sha256': digest, 'files': len(inputs), 'bytes': output.stat().st_size}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('native', 'tools', 'staging', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(bundle(args.native, args.tools, args.staging, args.output), indent=2))
