#!/usr/bin/env python3
"""Check attribution metadata without claiming to certify license compliance."""
import argparse
import json
from pathlib import Path


def validate(catalog):
    failures = []
    libraries = catalog.get('libraries', [])
    licenses = catalog.get('licenses', {})
    if not libraries or not licenses:
        failures.append('Empty dependency or license inventory')
    for library in libraries:
        identity = library.get('uniqueId', '<unknown>')
        identifiers = library.get('licenses', [])
        if not identifiers:
            failures.append(f'{identity}: missing license declaration')
        for identifier in identifiers:
            license = licenses.get(identifier)
            if not license or not str(license.get('content', '')).strip():
                failures.append(f'{identity}: missing license text for {identifier}')
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalog', type=Path)
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text(encoding='utf-8'))
    errors = validate(catalog)
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f"Attribution metadata verified: {len(catalog['libraries'])} dependencies, "
          f"{len(catalog['licenses'])} license texts. Native/source review remains separate.")


if __name__ == '__main__':
    main()
