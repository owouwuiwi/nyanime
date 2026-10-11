#!/usr/bin/env python3
"""Check attribution metadata without claiming to certify license compliance."""
import argparse
import glob
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile

DEFAULT_BUNDLED_CATALOGS = ('licenses/catalog.json', 'licenses/native/catalog.json')


def validate(catalog):
    failures = []
    if not isinstance(catalog, dict):
        return ['Dependency inventory must be an object']
    libraries = catalog.get('libraries', [])
    licenses = catalog.get('licenses', {})
    if not isinstance(libraries, list) or not isinstance(licenses, dict):
        return ['Invalid dependency or license inventory']
    if not libraries or not licenses:
        failures.append('Empty dependency or license inventory')
    for library in libraries:
        if not isinstance(library, dict):
            failures.append('Invalid dependency entry')
            continue
        identity = library.get('uniqueId', '<unknown>')
        identifiers = library.get('licenses', [])
        if not isinstance(identifiers, list) or any(not isinstance(item, str) for item in identifiers):
            failures.append(f'{identity}: invalid license declaration')
            continue
        if not identifiers:
            failures.append(f'{identity}: missing license declaration')
        for identifier in identifiers:
            license = licenses.get(identifier)
            if not isinstance(license, dict) or not isinstance(license.get('content'), str) or not license['content'].strip():
                failures.append(f'{identity}: missing license text for {identifier}')
    return failures


def asset_name(name):
    if not isinstance(name, str) or not name or '\\' in name or ':' in name or '\x00' in name:
        raise ValueError('Invalid attribution asset path')
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or path.as_posix() != name:
        raise ValueError('Invalid attribution asset path')
    return name


def validate_bundled(catalog, read_asset):
    """Validate the same offline registry consumed by the Android license screen."""
    if not isinstance(catalog, list) or not catalog:
        return ['Empty or invalid bundled-license registry']
    failures = []
    for entry in catalog:
        if not isinstance(entry, dict):
            failures.append('Invalid bundled-license entry')
            continue
        identity = entry.get('name', '<unknown>')
        for field in ('name', 'website', 'licenseName', 'asset'):
            if not isinstance(entry.get(field), str) or not entry[field].strip():
                failures.append(f'{identity}: missing bundled {field}')
        for field in ('asset', 'notice', 'sourceAsset'):
            if field != 'asset' and field not in entry:
                continue
            try:
                path = asset_name(entry.get(field))
                raw = read_asset(path)
                if not raw.decode('utf-8').strip():
                    raise ValueError('Empty text')
                expected = entry.get('sourceSha256' if field == 'sourceAsset' else field + 'Sha256')
                if expected and hashlib.sha256(raw).hexdigest() != expected:
                    raise ValueError('Hash mismatch')
            except (KeyError, OSError, UnicodeError, ValueError) as error:
                failures.append(f'{identity}: invalid or unavailable {field} ({error})')
    return failures


def verify_bundled(read_asset, catalogs):
    failures, count = [], 0
    for path in catalogs:
        try:
            entries = json.loads(read_asset(asset_name(path)))
            failures.extend(validate_bundled(entries, read_asset))
            count += len(entries) if isinstance(entries, list) else 0
        except (KeyError, OSError, UnicodeError, ValueError) as error:
            failures.append(f'{path}: unavailable bundled registry ({error})')
    return failures, count


def directory_reader(roots):
    def read(name):
        name = asset_name(name)
        matches = []
        for root in roots:
            root = root.resolve()
            path = (root / name).resolve()
            if not path.is_relative_to(root):
                raise ValueError('Attribution asset escapes its root')
            if path.is_file():
                matches.append(path.read_bytes())
        if not matches:
            raise FileNotFoundError(name)
        if any(raw != matches[0] for raw in matches[1:]):
            raise ValueError('Conflicting attribution assets: ' + name)
        return matches[0]
    return read


def verify_apk(apk, expected_catalog, catalogs):
    """Check packaged bytes, including optimized/renamed raw resources."""
    with zipfile.ZipFile(apk) as archive:
        expected = hashlib.sha256(expected_catalog).digest()
        candidates = (name for name in archive.namelist() if name.startswith('res/') and name.endswith('.json'))
        if not any(hashlib.sha256(archive.read(name)).digest() == expected for name in candidates):
            return [f'{apk.name}: exact dependency inventory missing from APK'], 0
        return verify_bundled(lambda path: archive.read('assets/' + asset_name(path)), catalogs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalog', type=Path)
    parser.add_argument('--asset-root', action='append', type=Path, default=[])
    parser.add_argument('--bundled-catalog', action='append')
    parser.add_argument('--apk', nargs='+', type=Path, default=[])
    args = parser.parse_args()
    raw = args.catalog.read_bytes()
    catalog = json.loads(raw)
    errors = validate(catalog)
    bundled = args.bundled_catalog or DEFAULT_BUNDLED_CATALOGS
    if args.asset_root:
        failures, count = verify_bundled(directory_reader(args.asset_root), bundled)
        errors.extend(failures)
        if not failures:
            print(f'Offline attribution assets verified: {count} entries.')
    for pattern in args.apk:
        matches = sorted(glob.glob(str(pattern)))
        if not matches:
            errors.append(f'{pattern}: no APK found')
        for match in matches:
            apk = Path(match)
            failures, count = verify_apk(apk, raw, bundled)
            errors.extend(failures)
            if not failures:
                print(f'{apk.name}: exact dependency inventory and {count} bundled entries verified.')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f"Attribution metadata verified: {len(catalog['libraries'])} dependencies, "
          f"{len(catalog['licenses'])} license texts. Native/source review remains separate.")


if __name__ == '__main__':
    main()
