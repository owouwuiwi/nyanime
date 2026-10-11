"""Restore hash-pinned native source materials mirrored for the private build job.

Public release bundles still carry the actual archives at their declared public
URLs. The private mirror only breaks the first-publication download dependency.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import tempfile


def restore(manifest, destination, repository):
    destination.mkdir(parents=True, exist_ok=True)
    for name, record in manifest['sourcePackages'].items():
        tag = record.get('privateMirrorRelease')
        if not tag:
            continue
        if PurePosixPath(name).name != name or '\\' in name or not name.endswith('.tar.gz'):
            raise ValueError('Invalid source material filename')
        target = destination / name
        if target.is_file():
            with target.open('rb') as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() == record['sha256']:
                    continue
            raise ValueError('Existing source material has a different hash: ' + name)
        with tempfile.TemporaryDirectory(dir=destination) as folder:
            subprocess.run(['gh', 'release', 'download', tag, '--repo', repository,
                            '--pattern', name, '--dir', folder], check=True, capture_output=True)
            source = Path(folder) / name
            with source.open('rb') as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != record['sha256']:
                    raise ValueError('Source mirror hash mismatch: ' + name)
            source.replace(target)
        print('Restored verified source material: ' + name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=Path('tools/native/distribution.json'))
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--repository', required=True)
    args = parser.parse_args()
    restore(json.loads(args.manifest.read_text(encoding='utf-8')), args.destination, args.repository)
