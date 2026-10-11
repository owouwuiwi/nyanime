#!/usr/bin/env python3
"""Byte-exact ZIP-aware APK deltas. Original code; Python/JDK gzip, no native codec.

Never re-sign or re-pack an APK. COPY refers to signed base bytes; INSERT carries
exact target bytes (including ZIP metadata and APK signing block). See docs/delta-updates.md.
"""
import argparse
import gzip
import hashlib
import json
import mmap
from pathlib import Path
import re
import struct
import tempfile
import urllib.request
import urllib.parse
import zipfile

MAGIC = b'NYADLT01'
MANIFEST = 'Nyanime-Delta-v1.json'
MAX_APK = 1_073_741_824
MAX_COMMANDS = 100_000
BLOCK = 64 * 1024
ABI = r'(?:universal|arm64-v8a|armeabi-v7a|x86|x86_64)'
VERSION = r'\d+\.\d+\.\d+\.\d+'
APK_NAME = re.compile(rf'Nyanime-Production-({VERSION})-({ABI})\.apk')
PATCH_NAME = re.compile(rf'Nyanime-Delta-[a-f0-9]{{16}}-({VERSION})-({ABI})\.nyadelta')


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def worthwhile(patch_size, target_size):
    return 0 < patch_size <= target_size * 4 // 5 and target_size - patch_size >= 1_048_576


def fetch_latest_base(cache, version, repository='owouwuiwi/nyanime'):
    """Best-effort build optimization. Hash-pin an immutable public base, cache it locally.

    Network/cache problems skip affected bases rather than blocking a full release.
    No credentials are sent and no remote program or downloaded code is executed.
    """
    class ReleaseRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, response, code, message, headers, target):
            parsed = urllib.parse.urlsplit(target)
            if parsed.scheme != 'https' or parsed.hostname not in {'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}:
                raise ValueError('Unexpected base APK redirect')
            return super().redirect_request(request, response, code, message, headers, target)

    opener = urllib.request.build_opener(ReleaseRedirect())
    request = urllib.request.Request(f'https://api.github.com/repos/{repository}/releases/latest',
                                     headers={'User-Agent': 'Nyanime-delta-preparation', 'Accept': 'application/vnd.github+json'})
    try:
        with opener.open(request, timeout=15) as response:
            data = response.read(2_097_153)
        if len(data) > 2_097_152:
            return []
        release = json.loads(data)
        tag = release.get('tag_name', '')
        if (release.get('draft') or not re.fullmatch('v' + VERSION, tag) or
                tuple(map(int, tag[1:].split('.'))) >= tuple(map(int, version.split('.')))):
            return []
        destination = Path(cache) / tag
        destination.mkdir(parents=True, exist_ok=True)
        verified = {}
        for asset in release.get('assets', []):
            name, digest = asset.get('name', ''), asset.get('digest', '')
            match = APK_NAME.fullmatch(name)
            if (not match or match[1] != tag[1:] or not isinstance(digest, str) or
                    not re.fullmatch('sha256:[a-f0-9]{64}', digest) or
                    type(asset.get('size')) is not int or not 0 < asset['size'] <= MAX_APK):
                continue
            url = f'https://github.com/{repository}/releases/download/{tag}/{name}'
            if asset.get('browser_download_url') != url:
                continue
            file = destination / name
            expected = digest.removeprefix('sha256:')
            if file.is_file() and file.stat().st_size == asset['size'] and sha256(file) == expected:
                verified[name] = expected
                continue
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(dir=destination, prefix='download-', suffix='.tmp', delete=False) as output:
                    temporary = Path(output.name)
                    with opener.open(url, timeout=90) as response:
                        total = 0
                        while block := response.read(1024 * 1024):
                            total += len(block)
                            if total > asset['size']:
                                raise ValueError('Base APK exceeds declared size')
                            output.write(block)
                if total != asset['size'] or sha256(temporary) != expected:
                    raise ValueError('Base APK hash mismatch')
                temporary.replace(file)
                verified[name] = expected
            except (OSError, ValueError):
                # An invalid old cache entry must not become an unsigned fallback base.
                file.unlink(missing_ok=True)
            finally:
                if temporary:
                    temporary.unlink(missing_ok=True)
        (destination / 'verified-bases.json').write_text(json.dumps(verified, sort_keys=True) + '\n', encoding='utf-8')
        return [destination] if verified else []
    except (OSError, ValueError, TypeError, KeyError):
        return []


def _payloads(path, content):
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > MAX_COMMANDS or len({item.filename for item in entries}) != len(entries):
            raise ValueError('Ambiguous or oversized APK ZIP')
        previous = 0
        result = {}
        for entry in sorted(entries, key=lambda item: item.header_offset):
            offset = entry.header_offset
            if offset < previous or content[offset:offset + 4] != b'PK\x03\x04':
                raise ValueError('Invalid APK entry offset')
            name_length, extra_length = struct.unpack_from('<HH', content, offset + 26)
            start = offset + 30 + name_length + extra_length
            end = start + entry.compress_size
            if not offset < start <= end <= len(content):
                raise ValueError('APK entry outside file')
            previous = end
            if entry.compress_size:
                result[entry.filename] = (start, end)
        return result


def create_patch(base, target, output):
    base, target, output = map(Path, (base, target, output))
    if output.resolve() in {base.resolve(), target.resolve()}:
        raise ValueError('Output would overwrite an input')
    if not (0 < base.stat().st_size <= MAX_APK and 0 < target.stat().st_size <= MAX_APK):
        raise ValueError('APK size outside bounds')
    commands = []

    def append(kind, offset, length):
        if length == 0:
            return
        if commands and commands[-1][0] == kind and commands[-1][1] + commands[-1][2] == offset:
            previous_kind, previous_offset, previous_length = commands.pop()
            commands.append((previous_kind, previous_offset, previous_length + length))
        else:
            commands.append((kind, offset, length))

    with base.open('rb') as old_file, target.open('rb') as new_file, \
            mmap.mmap(old_file.fileno(), 0, access=mmap.ACCESS_READ) as old, \
            mmap.mmap(new_file.fileno(), 0, access=mmap.ACCESS_READ) as new:
        old_entries, new_entries = _payloads(base, old), _payloads(target, new)
        index = {}
        for start, end in old_entries.values():
            if end - start >= 256:
                index.setdefault((end - start, hashlib.sha256(old[start:end]).digest()), start)
        cursor = 0
        for name, (start, end) in new_entries.items():
            append(1, cursor, start - cursor)
            size = end - start
            found = index.get((size, hashlib.sha256(new[start:end]).digest())) if size >= 256 else None
            if found is not None:
                append(0, found, size)
            elif name in old_entries and size >= BLOCK:
                # Bounded chunks also reuse unchanged portions of stored, changed entries.
                old_start, old_end = old_entries[name]
                chunks = {(min(BLOCK, old_end - pos), hashlib.sha256(old[pos:min(pos + BLOCK, old_end)]).digest()): pos
                          for pos in range(old_start, old_end, BLOCK)}
                for pos in range(start, end, BLOCK):
                    length = min(BLOCK, end - pos)
                    match = chunks.get((length, hashlib.sha256(new[pos:pos + length]).digest()))
                    append(0 if match is not None else 1, match if match is not None else pos, length)
            else:
                append(1, start, size)
            cursor = end
        append(1, cursor, len(new) - cursor)
        if not 0 < len(commands) <= MAX_COMMANDS:
            raise ValueError('Too many delta commands')
        output.parent.mkdir(parents=True, exist_ok=True)
        try:
            with output.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0, compresslevel=6) as patch:
                patch.write(MAGIC + struct.pack('>QQ', len(old), len(new)))
                patch.write(bytes.fromhex(sha256(base)) + bytes.fromhex(sha256(target)))
                patch.write(struct.pack('>I', len(commands)))
                for kind, offset, length in commands:
                    patch.write(bytes([kind]))
                    if kind == 0:
                        patch.write(struct.pack('>QQ', offset, length))
                    else:
                        patch.write(struct.pack('>Q', length))
                        for pos in range(offset, offset + length, BLOCK):
                            patch.write(new[pos:min(offset + length, pos + BLOCK)])
        except BaseException:
            output.unlink(missing_ok=True)
            raise
    return {'baseSha256': sha256(base), 'baseSize': base.stat().st_size,
            'targetSha256': sha256(target), 'targetSize': target.stat().st_size,
            'patchSha256': sha256(output), 'patchSize': output.stat().st_size,
            'commands': len(commands)}


