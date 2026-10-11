"""Build the pinned, lazy image backends in a native Linux filesystem."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
NCNN = '56775de50990ab7f16627efdcf5529b49541206f'
ARCHIVE_SHA256 = '25d1a616bb3a598535545e217eb6fc5df98c4ba04344b48dbb141bbb108801ef'
NDK = '28.2.13676358'
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--abi', choices=(*ABIS, 'all'), default='all')
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--jobs', type=int, default=2, choices=range(1, 9))
    args = parser.parse_args()
    work = args.work_dir.resolve()
    if os.name != 'posix' or str(work).startswith('/mnt/'):
        parser.error('Use Linux and a Linux build filesystem, not /mnt/c.')
    ndk = args.ndk.resolve()
    if f'Pkg.Revision = {NDK}' not in (ndk/'source.properties').read_text():
        parser.error('Use the pinned NDK r28c.')
    work.mkdir(parents=True, exist_ok=True)
    source = work/'source'
    source.mkdir(exist_ok=True)
    for name in ('CMakeLists.txt', 'reader_quality.cpp'):
        shutil.copyfile(ROOT/'core/reader-quality/src/main/cpp'/name, source/name)
    hashes = {name: digest(source/name) for name in ('CMakeLists.txt', 'reader_quality.cpp')}
    archive = work/'ncnn-20240410.tar.gz'
    if not archive.exists():
        temporary = work/'ncnn-download.part'
        try:
            with urllib.request.urlopen(f'https://codeload.github.com/Tencent/ncnn/tar.gz/{NCNN}', timeout=90) as response:
                with temporary.open('wb') as destination:
                    shutil.copyfileobj(response, destination, length=1024*1024)
            if digest(temporary) != ARCHIVE_SHA256:
                raise ValueError('NCNN source verification failed')
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    if digest(archive) != ARCHIVE_SHA256:
        raise ValueError('NCNN source verification failed')
    llvm = ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
    for abi in ABIS if args.abi == 'all' else [args.abi]:
        build = work/'output'/abi
        subprocess.run([
            'cmake', '-S', str(source), '-B', str(build), '-GNinja',
            '-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),
            '-DANDROID_ABI='+abi, '-DANDROID_PLATFORM=android-26', '-DCMAKE_BUILD_TYPE=Release',
            '-DANDROID_STL=c++_static', '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON',
            '-DNCNN_SOURCE_ARCHIVE='+str(archive),
        ], check=True)
        subprocess.run(['cmake', '--build', str(build), '--parallel', str(args.jobs)], check=True)
        output = ROOT/'.local/reader-quality-native/jniLibs'/abi
        output.mkdir(parents=True, exist_ok=True)
        library = output/'libnyanime_reader_quality.so'
        shutil.copyfile(build/library.name, library)
        subprocess.run([str(llvm/'llvm-strip'), '--strip-unneeded', str(library)], check=True)
        elf = subprocess.check_output([str(llvm/'llvm-readelf'), '-hldW', str(library)], text=True)
        from scripts.verify_panels_native import verify_elf
        verify_elf(library.read_bytes(), abi)
        if 'TEXTREL' in elf or 'libc++_shared.so' in elf or 'libvulkan.so' in elf:
            raise ValueError('Unexpected runtime dependency or text relocation')
        if hashes != {name: digest(source/name) for name in hashes}:
            raise ValueError('Native sources changed during compilation')
        provenance = {
            'schema': 1, 'abi': abi, 'ncnn': NCNN, 'ncnnArchiveSha256': ARCHIVE_SHA256,
            'ndk': NDK, 'sources': hashes, 'librarySha256': digest(library),
            'cpuOnly': True, 'cppRuntime': 'static', 'maxTile': 256,
        }
        (output/'provenance.json').write_text(json.dumps(provenance, indent=2)+'\n', encoding='utf-8')
        (output/'readelf.txt').write_text(elf, encoding='utf-8')
        print('READER_NATIVE_VERIFIED: '+abi+' '+provenance['librarySha256'], flush=True)


if __name__ == '__main__':
    import sys
    sys.path.insert(0, str(ROOT))
    main()
