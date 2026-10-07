#!/usr/bin/env python3
"""Public bot publisher. Receives signed APKs and documentation, never app source."""
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
import urllib.request
import urllib.error
import urllib.parse
import zipfile
from datetime import datetime, timezone, timedelta
from pathlib import Path, PurePosixPath

PUBLIC = 'owouwuiwi/nyanime'
SOURCE = 'owouwuiwi/nyanime-source'
CERTIFICATE = 'F99AD47F9286CE3D61345F65DD0F888F16AEBF53BFE75EFE5B6AB889041AB259'
ABIS = ('universal', 'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
ROOT_DOCS = {'README.md', 'CHANGELOG.md', 'LICENSE', 'NOTICE', 'CONTRIBUTING.md'}

class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None and urllib.parse.urlparse(req.full_url).hostname != urllib.parse.urlparse(newurl).hostname:
            redirected.remove_header('Authorization')
        return redirected

def request(path, token, method='GET', data=None, binary=False):
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
               'User-Agent': 'Nyanime-release', 'X-GitHub-Api-Version': '2022-11-28'}
    req = urllib.request.Request('https://api.github.com/' + path, headers=headers,
        data=json.dumps(data).encode() if data is not None else None, method=method)
    with urllib.request.build_opener(SafeRedirect()).open(req, timeout=90) as response:
        content = response.read()
        return content if binary else (json.loads(content) if content else None)

def document(name):
    p = PurePosixPath(name)
    return name in ROOT_DOCS or (p.parts[0] == 'docs' and p.suffix == '.md' and 'qa' not in p.parts) or name in {
        '.github/assets/nyanime.svg', '.github/assets/logo.png'}

def validate_name(name):
    p = PurePosixPath(name)
    if p.is_absolute() or '..' in p.parts or '\\' in name or ':' in name or not p.parts:
        raise ValueError('Unsafe publication archive path')
    return name in {'publication.json', 'notes.md', 'numeric/SHA256SUMS'} or (
        name.startswith('public/') and document(name.removeprefix('public/'))
    ) or bool(re.fullmatch(r'numeric/Nyanime-\d+\.\d+\.\d+\.\d+-(universal|arm64-v8a|armeabi-v7a|x86|x86_64)\.apk', name))

def validate_text(text):
    blocked = {'807e4e92b75624ff2c9c3fd01ece23f84c4b2bfeec6816460539628f676d903d', '64a5090b45a4941f8ebc96f9c2f9f66558680fead5ddcbb8b780c1dc24d816a2', 'c4855bb0c8c2b1e2856a0aaf11f3b15ea7f0b353e2aa34dade10e6e435bc97c4'}
    tokens = re.findall(r'[\w@.+-]+', text.casefold())
    candidates = tokens + [' '.join(pair) for pair in zip(tokens, tokens[1:])]
    if any(hashlib.sha256(value.encode()).hexdigest() in blocked for value in candidates) or re.search(
        r'(?i)C:[\\/]+Users[\\/]|(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})', text
    ):
        raise ValueError('Personal information or credentials in public publication')

def unpack(archive, destination):
    seen = set()
    total = 0
    with zipfile.ZipFile(archive) as package:
        for entry in package.infolist():
            if entry.is_dir():
                continue
            if entry.filename in seen or not validate_name(entry.filename) or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Unexpected file, duplicate path or symlink in release bundle')
            seen.add(entry.filename)
            total += entry.file_size
            if total > 2_000_000_000:
                raise ValueError('Publication archive exceeds maximum size')
            target = destination / entry.filename
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(package.read(entry))
            if target.suffix in {'.md', '.svg', '.json'} or target.name in {'LICENSE', 'NOTICE'}:
                validate_text(target.read_text(encoding='utf-8'))

