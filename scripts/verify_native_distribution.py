#!/usr/bin/env python3
"""Verify an APK's complete native inventory against the delivered source materials."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def validate(apk, manifest, materials):
    if manifest.get('schemaVersion') != 1 or not manifest.get('components'):
        raise ValueError('Invalid native-source manifest')
    packages = manifest.get('sourcePackages', {})
    for name, package in packages.items():
        if PurePosixPath(name).name != name or '\\' in name:
            raise ValueError('Source package must have a plain filename')
        path = materials / name
        if not path.is_file() or sha256(path) != package['sha256']:
            raise ValueError(f'Missing or changed corresponding sources: {name}')
    declared = {}
    for identity, component in manifest['components'].items():
        if not component.get('license') or not component.get('sourcePackages'):
            raise ValueError(f'Missing source or license declaration: {identity}')
        if not set(component['sourcePackages']).issubset(packages):
            raise ValueError(f'Undelivered source package: {identity}')
        if not component.get('sourceIdentity') or not component.get('buildRecipe'):
            raise ValueError(f'Missing immutable source identity or build recipe: {identity}')
        for name, digest in component['libraries'].items():
            path = PurePosixPath(name)
            if len(path.parts) != 3 or path.parts[0] != 'lib' or not name.endswith('.so'):
                raise ValueError(f'Invalid native entry: {name}')
            if name in declared:
                raise ValueError(f'Duplicate native ownership: {name}')
            declared[name] = digest
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK archive entries')
        actual = {n for n in names if n.startswith('lib/') and n.endswith('.so')}
        if not actual:
            raise ValueError('Empty APK native inventory')
        if actual - declared.keys():
            raise ValueError(f'Native libraries without corresponding sources: {sorted(actual - declared.keys())}')
        abis = {n.split('/')[1] for n in actual}
        expected = {n for n in declared if n.split('/')[1] in abis}
        if actual != expected:
            raise ValueError(f'Native inventory differs: {sorted(actual ^ expected)}')
        for name in sorted(actual):
            if hashlib.sha256(archive.read(name)).hexdigest() != declared[name]:
                raise ValueError(f'Native binary changed after source verification: {name}')
    return {'nativeLibraries': len(actual), 'abis': sorted(abis), 'sourcePackages': len(packages)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--materials', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(validate(args.apk, json.loads(args.manifest.read_text()), args.materials), indent=2))


if __name__ == '__main__':
    main()
