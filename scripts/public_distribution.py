#!/usr/bin/env python3
"""Publish reviewed application source alongside immutable signed releases."""
import argparse
import hashlib
import io
import json
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.release_notes import validate_notes_format

PUBLIC_REPOSITORY = 'owouwuiwi/nyanime'
CERTIFICATE = '7DD368792C4D94D1186CC4B0E2AD6C2F34FCE5DD37F19C6A1BAB45106FFC136B'
APPLICATION_ID = 'it.owouwuiwi.nyanime'
ASSET_PREFIX = 'Nyanime-Production'
ABIS = ('universal', 'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
ROOT_DOCUMENTS = {'README.md', 'CHANGELOG.md', 'LICENSE', 'NOTICE', 'CONTRIBUTING.md'}
SOURCE_ROOTS = {'addon-api', 'app', 'artwork', 'buildSrc', 'core', 'core-metadata',
                'data', 'distribution', 'domain', 'gradle', 'i18n', 'i18n-aniyomi',
                'macrobenchmark', 'news-api', 'presentation-core', 'presentation-widget',
                'scripts', 'source-api', 'source-local', 'tools', 'tv-api', 'vendor'}
SOURCE_FILES = {'.editorconfig', '.gitattributes', '.gitignore', 'CODE_OF_CONDUCT.md',
                'app-identity.properties', 'build.gradle.kts', 'gradle.properties',
                'gradlew', 'gradlew.bat', 'release.properties', 'settings.gradle.kts'}

def allowed_document(name):
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or '\\' in name or not path.parts:
        return False
    return name in ROOT_DOCUMENTS or (
        path.parts[0] == 'docs' and path.suffix == '.md' and 'qa' not in path.parts
    ) or name in {'.github/assets/nyanime.svg', '.github/assets/logo.png'}

def allowed_source(name):
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or '\\' in name or ':' in name or not path.parts:
        return False
    if any(part in {'build', '.gradle', '.local', '__pycache__'} for part in path.parts):
        return False
    if path.name == 'local.properties' or path.suffix.lower() in {'.apk', '.jks', '.p12', '.keystore'}:
        return False
    return allowed_document(name) or name in SOURCE_FILES or path.parts[0] in SOURCE_ROOTS

def public_text(content):
    # Compatibility endpoint literals remain in private source; public prose uses
    # the current publisher. This is also applied to historical documentation.
    content = re.sub(r'(?i)lorenzogarrone2000@gmail\.com', 'owouwuiwi@users.noreply.github.com', content)
    content = re.sub(r'(?i)Lorenzo\s+Garrone', 'owouwuiwi', content)
    content = re.sub(r'(?i)(?:lorenzogarrone|noire342)/(?:ultrayomi|nyanime)', PUBLIC_REPOSITORY, content)
    content = re.sub(r'(?i)(?:lorenzogarrone|noire342)\.github\.io', 'owouwuiwi.github.io', content)
    content = re.sub(r'(?i)(?:https://github\.com/)?lorenzogarrone\b', 'owouwuiwi', content)
    content = re.sub(r'(?i)C:[\\/]+Users[\\/]+[^\s`"<>]+', '[local workspace]', content)
    assert_public_text(content)
    return content

def assert_public_text(content):
    if re.search(r'(?i)lorenzo\s*garrone|lorenzogarrone|C:[\\/]+Users[\\/]|(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})', content):
        raise ValueError('Public export contains personal data, a local path or credential')


def validate_release_notes(content):
    """Reject corrupted text or unstructured notes before GitHub publication."""
    assert_public_text(content)
    if re.search(r'Ã[\u0080-\u00bf]|â[€\u201a\u0153\u0080-\u00bf]|ðŸ|\ufffd', content):
        raise ValueError('Release notes contain corrupted UTF-8 text')
    validate_notes_format(content)

def git(*args, cwd=None):
    return subprocess.check_output(['git', *args], cwd=cwd)

def export_documents(destination, revision='HEAD', cwd=None):
    with zipfile.ZipFile(io.BytesIO(git('archive', '--format=zip', revision, cwd=cwd))) as snapshot:
        for entry in snapshot.infolist():
            name = entry.filename
            if entry.is_dir() or not allowed_source(name):
                continue
            data = snapshot.read(entry)
            # Preserve code and embedded licence texts verbatim. Redaction applies
            # only to public prose; it must never rewrite an upstream copyright.
            if allowed_document(name) and name not in {'LICENSE', 'NOTICE'} and not name.endswith('.png'):
                data = public_text(data.decode('utf-8')).encode('utf-8')
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            mode = (entry.external_attr >> 16) & 0o777
            if mode:
                target.chmod(mode)

def archive_source(destination, source_directory, version):
    with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as package:
        for file in sorted(source_directory.rglob('*')):
            if file.is_file():
                name = file.relative_to(source_directory).as_posix()
                if not allowed_source(name):
                    raise ValueError('Unexpected material in source archive')
                entry = zipfile.ZipInfo(f'Nyanime-{version}/{name}', (1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = (0o100755 if name == 'gradlew' or name.endswith('.sh') else 0o100644) << 16
                package.writestr(entry, file.read_bytes())
    with zipfile.ZipFile(destination) as package:
        if package.testzip():
            raise ValueError('Invalid source archive')

def native_materials(manifest, destination, cache=None):
    """Retain hash-pinned materials, either locally supplied or publicly downloadable."""
    for name, record in manifest['sourcePackages'].items():
        if not re.fullmatch(r'Nyanime-Native-\d+\.\d+\.\d+\.\d+-[a-z0-9-]+\.tar\.gz', name):
            raise ValueError('Unexpected native-source package name')
        target = destination / name
        local = cache / name if cache else None
        if local and local.is_file():
            shutil.copyfile(local, target)
        else:
            url = record['url']
            if not url.startswith(f'https://github.com/{PUBLIC_REPOSITORY}/releases/download/v') or not url.endswith('/' + name):
                raise ValueError('Native sources must accompany a public Nyanime release')
            for attempt in range(3):
                try:
                    with urllib.request.urlopen(url, timeout=90) as response, target.open('wb') as file:
                        shutil.copyfileobj(response, file, length=1024 * 1024)
                    break
                except (OSError, TimeoutError):
                    target.unlink(missing_ok=True)
                    if attempt == 2:
                        raise
                    time.sleep(2)
        with target.open('rb') as file:
            if hashlib.file_digest(file, 'sha256').hexdigest() != record['sha256']:
                raise ValueError('Native-source archive hash mismatch')


def prepare_bundle(output, apk_directory, version, code, channel, notes, revision, materials=None):
    if not re.fullmatch(r'\d+\.\d+\.\d+\.\d+', version):
        raise ValueError('Invalid OTA version')
    if channel not in {'preview', 'recommended'}:
        raise ValueError('Invalid channel')
    if output.exists() and any(output.iterdir()):
        raise ValueError('Bundle output must be empty')
    notes_text = public_text(notes.read_text(encoding='utf-8'))
    validate_release_notes(notes_text)
    docs = output / 'public'
    docs.mkdir(parents=True)
    export_documents(docs, revision)
    # A public documentation tag never resolves to the private source revision.
    notes_text = re.sub(r'https://github\.com/[^\s)]+/blob/[^/]+/CHANGELOG\.md',
                        f'https://github.com/{PUBLIC_REPOSITORY}/blob/v{version}/CHANGELOG.md', notes_text)
    (output / 'notes.md').write_text(notes_text, encoding='utf-8')
    assets = output / 'numeric'
    assets.mkdir()
    hashes = {}
    for abi in ABIS:
        name = f'{ASSET_PREFIX}-{version}-{abi}.apk'
        source = apk_directory / name
        if not source.is_file():
            raise ValueError(f'Missing signed APK: {name}')
        shutil.copyfile(source, assets / name)
        hashes[name] = hashlib.sha256(source.read_bytes()).hexdigest()
    manifest = apk_directory / 'SHA256SUMS'
    if manifest.is_file():
        checksums = manifest.read_text(encoding='utf-8')
        records = [line.split('  ', 1) for line in checksums.splitlines()]
        if any(len(record) != 2 for record in records):
            raise ValueError('Malformed existing checksum manifest')
        existing = {name: digest for digest, name in records}
        if len(records) != len(hashes) or existing != hashes:
            raise ValueError('Existing checksum manifest does not match all APKs')
        hashes = existing
    else:
        hashes = dict(sorted(hashes.items()))
        checksums = ''.join(f'{digest}  {name}\n' for name, digest in hashes.items())
    (assets / 'SHA256SUMS').write_text(checksums, encoding='utf-8', newline='\n')
    source_name = f'Nyanime-Source-{version}.zip'
    archive_source(assets / source_name, docs, version)
    source_hash = hashlib.sha256((assets / source_name).read_bytes()).hexdigest()
    metadata = {'schema': 3, 'versionName': version, 'versionCode': code,
                'channel': channel, 'applicationId': APPLICATION_ID, 'assetPrefix': ASSET_PREFIX,
                'certificateSha256': CERTIFICATE, 'sha256': hashes,
                'sourceRevision': git('rev-parse', revision).decode().strip(),
                'sourceSha256': {source_name: source_hash}}
    native = docs / 'tools/native/distribution.json'
    if native.is_file():
        from scripts.verify_native_distribution import validate
        declaration = json.loads(native.read_text())
        native_materials(declaration, assets, materials)
        name = f'Nyanime-Native-Manifest-{version}.json'
        shutil.copyfile(native, assets / name)
        metadata.update(schema=4, nativeManifest=name)
        metadata['sourceSha256'][name] = hashlib.sha256(native.read_bytes()).hexdigest()
        metadata['sourceSha256'].update({key: item['sha256'] for key, item in declaration['sourcePackages'].items()})
        for abi in ABIS:
            validate(assets / f'{ASSET_PREFIX}-{version}-{abi}.apk', declaration, assets)
    (output / 'publication.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--apk-directory', required=True, type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--code', required=True, type=int)
    parser.add_argument('--channel', required=True)
    parser.add_argument('--notes', required=True, type=Path)
    parser.add_argument('--revision', default='HEAD')
    parser.add_argument('--native-materials', type=Path)
    args = parser.parse_args()
    prepare_bundle(args.output, args.apk_directory, args.version, args.code,
                   args.channel, args.notes, args.revision, args.native_materials)

if __name__ == '__main__':
    main()
