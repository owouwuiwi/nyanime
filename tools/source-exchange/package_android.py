"""Stage an explicitly built Linux/NDK artifact; never downloads or silently builds code."""
import argparse
import hashlib
import json
import shutil
import struct
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--repo', type=Path, required=True)
parser.add_argument('--library', type=Path, required=True)
parser.add_argument('--abi', choices=['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'], required=True)
parser.add_argument('--rustc', required=True)
parser.add_argument('--ndk', required=True)
parser.add_argument('--new-generation', action='store_true')
args = parser.parse_args()
engine = args.repo / 'core/source-exchange'
blob = args.library.read_bytes()
assert blob[:4] == b'\x7fELF' and blob[5] == 1
bits = blob[4]
machine = struct.unpack_from('<H', blob, 18)[0]
assert machine == {'arm64-v8a': 183, 'armeabi-v7a': 40, 'x86_64': 62, 'x86': 3}[args.abi]
if bits == 2:
    phoff = struct.unpack_from('<Q', blob, 32)[0]
    entry, count = struct.unpack_from('<HH', blob, 54)
    load_align = [struct.unpack_from('<Q', blob, phoff + i * entry + 48)[0] for i in range(count) if struct.unpack_from('<I', blob, phoff + i * entry)[0] == 1]
else:
    phoff = struct.unpack_from('<I', blob, 28)[0]
    entry, count = struct.unpack_from('<HH', blob, 42)
    load_align = [struct.unpack_from('<I', blob, phoff + i * entry + 28)[0] for i in range(count) if struct.unpack_from('<I', blob, phoff + i * entry)[0] == 1]
assert load_align and all(value >= 16384 for value in load_align), 'ELF must support 16 KiB pages'
digest = lambda file: hashlib.sha256(file.read_bytes()).hexdigest()
inputs = {str(file.relative_to(engine / 'native')).replace('\\', '/'): digest(file) for file in sorted((engine / 'native/src').glob('*.rs'))}
for name in ['Cargo.toml', 'Cargo.lock']:
    inputs[name] = digest(engine / 'native' / name)
manifest = engine / 'native/android/provenance.json'
data = json.loads(manifest.read_text()) if manifest.exists() else {'inputs': inputs, 'binaries': {}}
if data['binaries']:
    if data['inputs'] != inputs and args.new_generation:
        data = {'inputs': inputs, 'binaries': {}}
    assert data['inputs'] == inputs, 'Native source changed; rebuild every already staged ABI'
destination = engine / f'src/main/jniLibs/{args.abi}/libnyanime_source_exchange.so'
destination.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(args.library, destination)
data.update({'schema': 1, 'inputs': inputs, 'rustc': args.rustc, 'ndk': args.ndk,
             'upstream': '6960642a46f549c53d53abef3f2db7327857e488', 'iroh': '1.0.0', 'iroh-blobs': '0.103.1'})
data['binaries'][args.abi] = digest(destination)
manifest.parent.mkdir(parents=True, exist_ok=True)
manifest.write_text(json.dumps(data, indent=2, sort_keys=True) + '\n')
print('VERIFIED_ANDROID_ENGINE', args.abi, len(blob), data['binaries'][args.abi])
