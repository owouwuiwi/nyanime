"""Restore the reviewed native mirror; a rebuild must be reviewed before publication."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import zipfile

from scripts.build_telegram_native import ABIS, TDLIB, OPENSSL, NDK
from scripts.verify_telegram_native import verify_directory


def restore(root):
    manifest = json.loads((root / 'tools/native/telegram-artifact.json').read_text())
    if (manifest['tdlib'], manifest['openssl'], manifest['ndk']) != (TDLIB, OPENSSL, NDK):
        raise ValueError('Native mirror source identity differs from the build recipe')
    artifact = root / manifest['artifact']
    if not artifact.resolve().is_relative_to((root / 'vendor/native').resolve()):
        raise ValueError('Native mirror leaves vendor directory')
    with artifact.open('rb') as file:
        if hashlib.file_digest(file, 'sha256').hexdigest() != manifest['sha256']:
            raise ValueError('Native mirror hash mismatch')
    expected = {abi + '/' + name for abi in ABIS for name in ('libtdjsonjava.so', 'provenance.json')}
    if set(manifest['files']) != expected:
        raise ValueError('Native mirror file declaration is incomplete')
    destination = root / '.local/telegram-native/jniLibs'
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
        stage = Path(temporary)
        with zipfile.ZipFile(artifact) as archive:
            if set(archive.namelist()) != expected or len(archive.namelist()) != len(expected):
                raise ValueError('Unexpected, duplicate or unsafe native mirror entry')
            for name in expected:
                data = archive.read(name)
                if hashlib.sha256(data).hexdigest() != manifest['files'][name]:
                    raise ValueError('Native mirror entry changed')
                target = stage / name
                target.parent.mkdir(exist_ok=True)
                target.write_bytes(data)
        verify_directory(stage)
        for name in expected:
            target = destination / name
            if target.is_symlink() or not target.resolve().is_relative_to(destination.resolve()):
                raise ValueError('Native destination leaves its directory')
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest() != manifest['files'][name]:
                os.replace(stage / name, target)
    verify_directory(destination)


if __name__ == '__main__':
    restore(Path(__file__).resolve().parents[1])
