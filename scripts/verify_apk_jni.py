"""Verify JNI-only Zstd names in the actual optimized APK, not a debug classpath."""

import argparse
import struct
import zipfile


def uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise ValueError("Invalid DEX ULEB128")


def declarations(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Expected a standard DEX file")

    def integer(offset):
        return struct.unpack_from("<I", data, offset)[0]

    strings = []
    for i in range(integer(56)):
        _, start = uleb(data, integer(integer(60) + i * 4))
        strings.append(data[start:data.index(0, start)].decode("utf-8", errors="replace"))
    types = [strings[integer(integer(68) + i * 4)] for i in range(integer(64))]
    fields = []
    for i in range(integer(80)):
        owner, kind, name = struct.unpack_from("<HHI", data, integer(84) + i * 8)
        fields.append((types[owner], strings[name], types[kind]))

    classes, declared_fields = set(), set()
    for i in range(integer(96)):
        definition = integer(100) + i * 32
        classes.add(types[integer(definition)])
        offset = integer(definition + 24)
        if not offset:
            continue
        counts = []
        for _ in range(4):
            count, offset = uleb(data, offset)
            counts.append(count)
        for count in counts[:2]:
            index = 0
            for _ in range(count):
                delta, offset = uleb(data, offset)
                _, offset = uleb(data, offset)
                index += delta
                declared_fields.add(fields[index])
    return classes, declared_fields


def verify(path):
    classes, fields = set(), set()
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                dex_classes, dex_fields = declarations(apk.read(name))
                classes.update(dex_classes)
                fields.update(dex_fields)
        if not any(name.startswith("lib/") and name.endswith("/libzstd-kmp.so") for name in apk.namelist()):
            raise ValueError("Zstd native library is missing")
    required = {"Lcom/squareup/zstd/" + name + ";" for name in (
        "ZstdCompressor", "ZstdDecompressor", "JniZstdKt", "JniZstdCompressor", "JniZstdDecompressor",
    )}
    missing = required - classes
    for owner in ("ZstdCompressor", "ZstdDecompressor"):
        for field in ("inputBytesProcessed", "outputBytesProcessed"):
            symbol = ("Lcom/squareup/zstd/" + owner + ";", field, "I")
            if symbol not in fields:
                missing.add(".".join(symbol))
    if missing:
        raise ValueError("JNI symbols removed or renamed: " + ", ".join(sorted(missing)))
    print("APK_ZSTD_JNI_SYMBOLS_OK: " + str(path))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="+")
    for apk_path in parser.parse_args().apk:
        verify(apk_path)
