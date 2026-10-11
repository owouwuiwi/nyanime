#!/usr/bin/env python3
"""Apply the exact ABI-specific source overlay in a fresh extracted source tree."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil


def select(root, abi):
    declaration = json.loads((root / 'all-architectures.json').read_text())
    if (root / 'selected-architecture').exists():
        raise ValueError('Use a fresh extracted tree for each architecture')
    for name, digest in declaration['sourceVariants'][abi].items():
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts or '\\' in name:
            raise ValueError('Unsafe source overlay')
        source = root / 'source-variants' / abi / name
        target = root / name
        if source.is_symlink() or target.is_symlink() or not target.resolve().is_relative_to(root.resolve()):
            raise ValueError('Source overlay leaves build tree')
        if hashlib.sha256(source.read_bytes()).hexdigest() != digest:
            raise ValueError('Source overlay differs from the build record')
        shutil.copyfile(source, target)
    (root / 'selected-architecture').write_text(abi + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--abi', choices=['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'], required=True)
    args = parser.parse_args()
    select(args.root, args.abi)
