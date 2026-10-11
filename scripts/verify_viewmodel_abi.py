"""Reject APKs referencing ViewModelStore members absent from their packaged runtime.

Check the optimized DEX, not only Gradle's compile graph. Lifecycle changed the
restricted store key from String to Object; a mixed compile/runtime classpath can
otherwise build successfully and crash as soon as a playback session starts.
"""

import argparse
import struct
import zipfile

from scripts.verify_apk_jni import uleb


OWNER = "Landroidx/lifecycle/ViewModelStore;"


def store_members(data, owner=OWNER):
    if not data.startswith(b"dex\n"):
        raise ValueError("Expected a standard DEX file")

    def integer(offset):
        return struct.unpack_from("<I", data, offset)[0]

    strings = []
    for index in range(integer(56)):
        _, start = uleb(data, integer(integer(60) + index * 4))
        strings.append(data[start:data.index(0, start)].decode("utf-8", errors="replace"))
    types = [strings[integer(integer(68) + index * 4)] for index in range(integer(64))]
    prototypes = []
    for index in range(integer(72)):
        offset = integer(76) + index * 12
        arguments = integer(offset + 8)
        parameters = [] if not arguments else [
            types[struct.unpack_from("<H", data, arguments + 4 + item * 2)[0]]
            for item in range(integer(arguments))
        ]
        prototypes.append("(" + "".join(parameters) + ")" + types[integer(offset + 4)])
    methods = []
    for index in range(integer(88)):
        method_owner, prototype, name = struct.unpack_from("<HHI", data, integer(92) + index * 8)
        methods.append((types[method_owner], strings[name], prototypes[prototype]))

    declared = set()
    for index in range(integer(96)):
        definition = integer(100) + index * 32
        if types[integer(definition)] != owner:
            continue
        offset = integer(definition + 24)
        if not offset:
            continue
        counts = []
        for _ in range(4):
            count, offset = uleb(data, offset)
            counts.append(count)
        for _ in range(sum(counts[:2])):
            _, offset = uleb(data, offset)
            _, offset = uleb(data, offset)
        for count in counts[2:]:
            method_index = 0
            for _ in range(count):
                delta, offset = uleb(data, offset)
                method_index += delta
                _, offset = uleb(data, offset)
                _, offset = uleb(data, offset)
                declared.add(methods[method_index])
    return {method for method in methods if method[0] == owner}, declared


def verify(path):
    references, declarations = set(), set()
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                used, defined = store_members(apk.read(name))
                references.update(used)
                declarations.update(defined)
    if not declarations:
        raise ValueError("ViewModelStore runtime is missing")
    # These inherited Object methods do not need a store-specific declaration.
    inherited = {("toString", "()Ljava/lang/String;"), ("hashCode", "()I"), ("equals", "(Ljava/lang/Object;)Z")}
    missing = {member for member in references - declarations if member[1:] not in inherited}
    if missing:
        raise ValueError("Incompatible Lifecycle method references: " + repr(sorted(missing)))
    print("APK_VIEWMODEL_ABI_OK: " + str(path))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="+")
    for apk_path in parser.parse_args().apk:
        verify(apk_path)
