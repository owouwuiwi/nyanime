"""Transfer verified APKs and reviewed public source through a private draft Release."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import urllib.error
import zipfile
from pathlib import Path

from scripts.ci_release_storage import verify_assets
from scripts.public_distribution import ABIS, ASSET_PREFIX, allowed_document, assert_public_text
from scripts.request_publication import SOURCE_REPOSITORY, api
from distribution.scripts.publish_release import validate_bundle, validate_name


def make_archive(directory, archive):
    if directory.is_symlink() or archive.resolve().is_relative_to(directory.resolve()):
        raise ValueError('Unsafe publication staging directory')
    metadata = validate_bundle(directory)
    version = metadata['versionName']
    if metadata.get('delivery', 'full') == 'full' and verify_assets(directory / 'numeric', version) != metadata['sha256']:
        raise ValueError('Publication metadata differs from APK checksums')
    required = {'publication.json', 'notes.md', 'numeric/SHA256SUMS', 'public/CHANGELOG.md'}
    if metadata.get('delivery', 'full') == 'full':
        required.update(f'numeric/{ASSET_PREFIX}-{version}-{abi}.apk' for abi in ABIS)
    required.update('numeric/' + name for name in metadata.get('graphSha256', {}))
    required.update('numeric/' + name for name in metadata.get('deltaSha256', {}))
    required.update('numeric/' + name for name in metadata.get('sourceSha256', {}))
    files = []
    total = 0
    for file in sorted(directory.rglob('*')):
        if file.is_symlink():
            raise ValueError('Linked publication files are not allowed')
        if not file.is_file():
            continue
        name = file.relative_to(directory).as_posix()
        if not validate_name(name):
            raise ValueError('Unexpected file in publication bundle')
        if name in {'publication.json', 'notes.md'} or name.startswith('public/') and allowed_document(name.removeprefix('public/')) and file.suffix != '.png':
            assert_public_text(file.read_text(encoding='utf-8'))
        total += file.stat().st_size
        files.append((file, name))
    if not required.issubset({name for _, name in files}) or total > 2_000_000_000:
        raise ValueError('Incomplete or oversized publication bundle')
    archive.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=1) as package:
        for file, name in files:
            package.write(file, name)
    with archive.open('rb') as file:
        digest = hashlib.file_digest(file, 'sha256').hexdigest()
    return metadata, digest


def stage_bundle(directory, output):
    if os.environ['GITHUB_REPOSITORY'] != SOURCE_REPOSITORY:
        raise ValueError('Publication staging is allowed only in the private source repository')
    run_id = int(os.environ['GITHUB_RUN_ID'])
    attempt = int(os.environ['GITHUB_RUN_ATTEMPT'])
    revision = os.environ['GITHUB_SHA']
    token = os.environ['GITHUB_TOKEN']
    run = api(f'repos/{SOURCE_REPOSITORY}/actions/runs/{run_id}', token)
    if run['head_sha'] != revision or run['head_branch'] != 'main' or run['event'] not in {'push', 'workflow_dispatch'}:
        raise ValueError('Unapproved publication build')
    if int(run['run_attempt']) != attempt or attempt <= 0:
        raise ValueError('Publication attempt differs from the running build')
    archive = output.parent / f'nyanime-publication-{run_id}-{attempt}.zip'
    metadata, digest = make_archive(directory, archive)
    tag = f'ci-publication-{run_id}-{attempt}'
    base = f'repos/{SOURCE_REPOSITORY}/releases'
    try:
        release = api(base + '/tags/' + tag, token)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        drafts = [item for item in api(base + '?per_page=100', token) if item['tag_name'] == tag]
        if len(drafts) > 1:
            raise ValueError('Ambiguous publication staging drafts')
        if drafts:
            release = drafts[0]
        else:
            release = api(base, token, 'POST', {'tag_name': tag, 'target_commitish': revision,
                'name': f'Temporary verified publication {run_id}/{attempt}', 'draft': True,
                'prerelease': True, 'body': 'Private transfer of verified APKs and public documents. Removed after successful publication.'})
    if not release['draft'] or release['tag_name'] != tag or release['target_commitish'] != revision:
        raise ValueError('Unexpected publication staging release')
    asset = next((item for item in release['assets'] if item['name'] == archive.name), None)
    if asset is None:
        subprocess.run(['gh', 'release', 'upload', tag, str(archive), '--repo', SOURCE_REPOSITORY],
            check=True, env={**os.environ, 'GH_TOKEN': token})
        release = api(base + '/' + str(release['id']), token)
        asset = next(item for item in release['assets'] if item['name'] == archive.name)
    if asset['state'] != 'uploaded' or asset['size'] != archive.stat().st_size:
        raise ValueError('Incomplete publication staging upload')
    actual = asset.get('digest')
    if actual is None:
        data = api(base + '/assets/' + str(asset['id']), token, binary=True)
        actual = 'sha256:' + hashlib.sha256(data).hexdigest()
    if actual != 'sha256:' + digest:
        raise ValueError('Publication staging checksum mismatch; refusing to replace existing bytes')
    stage = {'source_run': run_id, 'source_attempt': attempt, 'source_sha': revision,
             'release_id': release['id'], 'asset_id': asset['id'], 'bundle_sha256': digest,
             'version': metadata['versionName']}
    output.write_text(json.dumps(stage, indent=2) + '\n', encoding='utf-8')
    print('Verified publication bundle retained in private draft Release:', release['id'])
    return stage


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    arguments = parser.parse_args()
    stage_bundle(arguments.directory, arguments.output)