def validate_bundle(directory):
    metadata = json.loads((directory / 'publication.json').read_text())
    version = metadata['versionName']
    if set(metadata) != {'schema', 'versionName', 'versionCode', 'channel', 'legacyTag', 'certificateSha256', 'sha256'} or metadata['schema'] != 1:
        raise ValueError('Unknown publication metadata')
    if not re.fullmatch(r'\d+\.\d+\.\d+\.\d+', version) or not re.fullmatch(r'r\d+', metadata['legacyTag']):
        raise ValueError('Invalid OTA tags')
    if metadata['channel'] not in {'preview', 'recommended'} or not 134 <= metadata['versionCode'] <= 2_100_000_000:
        raise ValueError('Invalid channel or Android version code')
    if metadata['certificateSha256'] != CERTIFICATE:
        raise ValueError('Signing identity changed')
    expected = {f'Nyanime-{version}-{abi}.apk' for abi in ABIS}
    if set(metadata['sha256']) != expected:
        raise ValueError('Missing ABI or unexpected APK')
    checksums = ''
    for name, digest in metadata['sha256'].items():
        apk = directory / 'numeric' / name
        if hashlib.sha256(apk.read_bytes()).hexdigest() != digest:
            raise ValueError('APK hash mismatch')
        checksums += f'{digest}  {name}\n'
    if (directory / 'numeric/SHA256SUMS').read_text() != checksums:
        raise ValueError('Checksum manifest mismatch')
    if not (directory / 'public/CHANGELOG.md').is_file() or not (directory / 'notes.md').is_file():
        raise ValueError('Missing release documentation')
    return metadata

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def commit_docs(directory, metadata):
    # Before any commit, reject source files already present in the public checkout.
    for name in git('ls-files').splitlines():
        if not (document(name) or name.startswith('releases/') and name.endswith('.json') or
                name in {'.github/workflows/publish.yml', 'scripts/publish_release.py', '.gitignore'}):
            raise ValueError('Public repository contains an unapproved file')
    documents = directory / 'public'
    for name in git('ls-files').splitlines():
        if document(name) and not (documents / name).exists():
            Path(name).unlink()
    for source in documents.rglob('*'):
        if source.is_file():
            target = Path(source.relative_to(documents))
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
    release_path = Path('releases') / (metadata['versionName'] + '.json')
    release_path.parent.mkdir(exist_ok=True)
    if release_path.exists():
        previous = json.loads(release_path.read_text())
        if previous['sha256'] != metadata['sha256'] or previous['versionCode'] != metadata['versionCode']:
            raise ValueError('Published version bytes or Android version code cannot be replaced')
    release_path.write_text(json.dumps(metadata, indent=2) + '\n')
    git('config', 'user.name', 'owouwuiwi')
    git('config', 'user.email', 'owouwuiwi@users.noreply.github.com')
    git('add', '-A')
    if git('diff', '--cached', '--name-only'):
        git('commit', '-m', f'Publish Nyanime {metadata["versionName"]} documentation and checksums')
        git('push', 'origin', 'HEAD:main')
    return git('rev-parse', 'HEAD')

def ensure_tag(tag, commit, create_alias_commit=False, canonical_snapshot=False):
    refs = git('ls-remote', '--tags', 'origin', f'refs/tags/{tag}')
    if refs:
        # A published documentation tag is immutable after the one-time migration.
        return refs.split()[0]
    if canonical_snapshot:
        # GitHub groups the release feed by commit date before ordering tags.
        # Documentation-only canonical snapshots occupy the previous UTC day;
        # the current-day r alias is therefore visible to even first-release-only
        # clients. Actual publication times and private source dates are unchanged.
        date = (datetime.now(timezone.utc) - timedelta(days=1)).replace(hour=23, minute=59, second=59, microsecond=0).isoformat()
        commit = subprocess.check_output(['git', 'commit-tree', git('rev-parse', 'HEAD^{tree}')],
            input=f'Archived release documentation for {tag}\n', text=True,
            env={**os.environ, 'GIT_AUTHOR_DATE': date, 'GIT_COMMITTER_DATE': date}).strip()
    elif create_alias_commit:
        commit = subprocess.check_output(['git', 'commit-tree', git('rev-parse', 'HEAD^{tree}'), '-p', commit],
            input=f'Compatibility documentation for {tag}\n', text=True).strip()
    git('tag', tag, commit)
    git('push', 'origin', f'refs/tags/{tag}')
    return commit

