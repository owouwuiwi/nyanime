"""Check pinned TDLib inputs, ELF ABIs/page alignment and optimized APK JNI names."""
import argparse
import json
from pathlib import Path
import struct
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.build_telegram_native import ABIS, TDLIB, OPENSSL, NDK
from scripts.verify_apk_jni import declarations

MACHINES = {"arm64-v8a": 183, "armeabi-v7a": 40, "x86": 3, "x86_64": 62}


def verify_elf(data, abi):
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("Invalid little-endian ELF engine")
    if struct.unpack_from("<H", data, 18)[0] != MACHINES[abi]:
        raise ValueError("Telegram engine ABI mismatch: " + abi)
    if data[4] == 2:
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        fmt = "<IIQQQQQQ"
        indices = (2, 3, 7)
    elif data[4] == 1:
        offset = struct.unpack_from("<I", data, 28)[0]
        size, count = struct.unpack_from("<HH", data, 42)
        fmt = "<IIIIIIII"
        indices = (1, 2, 7)
    else:
        raise ValueError("Unknown ELF class")
    loads = 0
    for n in range(count):
        segment = struct.unpack_from(fmt, data, offset + n * size)
        if segment[0] == 1:
            file_offset, virtual, alignment = (segment[index] for index in indices)
            if alignment < 16384 or (file_offset - virtual) % 16384:
                raise ValueError("Telegram engine does not support 16 KB pages: " + abi)
            loads += 1
    if not loads or b"JNI_OnLoad" not in data or b"org/drinkless/tdlib/JsonClient" not in data:
        raise ValueError("Missing official TDLib JSON/JNI entry points")


def verify_directory(directory):
    root = Path(directory)
    for abi in ABIS:
        metadata = json.loads((root / abi / "provenance.json").read_text())
        if metadata != {"tdlib": TDLIB, "openssl": OPENSSL, "ndk": NDK, "abi": abi}:
            raise ValueError("Unexpected native provenance for " + abi)
        verify_elf((root / abi / "libtdjsonjava.so").read_bytes(), abi)
    print("TELEGRAM_NATIVE_INPUTS_OK")


def verify_apk(path):
    classes = set()
    with zipfile.ZipFile(path) as apk:
        abis = {name.split("/")[1] for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")}
        if not abis or abis - set(ABIS):
            raise ValueError("Unexpected APK native ABIs")
        for abi in abis:
            verify_elf(apk.read("lib/" + abi + "/libtdjsonjava.so"), abi)
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                classes.update(declarations(apk.read(name))[0])
        if "Lorg/drinkless/tdlib/JsonClient;" not in classes or "Lorg/drinkless/tdlib/JsonClient$LogMessageHandler;" not in classes:
            raise ValueError("Official Telegram JNI classes removed or renamed")
        for license_name in ("TDLib-LICENSE.txt", "OpenSSL-LICENSE.txt", "telegram-NOTICE.txt"):
            if not apk.read("assets/licenses/" + license_name):
                raise ValueError("Missing bundled Telegram license")
    print("APK_TELEGRAM_NATIVE_OK: " + str(path))


if __name__ == "__main__":
    # Direct invocation also works from the project root, without installation.
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory")
    parser.add_argument("--apk", nargs="*")
    arguments = parser.parse_args()
    if arguments.directory:
        verify_directory(arguments.directory)
    for path in arguments.apk or []:
        verify_apk(path)
    if not arguments.directory and not arguments.apk:
        parser.error("Specify --directory or --apk")
