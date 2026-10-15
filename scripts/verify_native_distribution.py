#!/usr/bin/env python3
"""Verify an APK's complete native inventory against the delivered source materials."""
import argparse
import hashlib
import json
import re
import struct
from pathlib import Path, PurePosixPath
import zipfile


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def dex_defined_classes(data):
    """Read class definitions, not string references that may survive shrinking."""
    if len(data) < 112 or not re.fullmatch(rb'dex\n0[0-9]{2}\x00', data[:8]):
        raise ValueError('Invalid DEX header')
    def word(offset):
        if offset < 0 or offset + 4 > len(data):
            raise ValueError('Truncated DEX table')
        return struct.unpack_from('<I', data, offset)[0]
    if word(32) != len(data) or word(40) != 0x12345678:
        raise ValueError('Invalid DEX size or byte order')
    strings, string_offset = word(56), word(60)
    types, type_offset = word(64), word(68)
    count, class_offset = word(96), word(100)
    for n, offset, stride in [(strings, string_offset, 4), (types, type_offset, 4), (count, class_offset, 32)]:
        if offset + n * stride > len(data):
            raise ValueError('Truncated DEX table')
    result = set()
    for index in range(count):
        type_id = word(class_offset + index * 32)
        if type_id >= types:
            raise ValueError('Invalid DEX class index')
        string_id = word(type_offset + type_id * 4)
        if string_id >= strings:
            raise ValueError('Invalid DEX type index')
        position = word(string_offset + string_id * 4)
        for _ in range(5):
            if position >= len(data):
                raise ValueError('Truncated DEX string')
            value = data[position]
            position += 1
            if not value & 128:
                break
        else:
            raise ValueError('Invalid DEX string size')
        end = data.find(b'\x00', position)
        if end < 0:
            raise ValueError('Unterminated DEX string')
        # Required JNI ABI names are ASCII. Other descriptors need no decoding here.
        result.add(data[position:end])
    return result


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
    required_classes = set()
    for identity, component in manifest['components'].items():
        if not component.get('license') or not component.get('sourcePackages'):
            raise ValueError(f'Missing source or license declaration: {identity}')
        if not set(component['sourcePackages']).issubset(packages):
            raise ValueError(f'Undelivered source package: {identity}')
        if not component.get('sourceIdentity') or not component.get('buildRecipe'):
            raise ValueError(f'Missing immutable source identity or build recipe: {identity}')
        required = component.get('requiredJavaClasses', [])
        if not isinstance(required, list) or any(not isinstance(name, str) or not re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+', name) for name in required):
            raise ValueError(f'Invalid required Java ABI: {identity}')
        required_classes.update(('L' + name.replace('.', '/') + ';').encode('ascii') for name in required)
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
        if required_classes:
            definitions = set()
            for name in names:
                if re.fullmatch(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex', name):
                    definitions.update(dex_defined_classes(archive.read(name)))
            if missing := required_classes - definitions:
                raise ValueError(f'Required JNI Java classes removed or renamed: {sorted(value.decode() for value in missing)}')
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
