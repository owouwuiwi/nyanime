"""Restore reviewed image engines without rebuilding or contacting the network."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import zipfile

from scripts.build_reader_quality_native import ABIS, NCNN, ARCHIVE_SHA256, NDK
from scripts.verify_reader_quality_native import verify_directory


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def restore(root):
    manifest = json.loads((root/'tools/native/reader-quality/artifact.json').read_text(encoding='utf-8'))
    if (manifest.get('schemaVersion'), manifest.get('ncnn'), manifest.get('ncnnArchiveSha256'),
            manifest.get('ndk')) != (1, NCNN, ARCHIVE_SHA256, NDK):
        raise ValueError('Reader image mirror source identity differs from the build recipe')
    artifact = root/manifest['artifact']
    if not artifact.resolve().is_relative_to((root/'vendor/native').resolve()):
        raise ValueError('Reader image mirror leaves vendor directory')
    if sha(artifact) != manifest['sha256']:
        raise ValueError('Reader image mirror hash mismatch')
    expected = {abi+'/'+name for abi in ABIS for name in ('libnyanime_reader_quality.so', 'provenance.json')}
    if set(manifest['files']) != expected:
        raise ValueError('Reader image mirror file declaration is incomplete')
    destination = root/'.local/reader-quality-native/jniLibs'
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
        stage = Path(temporary)
        with zipfile.ZipFile(artifact) as archive:
            if set(archive.namelist()) != expected or len(archive.namelist()) != len(expected):
                raise ValueError('Unexpected, duplicate or unsafe reader image mirror entry')
            for info in archive.infolist():
                if info.file_size > (20*1024*1024 if info.filename.endswith('.so') else 64*1024):
                    raise ValueError('Oversized reader image mirror entry')
                data = archive.read(info)
                if hashlib.sha256(data).hexdigest() != manifest['files'][info.filename]:
                    raise ValueError('Reader image mirror entry changed')
                target = stage/info.filename
                target.parent.mkdir(exist_ok=True)
                target.write_bytes(data)
        # Validate the complete stage before replacing any existing input.
        verify_directory(stage)
        for name in expected:
            target = destination/name
            if target.is_symlink() or not target.resolve().is_relative_to(destination.resolve()):
                raise ValueError('Reader image destination leaves its directory')
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.is_file() or sha(target) != manifest['files'][name]:
                os.replace(stage/name, target)
    verify_directory(destination)


if __name__ == '__main__':
    restore(Path(__file__).resolve().parents[1])
