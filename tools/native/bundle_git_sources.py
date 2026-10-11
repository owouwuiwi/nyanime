#!/usr/bin/env python3
"""Export pinned source trees and their gitlinks, without local changes or Git data."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import posixpath
import subprocess
import tarfile


def git(root, *arguments):
    return subprocess.check_output(['git', '-C', str(root), *arguments])


def append_tree(output, root, revision, prefix, records, excluded_submodules=()):
    actual = git(root, 'rev-parse', revision + '^{commit}').decode().strip()
    if actual != revision:
        raise ValueError('Source revision must be a full commit ID')
    records.append({'path': prefix, 'revision': revision, 'excludedSubmodules': list(excluded_submodules)})
    with tarfile.open(fileobj=io.BytesIO(git(root, 'archive', '--format=tar', revision))) as source:
        for entry in source:
            path = PurePosixPath(entry.name)
            if path.is_absolute() or '..' in path.parts:
                raise ValueError('Unsafe upstream source path')
            if entry.issym():
                resolved = posixpath.normpath(posixpath.join(str(path.parent), entry.linkname))
                if resolved.startswith('../') or PurePosixPath(entry.linkname).is_absolute():
                    raise ValueError('Upstream symlink leaves source tree')
            entry.name = prefix + '/' + entry.name
            entry.uid = entry.gid = 0
            entry.uname = entry.gname = ''
            output.addfile(entry, source.extractfile(entry) if entry.isfile() else None)
    for record in git(root, 'ls-tree', '-rz', revision).split(b'\0'):
        if not record:
            continue
        metadata, name = record.split(b'\t', 1)
        mode, kind, commit = metadata.decode().split()
        if mode == '160000':
            child = name.decode()
            if child in excluded_submodules:
                continue
            append_tree(output, root / child, commit, prefix + '/' + child, records)


def bundle(lock_file, cache, output):
    lock = json.loads(lock_file.read_text())
    records = []
    with tarfile.open(output, 'w:gz') as package:
        for source in lock['sources']:
            append_tree(package, cache / source['path'], source['commit'], source['path'], records, source.get('excludedSubmodules', ()))
        data = (json.dumps({'sources': lock['sources'], 'exportedTrees': records}, indent=2) + '\n').encode()
        entry = tarfile.TarInfo('source-provenance.json')
        entry.size = len(data)
        package.addfile(entry, io.BytesIO(data))
    print(hashlib.file_digest(output.open('rb'), 'sha256').hexdigest(), output.name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lock', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    bundle(options.lock, options.cache, options.output)
