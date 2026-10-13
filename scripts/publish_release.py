#!/usr/bin/env python3
"""Publish verified APKs, reviewed source snapshots, documentation and checksums."""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import urllib.error
import urllib.parse
import zipfile
from datetime import datetime, timezone, timedelta
from pathlib import Path, PurePosixPath

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.public_distribution import allowed_source
from scripts.verify_native_distribution import validate as validate_native

PUBLIC = 'owouwuiwi/nyanime'
SOURCE = 'owouwuiwi/nyanime-source'
CERTIFICATE = '7DD368792C4D94D1186CC4B0E2AD6C2F34FCE5DD37F19C6A1BAB45106FFC136B'
APPLICATION_ID = 'it.owouwuiwi.nyanime'
ASSET_PREFIX = 'Nyanime-Production'
ABIS = ('universal', 'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
ROOT_DOCS = {'README.md', 'CHANGELOG.md', 'LICENSE', 'NOTICE', 'CONTRIBUTING.md'}

class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None and urllib.parse.urlparse(req.full_url).hostname != urllib.parse.urlparse(newurl).hostname:
            redirected.remove_header('Authorization')
        return redirected

def request(path, token, method='GET', data=None, binary=False):
    headers = {'Authorization': 'Bearer ' + token,
               'Accept': 'application/octet-stream' if binary else 'application/vnd.github+json',
               'Cache-Control': 'no-cache',
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
        name.startswith('public/') and allowed_source(name.removeprefix('public/'))
    ) or bool(re.fullmatch(r'numeric/(?:Nyanime-Production-\d+\.\d+\.\d+\.\d+-(universal|arm64-v8a|armeabi-v7a|x86|x86_64)\.apk|Nyanime-Source-\d+\.\d+\.\d+\.\d+\.zip|Nyanime-Native-\d+\.\d+\.\d+\.\d+-[a-z0-9-]+\.tar\.gz|Nyanime-Native-Manifest-\d+\.\d+\.\d+\.\d+\.json)', name))

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
            if entry.filename in {'notes.md', 'publication.json'} or (
                entry.filename.startswith('public/') and document(entry.filename.removeprefix('public/')) and target.suffix != '.png'
            ):
                validate_text(target.read_text(encoding='utf-8'))
            elif target.suffix.lower() in {'.kt', '.kts', '.java', '.py', '.sh', '.yml', '.properties', '.json', '.xml'}:
                if re.search(rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)', target.read_bytes()):
                    raise ValueError('Credential in application source')

def validate_bundle(directory):
    metadata = json.loads((directory / 'publication.json').read_text())
    version = metadata['versionName']
    fields = {'schema', 'versionName', 'versionCode', 'channel', 'applicationId', 'assetPrefix', 'certificateSha256', 'sha256'}
    if metadata['schema'] in {3, 4}:
        fields |= {'sourceRevision', 'sourceSha256'}
    if metadata['schema'] == 4:
        fields.add('nativeManifest')
    if set(metadata) != fields or metadata['schema'] not in {2, 3, 4}:
        raise ValueError('Unknown publication metadata')
    if not re.fullmatch(r'\d+\.\d+\.\d+\.\d+', version):
        raise ValueError('Invalid OTA tags')
    if metadata['channel'] not in {'preview', 'recommended'} or type(metadata['versionCode']) is not int or not 134 <= metadata['versionCode'] <= 2_100_000_000:
        raise ValueError('Invalid channel or Android version code')
    if metadata['certificateSha256'] != CERTIFICATE or metadata['applicationId'] != APPLICATION_ID or metadata['assetPrefix'] != ASSET_PREFIX:
        raise ValueError('Signing identity changed')
    expected = {f'{ASSET_PREFIX}-{version}-{abi}.apk' for abi in ABIS}
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
    if metadata['schema'] in {3, 4}:
        name = f'Nyanime-Source-{version}.zip'
        expected_sources = {name}
        if metadata['schema'] == 4:
            manifest_name = f'Nyanime-Native-Manifest-{version}.json'
            if metadata['nativeManifest'] != manifest_name:
                raise ValueError('Invalid native manifest name')
            manifest_file = directory / 'numeric' / manifest_name
            declaration = json.loads(manifest_file.read_text())
            expected_sources.update(declaration['sourcePackages'])
            expected_sources.add(manifest_name)
            if manifest_file.read_bytes() != (directory / 'public/tools/native/distribution.json').read_bytes():
                raise ValueError('Native manifest differs from public source snapshot')
            for abi in ABIS:
                validate_native(directory / 'numeric' / f'{ASSET_PREFIX}-{version}-{abi}.apk', declaration, directory / 'numeric')
        if not re.fullmatch(r'[0-9a-f]{40}', metadata['sourceRevision']) or set(metadata['sourceSha256']) != expected_sources:
            raise ValueError('Invalid source identity')
        for material, digest in metadata['sourceSha256'].items():
            if not validate_name('numeric/' + material) or hashlib.sha256((directory / 'numeric' / material).read_bytes()).hexdigest() != digest:
                raise ValueError('Source hash mismatch')
        source = directory / 'numeric' / name
        if hashlib.sha256(source.read_bytes()).hexdigest() != metadata['sourceSha256'][name]:
            raise ValueError('Source hash mismatch')
        with zipfile.ZipFile(source) as package:
            expected_prefix = f'Nyanime-{version}/'
            if package.testzip() or any(not entry.filename.startswith(expected_prefix) or
                    not allowed_source(entry.filename.removeprefix(expected_prefix)) for entry in package.infolist()):
                raise ValueError('Invalid source archive contents')
            entries = {entry.filename.removeprefix(expected_prefix): entry for entry in package.infolist()}
            public = directory / 'public'
            files = {file.relative_to(public).as_posix(): file for file in public.rglob('*') if file.is_file()}
            if set(entries) != set(files) or len(entries) != len(package.infolist()):
                raise ValueError('Source archive differs from public snapshot')
            for name, entry in entries.items():
                if files[name].is_symlink() or hashlib.sha256(files[name].read_bytes()).digest() != hashlib.sha256(package.read(entry)).digest():
                    raise ValueError('Source archive differs from public snapshot')
    if not (directory / 'public/CHANGELOG.md').is_file() or not (directory / 'notes.md').is_file():
        raise ValueError('Missing release documentation')
    return metadata

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def commit_docs(directory, metadata):
    reserved = {'.github/workflows/publish.yml', 'scripts/publish_release.py'}
    for name in git('ls-files').splitlines():
        if not (allowed_source(name) or name.startswith('releases/') and name.endswith('.json') or name in reserved):
            raise ValueError('Public repository contains an unapproved file')
    documents = directory / 'public'
    current_versions = [tuple(map(int, file.stem.split('.'))) for file in Path('releases').glob('*.json')]
    if not current_versions or tuple(map(int, metadata['versionName'].split('.'))) >= max(current_versions):
        for name in git('ls-files').splitlines():
            if allowed_source(name) and name not in reserved and not (documents / name).exists():
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
        git('commit', '-m', f'Publish Nyanime {metadata["versionName"]} source, documentation and checksums')
        git('push', 'origin', 'HEAD:main')
    return git('rev-parse', 'HEAD')

def canonical_snapshot_date():
    bridges = []
    for file in Path('releases').glob('*.json'):
        metadata = json.loads(file.read_text())
        alias = metadata.get('legacyTag')
        if metadata.get('schema') == 1 and alias and re.fullmatch(r'r\d+', alias):
            bridges.append((tuple(map(int, file.stem.split('.'))), alias))
    if bridges:
        alias = max(bridges)[1]
        ref = 'refs/tags/' + alias
        if not subprocess.run(['git', 'cat-file', '-e', ref + '^{commit}'], capture_output=True).returncode == 0:
            git('fetch', 'origin', ref + ':' + ref)
        # GitHub groups releases by UTC commit day, then publication time. A
        # one-second difference within the same day still hides the bridge.
        # Freeze the previous UTC day relative to the unchanged bridge, rather
        # than today's date, so this remains correct for future publications.
        bridge = datetime.fromisoformat(git('log', '-1', '--format=%cI', ref)).astimezone(timezone.utc)
        return (bridge - timedelta(days=1)).replace(hour=23, minute=59, second=59, microsecond=0).isoformat()
    return (datetime.now(timezone.utc) - timedelta(days=1)).replace(hour=23, minute=59, second=59, microsecond=0).isoformat()


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
        date = canonical_snapshot_date()
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
        # By-tag reads only published releases. Resume a draft through the authenticated list.
        drafts = [item for item in request(base + '?per_page=100', token) if item['tag_name'] == tag]
        if len(drafts) > 1:
            raise ValueError('Ambiguous release drafts; refusing to select one silently')
        if drafts:
            release = drafts[0]
        else:
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

def archive_previous_alias_dates(current_tag):
    """Keep one current-day compatibility alias, including r9999 -> r10000."""
    token = os.environ['GITHUB_TOKEN']
    today = datetime.now(timezone.utc).date()
    date = (datetime.now(timezone.utc) - timedelta(days=1)).replace(hour=23, minute=59, second=59, microsecond=0).isoformat()
    # Each publication archives the previous current-day alias. Consequently
    # only the new alias and its predecessor occupy the current-day feed window;
    # older history can grow without an ever-growing scan on every publication.
    releases = request(f'repos/{PUBLIC}/releases?per_page=100', token)
    aliases = [item for item in releases if re.fullmatch(r'r\d+', item['tag_name'])]
    newest_tag = max([current_tag] + [item['tag_name'] for item in aliases], key=lambda tag: int(tag[1:]))
    for release in aliases:
        tag = release['tag_name']
        if tag == newest_tag or datetime.fromisoformat(release['created_at'].replace('Z', '+00:00')).date() < today:
            continue
        ref = request(f'repos/{PUBLIC}/git/ref/tags/{tag}', token)
        if ref['object']['type'] != 'commit':
            raise ValueError('Unexpected legacy documentation tag type')
        commit = request(f'repos/{PUBLIC}/git/commits/{ref["object"]["sha"]}', token)
        identity = {'name': 'owouwuiwi', 'email': 'owouwuiwi@users.noreply.github.com', 'date': date}
        archived = request(f'repos/{PUBLIC}/git/commits', token, 'POST', {
            'message': f'Archived compatibility documentation for {tag}',
            # Retain the existing documentation snapshot as parent. The tag
            # advances without replacing its existing history or any files.
            'tree': commit['tree']['sha'], 'parents': [ref['object']['sha']], 'author': identity, 'committer': identity,
        })
        # Only public documentation metadata changes. APK bytes, release records,
        # download URLs and all private source tags remain untouched.
        # GitHub's bot token cannot update existing refs containing workflow
        # history. The authorized ref token handles only this metadata update;
        # releases and APK uploads continue to belong to the public bot.
        request(f'repos/{PUBLIC}/git/refs/tags/{tag}', os.environ['PUBLIC_REF_TOKEN'], 'PATCH', {'sha': archived['sha'], 'force': False})
        request(f'repos/{PUBLIC}/releases/{release["id"]}', os.environ['PUBLIC_REF_TOKEN'], 'PATCH', {'target_commitish': archived['sha']})
    return newest_tag

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


def download_bundle(event, run, token):
    """Accept verified private draft assets, retaining support for older artifact runs."""
    run_id = int(event['source_run'])
    if 'release_id' in event:
        release_id, asset_id, attempt = (int(event[key]) for key in ('release_id', 'asset_id', 'source_attempt'))
        if min(release_id, asset_id, attempt) <= 0 or attempt > int(run['run_attempt']):
            raise ValueError('Invalid publication staging identifiers')
        if not re.fullmatch('[a-f0-9]{64}', event['bundle_sha256']):
            raise ValueError('Invalid publication archive hash')
        release = request(f'repos/{SOURCE}/releases/{release_id}', token)
        tag = f'ci-publication-{run_id}-{attempt}'
        if not release['draft'] or release['tag_name'] != tag or release['target_commitish'] != run['head_sha']:
            raise ValueError('Publication staging belongs to an unapproved source build')
        asset = next((item for item in release['assets'] if item['id'] == asset_id), None)
        if (not asset or asset['name'] != f'nyanime-publication-{run_id}-{attempt}.zip'
                or asset['state'] != 'uploaded' or not 0 < asset['size'] <= 2_000_000_000):
            raise ValueError('Missing, incomplete or unexpected staging asset')
        data = request(f'repos/{SOURCE}/releases/assets/{asset_id}', token, binary=True)
        if len(data) != asset['size'] or hashlib.sha256(data).hexdigest() != event['bundle_sha256']:
            raise ValueError('Downloaded publication archive checksum mismatch')
        return data
    artifact_id = int(event['artifact_id'])
    artifacts = request(f'repos/{SOURCE}/actions/runs/{run_id}/artifacts', token)['artifacts']
    artifact = next(item for item in artifacts if item['id'] == artifact_id)
    if artifact['expired'] or not artifact['name'].startswith('nyanime-publication-'):
        raise ValueError('Expired or unexpected build artifact')
    return request(f'repos/{SOURCE}/actions/artifacts/{artifact_id}/zip', token, binary=True)

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

def reviewed_release_inputs(payload):
    """A manual, device-reviewed build still uses pinned metadata and the bot."""
    version = payload.get('version', '')
    if not isinstance(version, str) or not re.fullmatch(r'\d+\.\d+\.\d+\.\d+', version):
        raise ValueError('Invalid reviewed release version')
    if payload.get('phase') not in {'prepare', 'publish'}:
        raise ValueError('Unknown reviewed publication phase')
    metadata = json.loads((Path('releases') / (version + '.json')).read_text())
    if (metadata['schema'] != 4 or metadata['versionName'] != version or
            metadata['certificateSha256'] != CERTIFICATE or metadata['applicationId'] != APPLICATION_ID or
            metadata['assetPrefix'] != ASSET_PREFIX or metadata['channel'] not in {'recommended', 'preview'} or
            type(metadata['versionCode']) is not int or not 134 <= metadata['versionCode'] <= 2_100_000_000 or
            not re.fullmatch(r'[a-f0-9]{40}', metadata['sourceRevision'])):
        raise ValueError('Reviewed signing, channel or source identity changed')
    tag = 'v' + version
    properties = dict(line.split('=', 1) for line in git('show', tag + ':release.properties').splitlines()
                      if '=' in line and not line.startswith('#'))
    if (properties.get('versionName') != version or properties.get('versionCode') != str(metadata['versionCode']) or
            properties.get('channel') != metadata['channel']):
        raise ValueError('Immutable source tag differs from reviewed release')
    declaration = json.loads(git('show', tag + ':tools/native/distribution.json'))
    manifest = f'Nyanime-Native-Manifest-{version}.json'
    source = f'Nyanime-Source-{version}.zip'
    if (metadata['nativeManifest'] != manifest or set(metadata['sourceSha256']) !=
            {manifest, source} | set(declaration['sourcePackages'])):
        raise ValueError('Reviewed native material set changed')
    expected_apks = {f'{ASSET_PREFIX}-{version}-{abi}.apk' for abi in ABIS}
    if set(metadata['sha256']) != expected_apks:
        raise ValueError('Reviewed APK architecture set changed')
    expected = metadata['sha256'] | metadata['sourceSha256']
    if any(not validate_name('numeric/' + name) or not re.fullmatch(r'[a-f0-9]{64}', digest)
           for name, digest in expected.items()):
        raise ValueError('Invalid reviewed material name or hash')
    reused = {}
    for name, record in declaration['sourcePackages'].items():
        prefix = f'https://github.com/{PUBLIC}/releases/download/'
        url = record.get('url', '')
        if not url.startswith(prefix) or not re.fullmatch(r'v\d+\.\d+\.\d+\.\d+/' + re.escape(name), url[len(prefix):]):
            raise ValueError('Native material must use an immutable public release URL')
        if record['sha256'] != expected[name]:
            raise ValueError('Reviewed native material hash changed')
        if not url.startswith(prefix + tag + '/'):
            reused[name] = record
    checksum = ''.join(f'{digest}  {name}\n' for name, digest in metadata['sha256'].items()).encode()
    uploads = {name: digest for name, digest in expected.items() if name not in reused}
    uploads['SHA256SUMS'] = hashlib.sha256(checksum).hexdigest()
    return metadata, uploads, reused


def reviewed_release(payload):
    metadata, expected, reused = reviewed_release_inputs(payload)
    token = os.environ['GITHUB_TOKEN']
    version, phase = metadata['versionName'], payload['phase']
    tag = 'v' + version
    base = f'repos/{PUBLIC}/releases'
    matches = []
    for page in range(1, 100):
        releases = request(base + f'?per_page=100&page={page}', token)
        matches.extend(item for item in releases if item['tag_name'] == tag)
        if len(releases) < 100:
            break
    if len(matches) > 1:
        raise ValueError('Multiple reviewed release drafts')
    if phase == 'prepare' and not matches:
        notes = payload.get('notes', '')
        if not isinstance(notes, str) or not notes.strip() or len(notes.encode()) > 50_000 or version not in notes:
            raise ValueError('Missing reviewed release notes')
        validate_text(notes)
        # The immutable tag already exists. Never create/move it with the bot,
        # or ask the release API to replace historical workflow-containing refs.
        release = request(base, token, 'POST', {'tag_name': tag, 'target_commitish': 'main',
            'name': 'Nyanime ' + version, 'body': notes, 'draft': True,
            'prerelease': metadata['channel'] == 'preview'})
    elif matches:
        release = matches[0]
    else:
        raise ValueError('Prepare the reviewed draft before staging verified files')
    if release['author']['login'] != 'github-actions[bot]' or release['author']['type'] != 'Bot':
        raise ValueError('Reviewed releases must belong to the public GitHub publisher')
    if phase == 'prepare':
        if not release['draft']:
            raise ValueError('An existing published release cannot be prepared again')
        print('Prepared bot-owned reviewed release draft: ' + tag, flush=True)
        return
    assets = {asset['name']: asset for asset in release['assets']}
    if len(assets) != len(release['assets']) or set(assets) != set(expected) or any(
            assets[name]['state'] != 'uploaded' or assets[name]['digest'] != 'sha256:' + digest
            for name, digest in expected.items()):
        raise ValueError('Reviewed staging assets do not match the pinned manifest')
    if not release['draft']:
        if any(asset['uploader']['login'] != 'github-actions[bot]' for asset in assets.values()):
            raise ValueError('Published assets have unexpected attribution')
        print('Reviewed release is already published; immutable assets left untouched.', flush=True)
        return
    temp = Path(os.environ['RUNNER_TEMP']) / ('reviewed-' + version)
    numeric = temp / 'numeric'
    numeric.mkdir(parents=True)
    # Re-check every staged byte and the complete corresponding native materials
    # before replacing any draft asset. Published downloads are never replaced.
    for name, digest in expected.items():
        data = request(base + '/assets/' + str(assets[name]['id']), token, binary=True)
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('Downloaded reviewed asset hash mismatch')
        (numeric / name).write_bytes(data)
    for name, record in reused.items():
        old_tag = record['url'].split('/releases/download/', 1)[1].split('/', 1)[0]
        older = request(base + '/tags/' + old_tag, token)
        old = next(item for item in older['assets'] if item['name'] == name)
        if older['draft'] or old['digest'] != 'sha256:' + record['sha256'] or old['browser_download_url'] != record['url']:
            raise ValueError('Reusable public native source changed')
        with urllib.request.urlopen(record['url'], timeout=180) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != record['sha256']:
            raise ValueError('Downloaded native source hash mismatch')
        (numeric / name).write_bytes(data)
    (temp / 'publication.json').write_text(json.dumps(metadata))
    (temp / 'notes.md').write_text(release['body'], encoding='utf-8')
    public = temp / 'public'
    with zipfile.ZipFile(numeric / f'Nyanime-Source-{version}.zip') as archive:
        prefix = f'Nyanime-{version}/'
        for entry in archive.infolist():
            name = entry.filename.removeprefix(prefix)
            if (not entry.filename.startswith(prefix) or not allowed_source(name) or
                    (entry.external_attr >> 16) & 0o170000 == 0o120000):
                raise ValueError('Unsafe reviewed source archive')
            target = public / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
    validate_bundle(temp)
    for name, asset in assets.items():
        if asset['uploader']['login'] == 'github-actions[bot]':
            continue
        request(base + '/assets/' + str(asset['id']), token, 'DELETE')
        subprocess.run(['gh', 'release', 'upload', tag, str(numeric / name), '--repo', PUBLIC],
                       check=True, env={**os.environ, 'GH_TOKEN': token})
    completed = request(base + '/' + str(release['id']), token)
    saved = {asset['name']: asset for asset in completed['assets']}
    if set(saved) != set(expected) or any(saved[name]['state'] != 'uploaded' or
            saved[name]['digest'] != 'sha256:' + digest or
            saved[name]['uploader']['login'] != 'github-actions[bot]' for name, digest in expected.items()):
        raise ValueError('Bot publication differs from the reviewed files')
    latest = False
    if metadata['channel'] == 'recommended':
        try:
            previous = request(base + '/latest', token)['tag_name'].removeprefix('v')
            latest = tuple(map(int, version.split('.'))) >= tuple(map(int, previous.split('.')))
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            latest = True
    request(base + '/' + str(release['id']), token, 'PATCH', {'draft': False,
        'prerelease': metadata['channel'] == 'preview', 'make_latest': str(latest).lower()})
    print('Published reviewed APKs and corresponding sources with the public bot: ' + tag, flush=True)


def main():
    if os.environ['GITHUB_REPOSITORY'] != PUBLIC:
        raise SystemExit('Wrong public publication repository')
    full_event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    if full_event['action'] == 'publish-reviewed-nyanime':
        reviewed_release(full_event['client_payload'])
        return
    if full_event['action'] == 'repair-historical-attribution':
        repair_attribution(full_event)
        return
    event = full_event['client_payload']
    run_id = int(event['source_run'])
    source_token = os.environ['SOURCE_ARTIFACT_TOKEN']
    run = request(f'repos/{SOURCE}/actions/runs/{run_id}', source_token)
    if run['head_branch'] != 'main' or run['event'] not in {'push', 'workflow_dispatch'}:
        raise ValueError('Unapproved build branch or event')
    temp = Path(os.environ['RUNNER_TEMP']) / f'nyanime-publication-{run_id}'
    temp.mkdir()
    archive = temp / 'bundle.zip'
    archive.write_bytes(download_bundle(event, run, source_token))
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
    # Old APKs and r aliases remain immutable migration bridges. A new certificate
    # must never be offered to their same-package updater as an in-place update.
    print('Published production APKs, checksums and docs; historical migration bridges preserved.')

if __name__ == '__main__':
    main()
