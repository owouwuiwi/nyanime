"""Collect exact notices from the selected Cargo source graph (run beside the Linux build)."""
import argparse
import hashlib
import json
import shutil
import tarfile
import tomllib
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--metadata', type=Path, required=True)
parser.add_argument('--repo', type=Path, required=True)
args = parser.parse_args()
metadata = json.loads(args.metadata.read_text())
lock = tomllib.loads((args.repo / 'core/source-exchange/native/Cargo.lock').read_text())
locked = {(item['name'], item['version']): item.get('checksum') for item in lock['package']}
nodes = {node['id']: node for node in metadata['resolve']['nodes']}
packages = {package['id']: package for package in metadata['packages']}
selected = set()
extra = json.loads((args.repo / 'tools/source-exchange/extra-notices.json').read_text())
def visit(identity):
    if identity in selected:
        return
    selected.add(identity)
    for dep in nodes[identity]['deps']:
        if any(kind['kind'] != 'dev' for kind in dep['dep_kinds']):
            visit(dep['pkg'])
visit(metadata['resolve']['root'])
assets = args.repo / 'core/source-exchange/src/main/assets/source-exchange'
assets.mkdir(parents=True, exist_ok=True)
inventory = []
texts = ['Source exchange native dependencies.\n'
         'Original license expressions and notices are preserved below. Where available,\n'
         'the Apache-2.0 alternative is selected for this distribution; otherwise a\n'
         'permissive alternative or the stated file-level terms apply. This selection\n'
         'does not change the terms of the combined NyanimeB application.\n'
         'attohttpc 0.30.1 is unmodified MPL-2.0 software. Its exact original source is\n'
         'included at source-exchange/attohttpc-0.30.1.crate in this APK and available\n'
         'without charge at https://crates.io/api/v1/crates/attohttpc/0.30.1/download.\n']
for identity in sorted(selected):
    package = packages[identity]
    if identity == metadata['resolve']['root']:
        continue
    root = Path(package['manifest_path']).parent
    assert package['license'], f'Missing license: {identity}'
    found = sorted(file for file in root.rglob('*') if file.is_file() and
                   (file.name.upper().startswith(('LICENSE', 'COPYING', 'COPYRIGHT', 'NOTICE')) or file.name.upper().endswith('.LICENSE')))
    supplemental = [item for item in extra if item['crate'] == f'{package["name"]}-{package["version"]}'] if not found else []
    assert found or supplemental, f'Missing license text or explicit reviewed declaration: {identity}'
    archive = root.parents[2] / 'cache' / root.parent.name / f'{package["name"]}-{package["version"]}.crate'
    checksum = locked[(package['name'], package['version'])]
    assert checksum and hashlib.sha256(archive.read_bytes()).hexdigest() == checksum
    entry = {'name': package['name'], 'version': package['version'], 'license': package['license'],
             'source': package['source'], 'crateSha256': checksum, 'notices': {}}
    texts.append(f'\n{"=" * 72}\n{package["name"]} {package["version"]}\nLicense: {package["license"]}\nSource: https://crates.io/api/v1/crates/{package["name"]}/{package["version"]}/download\n')
    with tarfile.open(archive, 'r:gz') as original:
        for file in found:
            relative = str(file.relative_to(root))
            blob = file.read_bytes()
            digest = hashlib.sha256(blob).hexdigest()
            expected = original.extractfile(f'{root.name}/{relative}').read()
            assert expected == blob, f'Changed original notice: {identity}/{relative}'
            entry['notices'][relative] = digest
            texts.append(f'\n--- {relative} ---\n' + blob.decode('utf-8') + '\n')
    for item in supplemental:
        file = args.repo / 'tools/source-exchange/extra-notices' / item['crate'] / item['name']
        blob = file.read_bytes()
        assert hashlib.sha256(blob).hexdigest() == item['sha256']
        entry['notices'][item['name']] = item['sha256']
        entry.setdefault('supplemental', []).append(item)
        texts.append(f'\n--- {item["name"]} ---\nSource: {item["url"]}\nBasis: {item.get("basis", "original repository text at the crate source revision")}\n' + blob.decode('utf-8') + '\n')
    inventory.append(entry)
    if package['name'] == 'attohttpc':
        shutil.copyfile(archive, assets / archive.name)
(assets / 'THIRD-PARTY-LICENSES.txt').write_text('\n'.join(texts), encoding='utf-8')
(assets / 'inventory.json').write_text(json.dumps(inventory, indent=2, sort_keys=True) + '\n')
native = args.repo / 'core/source-exchange/native'
shutil.copyfile(native / 'LICENSE', assets / 'Lightning-LICENSE.txt')
shutil.copyfile(native / 'NOTICE', assets / 'NOTICE.txt')
with (assets / 'NOTICE.txt').open('a', encoding='utf-8') as notice:
    notice.write('\nNyanimeB source-exchange adaptation (2026): bounded Lightning fd2/blob ticket compatibility,\n'
                 'ephemeral scoped providers, capped verified streaming, on-demand Android JNI worker,\n'
                 'native source selection/review UI. The Tauri application, chat and updater are not included.\n'
                 'iroh-blobs 0.103.1 get_blob_ranges_impl informed the bounded Bao streaming loop;\n'
                 'its original MIT OR Apache-2.0 notices are retained in THIRD-PARTY-LICENSES.txt.\n')
print('EXACT_CARGO_NOTICES', len(inventory), 'unmodified MPL source included')
catalog = args.repo / 'app/src/main/assets/licenses/catalog.json'
entries = json.loads(catalog.read_text())
additions = [
    {'name': 'Lightning P2P — source exchange ticket compatibility',
     'website': 'https://github.com/Kerim-Sabic/lightning-p2p/tree/6960642a46f549c53d53abef3f2db7327857e488',
     'licenseName': 'Apache-2.0', 'asset': 'source-exchange/Lightning-LICENSE.txt', 'notice': 'source-exchange/NOTICE.txt'},
    {'name': 'Source exchange — iroh and native dependency notices',
     'website': 'https://github.com/n0-computer/iroh/tree/v1.0.0',
     'licenseName': 'MIT / Apache-2.0 and individual component terms',
     'asset': 'source-exchange/THIRD-PARTY-LICENSES.txt', 'notice': 'source-exchange/NOTICE.txt'},
]
for addition in additions:
    previous = next((index for index, entry in enumerate(entries) if entry['asset'] == addition['asset']), None)
    if previous is None:
        entries.append(addition)
    else:
        entries[previous] = addition
catalog.write_text(json.dumps(entries, indent=2, ensure_ascii=False) + '\n')
