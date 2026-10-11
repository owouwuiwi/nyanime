#!/usr/bin/env python3
"""Replace the complete native closure, retaining the pinned Java/Kotlin APIs."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

from verify_native import ABI_SUFFIX, validate_dependencies, validate_elf_header

TEMPLATES = {
    'mpv': 'a08c2d3345fb1f46f7ffe2f68999f244666de4a1ed1f90a0cef6c1c761a6d793',
    'ffmpeg': 'f3cfbd97f85ba25bcd6c594227d10df612f69ad4499576b65a4aa01190be21d9',
}
API_PAYLOADS = {
    'mpv': '45f2ecac18d55411b3e1c5c97d03438e685689c9d87d768dceb7fc4c8c70a1c8',
    'ffmpeg': '47ee7c50c03e32771931620a5c4feff20691464b6a4d96ba8039477342e3910d',
}


def api_payload(archive):
    with zipfile.ZipFile(archive) as original:
        if len(original.namelist()) != len(set(original.namelist())):
            raise ValueError('Duplicate template entry')
        payload = {n: original.read(n) for n in original.namelist()
                   if not n.endswith('/') and not n.startswith(('jni/', 'prefab/'))}
    fingerprint = hashlib.sha256()
    for name, data in sorted(payload.items()):
        fingerprint.update(name.encode() + b'\0')
        fingerprint.update(hashlib.sha256(data).digest())
    return payload, fingerprint.hexdigest()


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def package(builds, abis, templates, output):
    if len(builds) != len(abis) or len(set(abis)) != len(abis):
        raise ValueError('One verified root per ABI is required')
    output.mkdir(parents=True, exist_ok=False)
    reports = []
    natives = {'mpv': {}, 'ffmpeg': {}}
    for root, abi in zip(builds, abis):
        report = json.loads((root / f'native-verification-{abi}.json').read_text())
        if report['abi'] != abi or report['state'] != 'native_checks_passed_not_release_approval':
            raise ValueError('Unverified native build')
        if reports and reports[0]['provenance'] != report['provenance']:
            raise ValueError('Architectures use different sources or build recipes')
        validate_dependencies(report['libraries'], abi)
        if {p.name for p in (root / 'jni' / abi).iterdir()} != set(report['libraries']):
            raise ValueError('Unexpected native output')
        for name, record in report['libraries'].items():
            path = root / 'jni' / abi / name
            if digest(path) != record['sha256']:
                raise ValueError('Native changed after verification')
            validate_elf_header(path.read_bytes()[:20], abi)
            owner = 'mpv' if name in {'libmpv.so', 'libplayer.so'} else 'ffmpeg'
            natives[owner][f'jni/{abi}/{name}'] = path.read_bytes()
        archive = root / 'corresponding-sources.tar.gz'
        if (root / 'corresponding-sources.sha256').read_text().split()[0] != digest(archive):
            raise ValueError('Corresponding-source archive changed')
        reports.append(report)
    result = {'abis': abis, 'allAbis': set(abis) == set(ABI_SUFFIX), 'artifacts': {},
              'libraries': {r['abi']: r['libraries'] for r in reports}}
    for owner, template in templates.items():
        payload, fingerprint = api_payload(template)
        # Recipients can use the AARs delivered in vendor/maven as API templates:
        # their native entries are discarded completely. No upstream download is
        # needed and a changed Java/Kotlin/resource payload is rejected.
        if fingerprint != API_PAYLOADS[owner]:
            raise ValueError('Unpinned Java template')
        payload.update(natives[owner])
        archive = output / f'nyanime-{owner}-1.0.0.aar'
        with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as package:
            for name, data in sorted(payload.items()):
                info = zipfile.ZipInfo(name, (2024, 10, 22, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                package.writestr(info, data)
        result['artifacts'][owner] = {'file': archive.name, 'sha256': digest(archive)}
    (output / 'aar-verification.json').write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, action='append', required=True)
    parser.add_argument('--abi', choices=ABI_SUFFIX, action='append', required=True)
    parser.add_argument('--mpv-aar', type=Path, required=True)
    parser.add_argument('--ffmpeg-aar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    package(args.build, args.abi, {'mpv': args.mpv_aar, 'ffmpeg': args.ffmpeg_aar}, args.output)
