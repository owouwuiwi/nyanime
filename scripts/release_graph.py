"""Bounded OTA release closure, shared by preparation and the public verifier.

No executable payload, re-signing or mutable latest URLs. A complete checkpoint and
every required patch remain immutable public release assets. See docs/delta-updates.md.
"""
import copy
import heapq
import itertools
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request
import zipfile

from scripts.delta_updates import MAX_APK, MANIFEST as DELTA_MANIFEST, apply_patch, sha256

FILE_NAME = 'Nyanime-Release-v2.json'
MAX_BYTES = 512 * 1024
MAX_PACKAGES, MAX_PATCHES, MAX_STEPS = 160, 320, 8
ABIS = {'universal', 'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}
VERSION = r'[0-9]{1,9}\.[0-9]{1,9}\.[0-9]{1,9}\.[0-9]{1,9}'
HASH = r'[a-f0-9]{64}'


def version_parts(value):
    if not isinstance(value, str) or not re.fullmatch(VERSION, value):
        raise ValueError('Invalid release version')
    return tuple(map(int, value.split('.')))


def asset_url(repository, version, name):
    return f'https://github.com/{repository}/releases/download/v{version}/{name}'


def apk_name(node):
    return f'Nyanime-Production-{node["versionName"]}-{node["abi"]}.apk'


def patch_name(base, target):
    return f'Nyanime-Delta-{base["sha256"][:16]}-{target["versionName"]}-{target["abi"]}.nyadelta'


def route(graph, supported_abis, available=(), excluded_patches=(), excluded_bases=()):
    nodes = {node['sha256']: node for node in graph['packages']}
    compatible = set(supported_abis) | {'universal'}
    targets = {digest for digest in graph['targets'] if nodes[digest]['abi'] in compatible}
    queue, distances, counter = [], {}, itertools.count()
    for node in nodes.values():
        if (node['abi'] in compatible and node['sha256'] not in excluded_bases and
                (node['sha256'] in available or node.get('url'))):
            cost = 0 if node['sha256'] in available else node['size']
            heapq.heappush(queue, (cost, 0, next(counter), node, []))
    edges = {}
    for edge in graph['patches']:
        if edge['sha256'] not in excluded_patches:
            edges.setdefault(edge['from'], []).append(edge)
    while queue:
        cost, hops, _, base, steps = heapq.heappop(queue)
        digest = steps[-1]['to'] if steps else base['sha256']
        if distances.get((digest, hops), 1 << 63) <= cost:
            continue
        distances[digest, hops] = cost
        if digest in targets:
            return {'base': base, 'steps': steps, 'downloadBytes': cost}
        if hops < MAX_STEPS:
            for edge in edges.get(digest, []):
                heapq.heappush(queue, (cost + edge['size'], hops + 1, next(counter), base, steps + [edge]))
    return None


def validate(graph, repository, application_id, version):
    if (not isinstance(graph, dict) or set(graph) != {'schema', 'applicationId', 'versionName', 'versionCode',
                                                    'targets', 'packages', 'patches'} or
            type(graph['schema']) is not int or graph['schema'] != 2 or
            graph['applicationId'] != application_id or graph['versionName'] != version or
            type(graph['versionCode']) is not int or graph['versionCode'] <= 0):
        raise ValueError('Invalid OTA graph identity')
    final_version = version_parts(version)
    if (not isinstance(graph['packages'], list) or not 1 <= len(graph['packages']) <= MAX_PACKAGES or
            not isinstance(graph['patches'], list) or len(graph['patches']) > MAX_PATCHES or
            not isinstance(graph['targets'], list) or not 1 <= len(graph['targets']) <= 5):
        raise ValueError('OTA graph exceeds bounds; publish a full checkpoint')
    nodes, codes, versions = {}, {}, {}
    for node in graph['packages']:
        fields = {'sha256', 'size', 'versionName', 'versionCode', 'abi'}
        if not isinstance(node, dict) or set(node) not in (fields, fields | {'url'}):
            raise ValueError('Invalid APK declaration')
        if (not isinstance(node['sha256'], str) or not re.fullmatch(HASH, node['sha256']) or
                node['sha256'] in nodes or type(node['size']) is not int or not 0 < node['size'] <= MAX_APK or
                type(node['versionCode']) is not int or not 0 < node['versionCode'] <= graph['versionCode'] or
                node['abi'] not in ABIS or version_parts(node['versionName']) > final_version):
            raise ValueError('Invalid APK identity')
        if node['versionCode'] == graph['versionCode'] and node['versionName'] != version:
            raise ValueError('Conflicting target version')
        if (codes.setdefault(node['versionCode'], node['versionName']) != node['versionName'] or
                versions.setdefault(node['versionName'], node['versionCode']) != node['versionCode']):
            raise ValueError('Conflicting Android versions')
        if node.get('url') is not None and node['url'] != asset_url(repository, node['versionName'], apk_name(node)):
            raise ValueError('APK URL is not an immutable same-publisher asset')
        nodes[node['sha256']] = node
    targets = graph['targets']
    if len(set(targets)) != len(targets) or any(digest not in nodes for digest in targets):
        raise ValueError('Unknown or repeated target')
    selected = [nodes[digest] for digest in targets]
    if (len({node['abi'] for node in selected}) != len(selected) or
            any(node['versionName'] != version or node['versionCode'] != graph['versionCode'] for node in selected)):
        raise ValueError('Conflicting target architectures')
    seen_hashes, seen_edges = set(), set()
    for edge in graph['patches']:
        if (not isinstance(edge, dict) or set(edge) != {'from', 'to', 'sha256', 'size', 'url'} or
                edge['from'] not in nodes or edge['to'] not in nodes or
                not isinstance(edge['sha256'], str) or not re.fullmatch(HASH, edge['sha256']) or
                edge['sha256'] in seen_hashes or (edge['from'], edge['to']) in seen_edges or
                type(edge['size']) is not int or not 0 < edge['size'] <= MAX_APK):
            raise ValueError('Invalid patch identity')
        base, target = nodes[edge['from']], nodes[edge['to']]
        if (base['abi'] != target['abi'] or base['versionCode'] >= target['versionCode'] or
                version_parts(base['versionName']) >= version_parts(target['versionName']) or
                edge['url'] != asset_url(repository, target['versionName'], patch_name(base, target))):
            raise ValueError('Cyclic, incompatible or untrusted patch')
        seen_hashes.add(edge['sha256'])
        seen_edges.add((edge['from'], edge['to']))
    for target in selected:
        if route(graph | {'targets': [target['sha256']]}, [target['abi']]) is None:
            raise ValueError('Target has no retained complete checkpoint within the hop limit')
    return graph


def load(path, repository, application_id, version):
    data = Path(path).read_bytes()
    if len(data) > MAX_BYTES:
        raise ValueError('Release declaration exceeds byte limit')
    depth, quoted, escaped = 0, False, False
    for char in data.decode('utf-8'):
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in '[{':
            depth += 1
            if depth > 5:
                raise ValueError('Release declaration exceeds nesting limit')
        elif char in ']}':
            depth -= 1
    return validate(json.loads(data), repository, application_id, version)


def prepare(directory, version, code, application_id, repository, previous=None, patch_only=False):
    directory = Path(directory)
    graph = {'schema': 2, 'applicationId': application_id, 'versionName': version, 'versionCode': code,
             'targets': [], 'packages': [], 'patches': []}
    if previous is not None:
        previous = validate(copy.deepcopy(previous), repository, application_id, previous['versionName'])
        if previous['versionCode'] >= code:
            raise ValueError('Previous closure must be older')
        graph['packages'] = previous['packages']
        graph['patches'] = previous['patches']
    for abi in sorted(ABIS):
        node = {'versionName': version, 'versionCode': code, 'abi': abi}
        file = directory / apk_name(node)
        node.update(sha256=sha256(file), size=file.stat().st_size)
        if not patch_only:
            node['url'] = asset_url(repository, version, file.name)
        graph['targets'].append(node['sha256'])
        graph['packages'].append(node)
    nodes = {node['sha256']: node for node in graph['packages']}
    if (directory / DELTA_MANIFEST).is_file():
        manifest = json.loads((directory / DELTA_MANIFEST).read_text(encoding='utf-8'))
        for entry in manifest['patches']:
            if entry['baseSha256'] not in nodes:
                # Legacy one-hop material can coexist, but cannot invent unverified old version metadata.
                continue
            base, target = nodes[entry['baseSha256']], nodes[entry['targetSha256']]
            if base['size'] != entry['baseSize'] or target['size'] != entry['targetSize']:
                raise ValueError('Delta sizes differ from closure')
            graph['patches'].append({'from': base['sha256'], 'to': target['sha256'],
                'size': entry['patchSize'], 'sha256': entry['patchSha256'],
                'url': asset_url(repository, version, entry['patch'])})
    validate(graph, repository, application_id, version)
    (directory / FILE_NAME).write_text(json.dumps(graph, indent=2) + '\n', encoding='utf-8', newline='\n')
    return graph


def fetch(url, destination, size):
    class Redirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, response, code, message, headers, target):
            uri = urllib.parse.urlsplit(target)
            if (uri.scheme != 'https' or uri.username or uri.port or
                    uri.hostname not in {'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}):
                raise ValueError('Unexpected OTA asset redirect')
            return super().redirect_request(request, response, code, message, headers, target)
    opener = urllib.request.build_opener(Redirect())
    with opener.open(url, timeout=180) as response, Path(destination).open('xb') as output:
        total = 0
        while block := response.read(64 * 1024):
            total += len(block)
            if total > size:
                raise ValueError('OTA asset exceeds pinned length')
            output.write(block)
        if total != size:
            raise ValueError('Incomplete OTA asset')


def reconstruct(graph, target_hash, destination, materials, download=fetch, verify=lambda file, node: None):
    """Publisher replays the actual cold-client route, never trusting a local target APK as proof."""
    nodes = {node['sha256']: node for node in graph['packages']}
    target = nodes[target_hash]
    plan = route(graph | {'targets': [target_hash]}, [target['abi']])
    if plan is None:
        raise ValueError('No cold-client path')
    with tempfile.TemporaryDirectory(prefix='nyanime-ota-replay-') as temp:
        temp = Path(temp)
        base = plan['base']
        current = temp / base['sha256']
        download(base['url'], current, base['size'])
        def check(file, node):
            if file.stat().st_size != node['size'] or sha256(file) != node['sha256']:
                raise ValueError('APK replay hash mismatch')
            verify(file, node)
        check(current, base)
        for edge in plan['steps']:
            patch = temp / 'patch'
            local = Path(materials) / edge['url'].rsplit('/', 1)[-1]
            if local.is_file():
                shutil.copyfile(local, patch)
            else:
                download(edge['url'], patch, edge['size'])
            if patch.stat().st_size != edge['size'] or sha256(patch) != edge['sha256']:
                raise ValueError('Patch replay hash mismatch')
            following = temp / edge['to']
            apply_patch(current, patch, following)
            check(following, nodes[edge['to']])
            current.unlink()
            patch.unlink()
            current = following
        shutil.copyfile(current, destination)
    return plan


def verify_android(file, node, package_name, certificate):
    """Fail closed if the publisher has no Android signature/manifest verifier available."""
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    aapt = 'aapt.exe' if os.name == 'nt' else 'aapt'
    tools = sorted((Path(sdk) / 'build-tools').glob('*'),
                   key=lambda folder: tuple(map(int, re.findall(r'\d+', folder.name)))) if sdk else []
    tools = [folder for folder in tools if (folder / 'lib/apksigner.jar').is_file() and (folder / aapt).is_file()]
    if not tools:
        raise ValueError('Android build-tools are required to verify reconstructed APK signatures')
    tools = tools[-1]
    java = str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if os.environ.get('JAVA_HOME') else 'java'
    result = subprocess.check_output([java, '-jar', str(tools / 'lib/apksigner.jar'), 'verify', '--verbose', '--print-certs', str(file)], text=True)
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', result)
    if len(certificates) != 1 or certificates[0].upper() != certificate.upper():
        raise ValueError('Reconstructed APK signer differs from production identity')
    result = subprocess.check_output([str(tools / aapt), 'dump', 'badging', str(file)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", result)
    if not package or package.groups() != (package_name, str(node['versionCode']), node['versionName']):
        raise ValueError('Reconstructed APK Android identity differs from release declaration')
    native = re.search(r'native-code: (.+)', result)
    actual = set(re.findall(r"'([^']+)'", native[1])) if native else set()
    expected = ABIS - {'universal'} if node['abi'] == 'universal' else {node['abi']}
    if actual != expected:
        raise ValueError('Reconstructed APK ABI differs from release declaration')


def verify_capability(file):
    with zipfile.ZipFile(file) as apk:
        if json.loads(apk.read('assets/ota-capabilities.json')) != {'schema': 1, 'releaseGraph': 2, 'maxSteps': MAX_STEPS}:
            raise ValueError('Complete checkpoint does not contain the capable OTA client')


def publication_graph(directory, metadata, repository):
    """Bind closure targets and all current patch assets to the immutable reviewed metadata."""
    hashes = metadata.get('graphSha256', {})
    if set(hashes) != {FILE_NAME} or not re.fullmatch(HASH, hashes[FILE_NAME]):
        raise ValueError('Missing pinned release graph')
    file = Path(directory) / FILE_NAME
    if sha256(file) != hashes[FILE_NAME]:
        raise ValueError('Release graph hash mismatch')
    graph = load(file, repository, metadata['applicationId'], metadata['versionName'])
    if graph['versionCode'] != metadata['versionCode']:
        raise ValueError('Release graph version code mismatch')
    nodes = {node['sha256']: node for node in graph['packages']}
    targets = [nodes[digest] for digest in graph['targets']]
    if ({node['abi'] for node in targets} != ABIS or
            {apk_name(node): node['sha256'] for node in targets} != metadata['sha256']):
        raise ValueError('Release graph target set differs from reviewed APKs')
    for node in targets:
        expected = asset_url(repository, node['versionName'], apk_name(node)) if metadata.get('delivery', 'full') == 'full' else None
        if node.get('url') != expected:
            raise ValueError('Release graph promises an APK that is not uploaded')
    current_edges = {edge['url'].rsplit('/', 1)[-1]: edge['sha256'] for edge in graph['patches']
                     if nodes[edge['to']]['versionName'] == metadata['versionName']}
    delta = metadata.get('deltaSha256', {})
    if any(delta.get(name) != digest for name, digest in current_edges.items()):
        raise ValueError('Release graph patch assets differ from reviewed materials')
    return graph


def check_transition(path):
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    if (set(data) != {'schema', 'versionName', 'versionCode', 'verifiedUpdate'} or data['schema'] != 1 or
            data['verifiedUpdate'] is not True or type(data['versionCode']) is not int or data['versionCode'] < 284):
        raise ValueError('Publish and verify the full OTA transition before enabling patch-only releases')
    version_parts(data['versionName'])
    return data
