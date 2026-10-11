"""Reject unresolved ToggleButtonDefaults calls in the actual optimized APK.

Material3 alpha27 removed methods still present in alpha26. Modules compiled
against different versions can pass compilation and R8, then crash in the video
information panel. The graph check and this artifact check cover different stages.
"""

import argparse
import zipfile

from scripts.verify_viewmodel_abi import store_members


OWNER = "Landroidx/compose/material3/ToggleButtonDefaults;"
# ToggleButtonDefaults is a final Kotlin object extending only java.lang.Object.
OBJECT_METHODS = {
    ("toString", "()Ljava/lang/String;"),
    ("hashCode", "()I"),
    ("equals", "(Ljava/lang/Object;)Z"),
    ("getClass", "()Ljava/lang/Class;"),
    ("notify", "()V"),
    ("notifyAll", "()V"),
    ("wait", "()V"),
    ("wait", "(J)V"),
    ("wait", "(JI)V"),
}


def verify(path):
    references, declarations = set(), set()
    dex_count = 0
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                used, defined = store_members(apk.read(name), owner=OWNER)
                references.update(used)
                declarations.update(defined)
                dex_count += 1
    if not dex_count:
        raise ValueError("APK contains no DEX files")
    # R8 may inline the whole object; no references then means no ABI to check.
    missing = {member for member in references - declarations if member[1:] not in OBJECT_METHODS}
    if missing:
        raise ValueError("Incompatible Material3 method references: " + repr(sorted(missing)))
    print("APK_COMPOSE_ABI_OK: " + str(path))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="+")
    for apk_path in parser.parse_args().apk:
        verify(apk_path)
