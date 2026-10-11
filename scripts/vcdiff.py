"""Generate and independently replay RFC 3284 patches with pinned xdelta3."""

import atexit
from functools import lru_cache
import os
from pathlib import Path, PurePosixPath
import platform
import subprocess
import tarfile
import tempfile
from urllib.request import urlopen
import zipfile

from scripts.delta_updates import MAX_APK, sha256, worthwhile

HEADER = bytes.fromhex("d6c3c40000")
MAX_WINDOW = 2 * 1024 * 1024
MAX_ENCODED_WINDOW = MAX_WINDOW + 64 * 1024
XDELTA_VERSION = "3.2.2"
TOOLS = {
    "Windows": ("windows-x86_64.zip", "d48f7651d86b78fdca397057b46601f89131f37219675df736db70af44ec3379"),
    "Linux": ("linux-x86_64.tar.gz", "a8ce7d2c943ae2b3d308942643cb36d4805a1f16775abe1be64fee292d7c2506"),
}


@lru_cache(maxsize=1)
def executable():
    if override := os.environ.get("NYANIME_XDELTA3"):
        return Path(override).resolve(strict=True)
    system = platform.system()
    if system not in TOOLS or platform.machine().lower() not in {"amd64", "x86_64"}:
        raise ValueError("Set NYANIME_XDELTA3 to xdelta3 3.2.2 on this build platform")
    suffix, digest = TOOLS[system]
    name = f"xdelta3-{XDELTA_VERSION}-{suffix}"
    temporary = tempfile.TemporaryDirectory(prefix="nyanime-xdelta3-")
    atexit.register(temporary.cleanup)
    directory = Path(temporary.name)
    archive = directory / name
    url = f"https://github.com/jmacd/xdelta/releases/download/v{XDELTA_VERSION}/{name}"
    with urlopen(url, timeout=45) as download, archive.open("xb") as destination:
        destination.write(download.read(16 * 1024 * 1024))
    if sha256(archive) != digest:
        raise ValueError("Unexpected xdelta3 release archive digest")
    if system == "Windows":
        with zipfile.ZipFile(archive) as package:
            entry, = (member for member in package.namelist() if PurePosixPath(member).name == "xdelta3.exe")
            tool = directory / "xdelta3.exe"
            tool.write_bytes(package.read(entry))
    else:
        with tarfile.open(archive, "r:gz") as package:
            entry, = (member for member in package if member.isfile() and PurePosixPath(member.name).name == "xdelta3")
            tool = directory / "xdelta3"
            with package.extractfile(entry) as source:
                tool.write_bytes(source.read())
            tool.chmod(0o700)
    return tool


def run(*arguments):
    environment = dict(os.environ)
    environment.pop("XDELTA", None)
    subprocess.run([str(executable()), *map(str, arguments)], env=environment, check=True, timeout=600)


def length(stream):
    value = 0
    for _ in range(5):
        octet = stream.read(1)
        if not octet:
            break
        value = (value << 7) | (octet[0] & 0x7f)
        if value > MAX_APK:
            break
        if not octet[0] & 0x80:
            return value
    raise ValueError("Invalid VCDIFF integer")


def verify_profile(patch, base_size, target_size):
    patch_size = patch.stat().st_size
    if not all(0 < size <= MAX_APK for size in (base_size, target_size, patch_size)):
        raise ValueError("APK or patch exceeds the VCDIFF size limits")
    with patch.open("rb") as stream:
        if stream.read(len(HEADER)) != HEADER:
            raise ValueError("Unsupported VCDIFF header")
        written = 0
        while stream.tell() < patch_size:
            start = stream.tell()
            indicator = stream.read(1)
            if indicator == b"\x01":
                source_length, position = length(stream), length(stream)
                if not 0 < source_length <= base_size - position:
                    raise ValueError("Invalid VCDIFF source window")
            elif indicator != b"\x00":
                raise ValueError("Unsupported VCDIFF window")
            delta_length = length(stream)
            end = stream.tell() + delta_length
            target_length = length(stream)
            if not stream.tell() < end <= min(patch_size, start + MAX_ENCODED_WINDOW):
                raise ValueError("Invalid encoded VCDIFF window")
            if not 0 < target_length <= min(MAX_WINDOW, target_size - written):
                raise ValueError("Invalid VCDIFF target window")
            if stream.read(1) != b"\x00":
                raise ValueError("VCDIFF secondary compression is unsupported")
            data_length, instructions_length, addresses_length = (length(stream) for _ in range(3))
            if data_length + instructions_length + addresses_length != end - stream.tell():
                raise ValueError("VCDIFF sections do not fit in the window")
            written += target_length
            stream.seek(end)
        if written != target_size:
            raise ValueError("Incomplete VCDIFF target")


def apply_patch(base, patch, output, target_size):
    if output.exists():
        raise ValueError("VCDIFF output already exists")
    verify_profile(patch, base.stat().st_size, target_size)
    try:
        run("-d", "-D", "-R", "-s", base, patch, output)
        if output.stat().st_size != target_size:
            raise ValueError("Reconstructed VCDIFF length differs from the manifest")
    except BaseException:
        output.unlink(missing_ok=True)
        raise


def create_patch(base, target, output):
    if output.exists():
        raise ValueError("VCDIFF patch already exists")
    with tempfile.TemporaryDirectory(prefix="nyanime-vcdiff-") as directory:
        patch = Path(directory) / "update.vcdiff"
        rebuilt = Path(directory) / "rebuilt.apk"
        run("-e", "-D", "-a", "-A=", "-S", "none", "-n", "-W", 1024 * 1024,
            "-B", 256 * 1024 * 1024, "-s", base, target, patch)
        target_size = target.stat().st_size
        if not worthwhile(patch.stat().st_size, target_size):
            return False
        apply_patch(base, patch, rebuilt, target_size)
        if sha256(rebuilt) != sha256(target):
            raise ValueError("VCDIFF reconstructed APK differs from the signed target")
        with patch.open("rb") as source, output.open("xb") as destination:
            while block := source.read(1024 * 1024):
                destination.write(block)
        return True
