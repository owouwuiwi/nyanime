"""Check image worker provenance, ABI, 16 KiB pages and the exact APK closure."""
import argparse
import glob
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.build_reader_quality_native import NCNN, ARCHIVE_SHA256, NDK, ABIS, digest
from scripts.verify_panels_native import verify_elf


def verify_directory(directory, abis=ABIS):
    expected = {}
    for abi in abis:
        path = directory/abi/'libnyanime_reader_quality.so'
        metadata = json.loads((directory/abi/'provenance.json').read_text(encoding='utf-8'))
        if metadata.get('schema') != 1 or metadata.get('abi') != abi or metadata.get('ncnn') != NCNN:
            raise ValueError('Unknown reader image engine provenance: '+abi)
        if metadata.get('ncnnArchiveSha256') != ARCHIVE_SHA256 or metadata.get('ndk') != NDK:
            raise ValueError('Unverified native toolchain or source: '+abi)
        if metadata.get('cpuOnly') is not True or metadata.get('cppRuntime') != 'static':
            raise ValueError('Unexpected reader engine configuration: '+abi)
        for name in ('CMakeLists.txt', 'reader_quality.cpp'):
            if metadata['sources'].get(name) != digest(ROOT/'core/reader-quality/src/main/cpp'/name):
                raise ValueError('Reader native engine is stale: '+name)
        if digest(path) != metadata.get('librarySha256'):
            raise ValueError('Reader native engine hash differs: '+abi)
        verify_elf(path.read_bytes(), abi)
        expected['lib/'+abi+'/'+path.name] = digest(path)
    return expected


def verify_apk(path, expected):
    with zipfile.ZipFile(path) as apk:
        abis = {n.split('/')[1] for n in apk.namelist() if n.startswith('lib/') and n.endswith('.so')}
        if not abis:
            raise ValueError('Empty APK native inventory')
        names = {n for n in apk.namelist() if n.endswith('/libnyanime_reader_quality.so')}
        if names != {n for n in expected if n.split('/')[1] in abis}:
            raise ValueError('Reader image engine missing from an APK architecture')
        for name in names:
            data = apk.read(name)
            if hashlib.sha256(data).hexdigest() != expected[name]:
                raise ValueError('Packaged reader engine differs: '+name)
            verify_elf(data, name.split('/')[1])
        for name in ('NCNN.txt', 'Ruapu.txt', 'RealCUGAN-Model.txt', 'RealCUGAN-Converter.txt',
                     'RealESRGAN-Model.txt', 'RealESRGAN-Converter.txt'):
            packaged = apk.read('assets/licenses/reader-quality/'+name)
            if packaged != (ROOT/'core/reader-quality/licenses'/name).read_bytes():
                raise ValueError('Missing or modified reader component license: '+name)
    print(Path(path).name+': verified reader image engines, source identity, notices and 16 KiB alignment')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=ROOT/'.local/reader-quality-native/jniLibs')
    parser.add_argument('--apk', nargs='*')
    args = parser.parse_args()
    expected = verify_directory(args.directory)
    if args.apk:
        paths = sorted({path for pattern in args.apk for path in glob.glob(pattern)})
        if not paths:
            parser.error('No APKs matched')
        for path in paths:
            verify_apk(path, expected)
    else:
        print('READER_NATIVE_INPUTS_OK: '+', '.join(ABIS))
