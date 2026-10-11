"""Reject optimized APKs missing WorkManager's reflectively created Room constructor.

Older Room consumer rules retain generated database classes but do not explicitly
retain their no-argument constructors. Optional modules can hide this defect by
bringing a newer Room runtime. Check the actual application independently of them.
"""

import argparse
import json
from pathlib import Path
import re
import zipfile

from scripts.verify_viewmodel_abi import store_members


OWNER = "Landroidx/work/impl/WorkDatabase_Impl;"
CONSTRUCTOR = (OWNER, "<init>", "()V")
MINIMUM_VERSIONS = {"androidx.work:work-runtime": (2, 11, 2), "androidx.room:room-runtime": (2, 8, 4)}


def dependencies(report):
    """Do not downgrade the database stack already deployed in NyanimeB."""
    for graph in ("previewCompileClasspath", "previewRuntimeClasspath"):
        values = report.get(graph, [])
        for module, minimum in MINIMUM_VERSIONS.items():
            versions = [value.removeprefix(module + ":") for value in values if value.startswith(module + ":")]
            if len(versions) != 1 or not re.fullmatch(r"\d+\.\d+\.\d+", versions[0]):
                raise ValueError("Missing or ambiguous background database dependency: " + module + " in " + graph)
            if tuple(map(int, versions[0].split("."))) < minimum:
                raise ValueError("Background database dependency downgrade: " + module + " in " + graph)


def verify(path):
    declared = set()
    dex_count = 0
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                _, defined = store_members(apk.read(name), owner=OWNER)
                declared.update(defined)
                dex_count += 1
    if not dex_count:
        raise ValueError("APK contains no DEX files")
    if CONSTRUCTOR not in declared:
        raise ValueError("Missing reflective WorkDatabase_Impl no-argument constructor")
    print("APK_WORK_DATABASE_CONSTRUCTOR_OK: " + str(path))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependencies", type=Path)
    parser.add_argument("apk", nargs="*")
    arguments = parser.parse_args()
    if not arguments.dependencies and not arguments.apk:
        parser.error("Provide a dependency report or an APK")
    if arguments.dependencies:
        dependencies(json.loads(arguments.dependencies.read_text(encoding="utf-8")))
        print("BACKGROUND_DATABASE_DEPENDENCIES_OK")
    for apk_path in arguments.apk:
        verify(apk_path)