def published(tag, directory, notes, metadata, commit, canonical):
    token = os.environ['GITHUB_TOKEN']
    base = f'repos/{PUBLIC}/releases'
    try:
        release = request(base + '/tags/' + tag, token)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        release = request(base, token, 'POST', {'tag_name': tag, 'target_commitish': commit,
            'name': 'Nyanime ' + metadata['versionName'], 'body': notes, 'draft': True,
            'prerelease': not canonical or metadata['channel'] == 'preview'})
    assets = {asset['name']: asset for asset in release['assets']}
    for file in directory.iterdir():
        digest = hashlib.sha256(file.read_bytes()).hexdigest()
        if file.name in assets:
            asset = assets[file.name]
            actual = asset.get('digest')
            if actual != 'sha256:' + digest:
                with urllib.request.urlopen(asset['browser_download_url'], timeout=120) as response:
                    actual = 'sha256:' + hashlib.sha256(response.read()).hexdigest()
                if actual != 'sha256:' + digest:
                    raise ValueError('Existing public asset differs; refusing to overwrite it')
            continue
        if not release['draft']:
            raise ValueError('Published release is incomplete; refusing to modify its APKs')
        subprocess.run(['gh', 'release', 'upload', tag, str(file), '--repo', PUBLIC], check=True,
                       env={**os.environ, 'GH_TOKEN': token})
    latest = False
    if canonical and metadata['channel'] == 'recommended':
        try:
            previous = request(base + '/latest', token)['tag_name'].removeprefix('v')
            latest = tuple(map(int, metadata['versionName'].split('.'))) >= tuple(map(int, previous.split('.')))
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            latest = True
    request(base + '/' + str(release['id']), token, 'PATCH', {'name': 'Nyanime ' + metadata['versionName'],
        'body': notes, 'draft': False, 'prerelease': not canonical or metadata['channel'] == 'preview',
        'make_latest': str(latest).lower()})

def legacy_first(feed, abi='arm64-v8a'):
    # Exact selection of the first custom updater, before the stricter asset filter.
    build_types = ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')
    for release in feed:
        assets = {}
        for asset in release['assets']:
            kind = next((kind for kind in build_types if '-' + kind in asset['name']), None)
            assets[kind] = asset['browser_download_url']
        if assets.get(abi) or assets.get(None):
            return release['tag_name']
    return None

def repair_attribution(event):
    """Re-upload unchanged historical assets with the bot, preserving URLs."""
    payload = event['client_payload']
    tag = payload['tag']
    if not re.fullmatch(r'r\d+', tag):
        raise ValueError('Historical repair requires a legacy application tag')
    token = os.environ['GITHUB_TOKEN']
    release = request(f'repos/{PUBLIC}/releases/tags/{tag}', token)
    if release.get('immutable') or release['draft']:
        raise ValueError('Historical release cannot be repaired')
    requested = {int(value) for value in payload['asset_ids']}
    assets = [asset for asset in release['assets'] if asset['id'] in requested]
    if len(assets) != len(requested):
        raise ValueError('Requested historical asset not found')
    temp = Path(os.environ['RUNNER_TEMP']) / 'historical-attribution'
    temp.mkdir()
    for asset in assets:
        name = asset['name']
        if name != 'SHA256SUMS' and not re.fullmatch(r'app-(universal|arm64-v8a|armeabi-v7a|x86|x86_64)-preview\.apk', name):
            raise ValueError('Unexpected historical asset')
        if asset['uploader']['type'] != 'User':
            continue
        url = asset['browser_download_url']
        if not url.startswith(f'https://github.com/{PUBLIC}/releases/download/{tag}/'):
            raise ValueError('Unexpected download host')
        file = temp / name
        with urllib.request.urlopen(url, timeout=180) as response:
            file.write_bytes(response.read())
        digest = 'sha256:' + hashlib.sha256(file.read_bytes()).hexdigest()
        if digest != asset['digest']:
            raise ValueError('Historical asset hash mismatch')
        subprocess.run(['gh', 'release', 'upload', tag, str(file), '--repo', PUBLIC, '--clobber'],
                       check=True, env={**os.environ, 'GH_TOKEN': token})
        updated = request(f'repos/{PUBLIC}/releases/tags/{tag}', token)
        replacement = next(item for item in updated['assets'] if item['name'] == name)
        if replacement['digest'] != digest or replacement['browser_download_url'] != url or replacement['uploader']['type'] != 'Bot':
            raise ValueError('Historical repair did not preserve bytes, URL and bot ownership')
    print('Historical assets retain their bytes and download URLs; attribution belongs to the bot.')

