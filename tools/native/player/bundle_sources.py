#!/usr/bin/env python3
"""Create a native-only source package. Never copy app sources or private credentials."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import shutil
import subprocess
import tarfile

RECIPE = Path(__file__).resolve().parent
EXCLUDED = {".git", "__pycache__"}
GENERATED_TREES = {
    ("mpv-android", "buildscripts", "sdk"),
    ("mpv-android", "buildscripts", "prefix"),
    ("mpv-android", "app", "src", "main", "libs"),
    ("mpv-android", "app", "src", "main", "jniLibs"),
    ("ffmpeg-kit", "prebuilt"),
    ("ffmpeg-kit", "android", "libs"),
}


def archive_entry(info):
    path = PurePosixPath(info.name)
    parts = path.parts
    if path.is_absolute() or ".." in parts or "\\" in info.name or not parts:
        raise ValueError(f"Unsafe native source path: {info.name}")
    if any(part in EXCLUDED or part.startswith(("_build", "obj-")) for part in parts):
        return None
    if any(parts[:len(tree)] == tree for tree in GENERATED_TREES):
        return None
    if parts[-1] in {"corresponding-sources.tar.gz", "native-build.log"}:
        return None
    if not (info.isdir() or info.isfile() or info.issym() or info.islnk()):
        raise ValueError(f"Unsupported native source entry: {info.name}")
    if info.issym() or info.islnk():
        target = PurePosixPath(info.linkname)
        if target.is_absolute() or "\\" in info.linkname:
            raise ValueError(f"Unsafe native source link: {info.name}")
        resolved = posixpath.normpath(
            posixpath.join(str(path.parent), info.linkname) if info.issym() else info.linkname
        )
        # Retain upstream relative links, but never a link into host files or an
        # unrelated checkout. No source files are dereferenced while archiving.
        if PurePosixPath(resolved).parts[0] != parts[0]:
            raise ValueError(f"Native source link leaves its tree: {info.name}")
    return info


def verify_build_recipe(root):
    lock = root / "sources.lock.json"
    provenance = json.loads((root / "provenance.json").read_text(encoding="utf-8"))
    digest = hashlib.sha256(lock.read_bytes()).hexdigest()
    if provenance["lockSha256"] != digest or lock.read_bytes() != (RECIPE / lock.name).read_bytes():
        raise ValueError("Source lock changed after the native build")
    for name, expected in provenance["recipeHashes"].items():
        if Path(name).name != name or hashlib.sha256((RECIPE / name).read_bytes()).hexdigest() != expected:
            raise ValueError(f"Recipe changed after the native build: {name}")
    reports = list(root.glob("native-verification-*.json"))
    if not reports:
        raise ValueError("A successful native verification is required first")
    for path in reports:
        report = json.loads(path.read_text(encoding="utf-8"))
        if report["state"] != "native_checks_passed_not_release_approval" or report["provenance"] != provenance:
            raise ValueError("Native verification does not match the source package")
    return json.loads(lock.read_text(encoding="utf-8"))


def source_entries(root):
    # Do not archive arbitrary files added beside the native checkouts.
    names = {"mpv-android", "ffmpeg-kit", "cpu-features", "sources.lock.json",
             "provenance.json", "patches", "verified-config", "rebuild-recipe"}
    names.update(path.name for path in root.glob("ffmpeg-*.patch"))
    names.update(path.name for path in root.glob("native-verification-*.json"))
    return [root / name for name in sorted(names) if (root / name).exists()]


def bundle(root):
    lock = verify_build_recipe(root)
    recipe_target = root / "rebuild-recipe"
    shutil.copytree(RECIPE, recipe_target, dirs_exist_ok=True, ignore=shutil.ignore_patterns("__pycache__"))
    for patch in RECIPE.parent.glob("ffmpeg-*.patch"):
        shutil.copyfile(patch, root / patch.name)
    diffs = root / "patches"
    for index, item in enumerate(lock["gitSources"]):
        path = root / item["path"]
        actual = subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()
        if actual != item["commit"]:
            raise ValueError(f"Source revision changed: {path}")
        diff = subprocess.check_output(["git", "-C", str(path), "diff"], text=True)
        (diffs / f"{index:02d}-{path.name}.diff").write_text(diff, encoding="utf-8")
    archive = root / "corresponding-sources.tar.gz"
    with tarfile.open(archive, "w:gz") as output:
        for entry in source_entries(root):
            output.add(entry, arcname=entry.name, filter=archive_entry)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    (root / "corresponding-sources.sha256").write_text(f"{digest}  {archive.name}\n")
    print(f"Native corresponding sources: {digest}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    args = parser.parse_args()
    bundle(args.root.resolve())
