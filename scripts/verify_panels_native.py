"""Verify the pinned guided-reading model and LiteRT ABI/page alignment in signed APKs."""

import argparse
import glob
import hashlib
import struct
import zipfile
from pathlib import Path


MACHINES = {"arm64-v8a": 183, "armeabi-v7a": 40, "x86": 3, "x86_64": 62}


def verify_elf(data, abi):
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("Invalid little-endian ELF engine")
    if struct.unpack_from("<H", data, 18)[0] != MACHINES[abi]:
        raise ValueError("LiteRT engine ABI mismatch: " + abi)
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
                raise ValueError("LiteRT engine does not support 16 KB pages: " + abi)
            loads += 1
    if not loads:
        raise ValueError("LiteRT engine has no loadable segments")


MODEL = "assets/deeppanel/model.tflite"
MODEL_SHA256 = "93347aad3abd846f8f993955764fe3279ff6d347cec401e6c2393856f5a8562e"


def verify(path):
    with zipfile.ZipFile(path) as apk:
        model = apk.read(MODEL)
        if len(model) != 12_532_336 or hashlib.sha256(model).hexdigest() != MODEL_SHA256:
            raise ValueError("Unexpected guided-reading model")
        if apk.getinfo(MODEL).compress_type != zipfile.ZIP_STORED:
            raise ValueError("Guided-reading model must support asset memory mapping")
        libraries = [
            name for name in apk.namelist()
            if name.startswith("lib/") and name.endswith("/libtensorflowlite_jni.so")
        ]
        abis = {
            name.split("/")[1] for name in apk.namelist()
            if name.startswith("lib/") and name.endswith(".so")
        }
        if not abis or {name.split("/")[1] for name in libraries} != abis:
            raise ValueError("LiteRT JNI is missing for an APK architecture")
        for name in libraries:
            verify_elf(apk.read(name), name.split("/")[1])
    print(f"{Path(path).name}: DeepPanel model and LiteRT ABI/16 KB alignment verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apks", nargs="+")
    args = parser.parse_args()
    files = sorted({path for pattern in args.apks for path in glob.glob(pattern)})
    if not files:
        parser.error("No APKs matched")
    for file in files:
        verify(file)