def apply_patch(base, patch, output):
    """Independent build-time round trip. Android has a separate streaming reader."""
    base, patch, output = map(Path, (base, patch, output))
    if output.resolve() in {base.resolve(), patch.resolve()} or output.exists():
        raise ValueError('Output must be a new file')

    def read_exact(stream, size):
        result = stream.read(size)
        if len(result) != size:
            raise ValueError('Truncated delta')
        return result

    try:
        with gzip.open(patch, 'rb') as source, base.open('rb') as old, output.open('xb') as destination:
            if read_exact(source, 8) != MAGIC:
                raise ValueError('Unknown delta format')
            base_size, target_size = struct.unpack('>QQ', read_exact(source, 16))
            base_hash, target_hash = read_exact(source, 32).hex(), read_exact(source, 32).hex()
            count, = struct.unpack('>I', read_exact(source, 4))
            if not (0 < target_size <= MAX_APK and base_size == base.stat().st_size and
                    0 < count <= MAX_COMMANDS and sha256(base) == base_hash):
                raise ValueError('Delta base or size mismatch')
            written = 0
            for _ in range(count):
                kind = read_exact(source, 1)[0]
                if kind == 0:
                    offset, length = struct.unpack('>QQ', read_exact(source, 16))
                    if offset > base_size or length > base_size - offset:
                        raise ValueError('Copy outside base')
                    old.seek(offset)
                    stream = old
                elif kind == 1:
                    length, = struct.unpack('>Q', read_exact(source, 8))
                    stream = source
                else:
                    raise ValueError('Unknown delta command')
                if length == 0 or length > target_size - written:
                    raise ValueError('Output exceeds declared size')
                remaining = length
                while remaining:
                    block = read_exact(stream, min(BLOCK, remaining))
                    destination.write(block)
                    remaining -= len(block)
                written += length
            if written != target_size or source.read(1):
                raise ValueError('Incorrect output size or trailing commands')
        if sha256(output) != target_hash:
            raise ValueError('Reconstructed APK hash mismatch')
    except BaseException:
        output.unlink(missing_ok=True)
        raise