def main():
    if os.environ['GITHUB_REPOSITORY'] != PUBLIC:
        raise SystemExit('Wrong public publication repository')
    full_event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    if full_event['action'] == 'repair-historical-attribution':
        repair_attribution(full_event)
        return
    event = full_event['client_payload']
    run_id, artifact_id = int(event['source_run']), int(event['artifact_id'])
    source_token = os.environ['SOURCE_ARTIFACT_TOKEN']
    run = request(f'repos/{SOURCE}/actions/runs/{run_id}', source_token)
    if run['head_branch'] != 'main' or run['event'] not in {'push', 'workflow_dispatch'}:
        raise ValueError('Unapproved build branch or event')
    artifacts = request(f'repos/{SOURCE}/actions/runs/{run_id}/artifacts', source_token)['artifacts']
    artifact = next(item for item in artifacts if item['id'] == artifact_id)
    if artifact['expired'] or not artifact['name'].startswith('nyanime-publication-'):
        raise ValueError('Expired or unexpected build artifact')
    temp = Path(os.environ['RUNNER_TEMP']) / f'nyanime-publication-{run_id}'
    temp.mkdir()
    archive = temp / 'bundle.zip'
    archive.write_bytes(request(f'repos/{SOURCE}/actions/artifacts/{artifact_id}/zip', source_token, binary=True))
    bundle = temp / 'bundle'
    unpack(archive, bundle)
    metadata = validate_bundle(bundle)
    if metadata['versionName'] != event['version']:
        raise ValueError('Dispatched version differs from verified bundle')
    docs_commit = commit_docs(bundle, metadata)
    canonical = 'v' + metadata['versionName']
    tag_commit = ensure_tag(canonical, docs_commit, canonical_snapshot=True)
    notes = (bundle / 'notes.md').read_text(encoding='utf-8')
    published(canonical, bundle / 'numeric', notes, metadata, tag_commit, True)
    legacy = bundle / 'legacy'
    legacy.mkdir()
    sums = ''
    for abi in ABIS:
        name = f'app-{abi}-preview.apk'
        shutil.copyfile(bundle / 'numeric' / f'Nyanime-{metadata["versionName"]}-{abi}.apk', legacy / name)
        sums += f'{hashlib.sha256((legacy / name).read_bytes()).hexdigest()}  {name}\n'
    (legacy / 'SHA256SUMS').write_text(sums)
    legacy_commit = ensure_tag(metadata['legacyTag'], docs_commit, True)
    published(metadata['legacyTag'], legacy, notes, metadata, legacy_commit, False)
    feed = request(f'repos/{PUBLIC}/releases?per_page=20', os.environ['GITHUB_TOKEN'])
    if legacy_first(feed) != metadata['legacyTag']:
        raise ValueError('Oldest OTA client does not see the latest compatibility release first')
    print('Published signed APKs, checksums and docs; verified the legacy OTA feed.')

if __name__ == '__main__':
    main()
