"""Free completed CI build storage only after all retained APKs verify."""
import argparse
import hashlib
import json
import re
import shutil
import zipfile
from pathlib import Path

from scripts.public_distribution import ABIS, ASSET_PREFIX


def safe_workspace_paths(root, relatives):
    for relative in relatives:
        path = root / relative
        path.resolve().relative_to(root)
        for ancestor in (path, *path.parents):
            if ancestor == root:
                break
            if ancestor.is_symlink():
                raise ValueError('CI cleanup refuses linked directories')


def release_build_intermediates(workspace):
    """APK/native verification has completed; preserve APKs and diagnostics for signing."""
    root = Path(workspace).resolve()
    targets = ['app/build/intermediates', 'app/build/tmp', 'app/build/generated', 'app/build/kotlin']
    directory = root / 'app/build/outputs/apk/preview'
    safe_workspace_paths(root, targets + ['app/build/outputs/apk/preview'])
    for abi in ABIS:
        path = directory / f'app-{abi}-preview.apk'
        if path.is_symlink():
            raise ValueError('CI cleanup refuses linked APKs')
        with zipfile.ZipFile(path) as apk:
            if not {'AndroidManifest.xml', 'classes.dex'}.issubset(apk.namelist()) or apk.testzip() is not None:
                raise ValueError('All five complete APKs are required before cleanup')
    for relative in targets:
        path = root / relative
        if path.exists():
            shutil.rmtree(path)
    print('CI disk free before signing: %.1f GiB' % (shutil.disk_usage(root).free / 1024**3))


def verify_assets(directory, version):
    expected = {f'{ASSET_PREFIX}-{version}-{abi}.apk' for abi in ABIS}
    records = [line.split() for line in (directory / 'SHA256SUMS').read_text().splitlines()]
    if any(len(record) != 2 for record in records):
        raise ValueError('Invalid retained APK checksum manifest')
    hashes = {name: digest for digest, name in records}
    if len(records) != len(expected) or set(hashes) != expected:
        raise ValueError('All five retained APKs are required before cleanup')
    for name, digest in hashes.items():
        path = directory / name
        if path.is_symlink() or not re.fullmatch('[a-fA-F0-9]{64}', digest):
            raise ValueError('Unsafe APK input or checksum')
        with path.open('rb') as file:
            if hashlib.file_digest(file, 'sha256').hexdigest() != digest.lower():
                raise ValueError('Retained APK checksum mismatch: ' + name)
    return hashes


def release_storage(workspace, version, after_upload=False):
    if not re.fullmatch(r'\d+\.\d+\.\d+\.\d+', version):
        raise ValueError('Invalid release version')
    root = Path(workspace).resolve()
    # Validate every target before deleting anything, including linked ancestors.
    targets = ['app/build']
    inputs = ['dist/numeric']
    if after_upload:
        targets += ['dist/numeric', 'dist/publication/numeric']
        inputs += ['dist/publication/numeric']
    safe_workspace_paths(root, targets + inputs)
    retained = verify_assets(root / 'dist/numeric', version)
    if after_upload:
        metadata = json.loads((root / 'dist/publication/publication.json').read_text())
        if metadata.get('delivery', 'full') == 'patches':
            from distribution.scripts.publish_release import validate_bundle
            validate_bundle(root / 'dist/publication')
            published = metadata['sha256']
        else:
            published = verify_assets(root / 'dist/publication/numeric', version)
        if published != retained or metadata.get('versionName') != version or metadata.get('sha256') != retained:
            raise ValueError('Uploaded bundle must match all retained APKs')
    for relative in targets:
        path = root / relative
        if path.exists():
            shutil.rmtree(path)
    print('CI disk free after cleanup: %.1f GiB' % (shutil.disk_usage(root).free / 1024**3))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    phase = parser.add_mutually_exclusive_group()
    phase.add_argument('--after-upload', action='store_true')
    phase.add_argument('--before-signing', action='store_true')
    args = parser.parse_args()
    if args.before_signing:
        release_build_intermediates(Path(__file__).resolve().parents[1])
    else:
        release_storage(Path(__file__).resolve().parents[1], args.version, args.after_upload)