def prepare_deltas(base_directories, target_directory, version, code):
    """Previous signed APK directories are explicit inputs, never downloaded implicitly."""
    target_directory = Path(target_directory)
    if (target_directory / MANIFEST).exists() or any(target_directory.glob('*.nyadelta')):
        raise ValueError('Delta output directory contains previous materials; use a fresh target directory')
    if not re.fullmatch(VERSION, version) or type(code) is not int or code <= 0:
        raise ValueError('Invalid delta target version')
    if not 0 < len(base_directories) <= 3:
        raise ValueError('Provide one to three previous signed releases')
    entries, seen = [], set()
    for directory in map(Path, base_directories):
        index = directory / 'verified-bases.json'
        verified = json.loads(index.read_text(encoding='utf-8')) if index.is_file() else None
        for base in sorted(directory.glob('Nyanime-Production-*.apk')):
            match = APK_NAME.fullmatch(base.name)
            if not match or tuple(map(int, match[1].split('.'))) >= tuple(map(int, version.split('.'))):
                continue
            abi = match[2]
            target = target_directory / f'Nyanime-Production-{version}-{abi}.apk'
            if not target.is_file():
                continue
            base_hash = sha256(base)
            if verified is not None and verified.get(base.name) != base_hash:
                continue
            if (base_hash, target.name) in seen:
                continue
            seen.add((base_hash, target.name))
            name = f'Nyanime-Delta-{base_hash[:16]}-{version}-{abi}.nyadelta'
            patch = target_directory / name
            record = create_patch(base, target, patch)
            if not worthwhile(record['patchSize'], record['targetSize']):
                patch.unlink()
                continue
            with tempfile.TemporaryDirectory(prefix='nyanime-delta-check-') as temporary:
                reconstructed = Path(temporary) / 'verified.apk'
                apply_patch(base, patch, reconstructed)
                if sha256(reconstructed) != record['targetSha256']:
                    raise ValueError('Delta round trip failed')
            record.pop('commands')
            entries.append(record | {'patch': name, 'target': target.name})
    manifest = {'schema': 1, 'applicationId': 'it.owouwuiwi.nyanime',
                'versionName': version, 'versionCode': code, 'patches': entries}
    if entries:
        (target_directory / MANIFEST).write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    return manifest


def validate_manifest(manifest, version, code, target_hashes):
    if (set(manifest) != {'schema', 'applicationId', 'versionName', 'versionCode', 'patches'} or
            type(manifest['schema']) is not int or manifest['schema'] != 1 or manifest['applicationId'] != 'it.owouwuiwi.nyanime' or
            manifest['versionName'] != version or type(manifest['versionCode']) is not int or manifest['versionCode'] != code or
            not isinstance(manifest['patches'], list) or not 0 < len(manifest['patches']) <= 15):
        raise ValueError('Invalid delta manifest')
    names, bases = set(), set()
    fields = {'baseSha256', 'baseSize', 'targetSha256', 'targetSize', 'patchSha256', 'patchSize', 'patch', 'target'}
    for record in manifest['patches']:
        if set(record) != fields:
            raise ValueError('Unknown delta fields')
        patch_match = PATCH_NAME.fullmatch(record['patch'])
        apk_match = APK_NAME.fullmatch(record['target'])
        if (not patch_match or not apk_match or patch_match[1] != version or apk_match[1] != version or
                patch_match[2] != apk_match[2] or record['target'] not in target_hashes or
                record['targetSha256'] != target_hashes[record['target']] or record['patch'] in names or
                (record['baseSha256'], record['target']) in bases):
            raise ValueError('Ambiguous or mismatched delta target')
        for field in ('baseSha256', 'targetSha256', 'patchSha256'):
            if not isinstance(record[field], str) or not re.fullmatch('[a-f0-9]{64}', record[field]):
                raise ValueError('Invalid delta hash')
        if record['patch'] != f'Nyanime-Delta-{record["baseSha256"][:16]}-{version}-{apk_match[2]}.nyadelta':
            raise ValueError('Patch base identity mismatch')
        if any(type(record[field]) is not int or not 0 < record[field] <= MAX_APK
               for field in ('baseSize', 'targetSize', 'patchSize')) or not worthwhile(record['patchSize'], record['targetSize']):
            raise ValueError('Invalid or inefficient delta size')
        names.add(record['patch'])
        bases.add((record['baseSha256'], record['target']))
    return {record['patch']: record['patchSha256'] for record in manifest['patches']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-directory', action='append', required=True, type=Path)
    parser.add_argument('--target-directory', required=True, type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--code', required=True, type=int)
    arguments = parser.parse_args()
    result = prepare_deltas(arguments.base_directory, arguments.target_directory, arguments.version, arguments.code)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
