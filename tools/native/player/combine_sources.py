#!/usr/bin/env python3
"""Deliver one complete source snapshot with configuration/proof for every ABI."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import subprocess

from bundle_sources import archive_entry, verify_build_recipe


def combine(roots, output):
    provenance = None
    changes = {}
    reports = {}
    for root in roots:
        verify_build_recipe(root)
        current = json.loads((root / 'provenance.json').read_text())
        diff = {file.name: hashlib.sha256(file.read_bytes()).hexdigest() for file in (root / 'patches').glob('*.diff')}
        if provenance is not None and current != provenance:
            raise ValueError('Architectures do not share identical pinned source revisions and preparation')
        provenance = current
        for file in root.glob('native-verification-*.json'):
            report = json.loads(file.read_text())
            if report['abi'] in reports:
                raise ValueError('Duplicate architecture')
            reports[report['abi']] = {'root': root, 'report': file}
            changes[report['abi']] = diff
    if set(reports) != {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}:
        raise ValueError('All architectures are required')
    with tarfile.open(output, 'w:gz') as combined:
        with tarfile.open(roots[0] / 'corresponding-sources.tar.gz') as base:
            for member in base:
                if member.name.startswith('verified-config') or member.name.startswith('native-verification-'):
                    continue
                archive_entry(member)
                combined.addfile(member, base.extractfile(member) if member.isfile() else None)
        variants = {}
        for abi, record in sorted(reports.items()):
            combined.add(record['report'], arcname=record['report'].name, filter=archive_entry)
            combined.add(record['root'] / 'verified-config' / abi, arcname='verified-config/' + abi, filter=archive_entry)
            combined.add(record['root'] / 'patches', arcname='architecture-patches/' + abi, filter=archive_entry)
            # Upstream build scripts make ABI-specific source changes (e.g. x86
            # AES-NI settings). Deliver every modified tracked file as an overlay;
            # never normalize those differences away or pretend they are identical.
            variants[abi] = {}
            for source in provenance['sources']:
                if 'commit' not in source:
                    continue
                checkout = record['root'] / source['path']
                names = subprocess.check_output(['git', '-C', str(checkout), 'diff', '--name-only', '-z']).split(b'\0')
                for name in names:
                    if not name:
                        continue
                    file = checkout / name.decode()
                    if not file.is_file() or file.is_symlink():
                        raise ValueError('An ABI overlay needs a reviewed regular source file')
                    relative = file.relative_to(record['root']).as_posix()
                    variants[abi][relative] = hashlib.sha256(file.read_bytes()).hexdigest()
                    combined.add(file, arcname='source-variants/' + abi + '/' + relative, filter=archive_entry)
        data = (json.dumps({'sharedSourceProvenance': provenance, 'architecturePatchHashes': changes,
                            'sourceVariants': variants, 'architectures': sorted(reports)}, indent=2) + '\n').encode()
        entry = tarfile.TarInfo('all-architectures.json')
        entry.size = len(data)
        combined.addfile(entry, io.BytesIO(data))
    print(hashlib.file_digest(output.open('rb'), 'sha256').hexdigest(), output.name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, action='append', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    combine(args.build, args.output)
