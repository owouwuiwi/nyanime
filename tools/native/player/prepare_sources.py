#!/usr/bin/env python3
"""Prepare exact native sources in a dedicated directory; never import old .so files."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request

RECIPE = Path(__file__).resolve().parent
LOCK = RECIPE / "sources.lock.json"


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, stderr=subprocess.STDOUT, text=True).rstrip()


def replace_once(path, old, new):
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise ValueError(f"Unexpected upstream recipe in {path}: {old!r}")
    path.write_text(text.replace(old, new), encoding="utf-8", newline="\n")


def enable_ffmpegkit_protocols(ffmpeg, kit):
    """Preserve the pinned wrapper's SAF bridge, including its LGPL notices."""
    updates, contributions = [], []
    for name, target in (
        ("libavformat_file.c", "libavformat/file.c"),
        ("libavutil_file.h", "libavutil/file.h"),
        ("libavutil_file.c", "libavutil/file.c"),
    ):
        source = kit / "tools/protocols" / name
        destination = ffmpeg / target
        current = destination.read_text(encoding="utf-8")
        if "saf_open_function" in current or "ff_saf_protocol" in current:
            raise ValueError("Custom SAF bridge already exists; use fresh sources")
        payload = source.read_bytes()
        updates.append((destination, current.rstrip() + "\n\n" + payload.decode("utf-8")))
        contributions.append({"source": "ffmpeg-kit/tools/protocols/" + name,
                              "target": target, "sha256": hashlib.sha256(payload).hexdigest()})
    path = ffmpeg / "libavformat/protocols.c"
    current = path.read_text(encoding="utf-8")
    declaration = "extern const URLProtocol ff_file_protocol;"
    if current.count(declaration) != 1 or "ff_saf_protocol" in current:
        raise ValueError("Unexpected FFmpeg protocol registry")
    updates.append((path, current.replace(declaration, declaration + "\nextern const URLProtocol ff_saf_protocol;")))
    for path, content in updates:
        path.write_text(content, encoding="utf-8", newline="\n")
    return contributions


def extract_lua(archive, destination):
    # There is one upstream top directory. Reject links and traversal before extraction.
    with tarfile.open(archive) as bundle:
        members = bundle.getmembers()
        for member in members:
            parts = Path(member.name).parts
            if not parts or parts[0] != "lua-5.2.4" or ".." in parts or member.islnk() or member.issym():
                raise ValueError("Unexpected Lua archive entry")
            relative = Path(*parts[1:])
            target = (destination / relative).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise ValueError("Archive entry outside source directory")
        for member in members:
            relative = Path(*Path(member.name).parts[1:])
            target = destination / relative
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.extractfile(member) as source:
                    target.write_bytes(source.read())
                os.chmod(target, member.mode & 0o777)
            else:
                raise ValueError("Unsupported Lua archive entry")


def fetch_pinned_source(path, source, cache=None):
    upstream = "origin"
    if cache is not None:
        cached = cache.resolve() / source["path"]
        if not cached.resolve().is_relative_to(cache.resolve()):
            raise ValueError("Source cache path escaped its root")
        if run("git", "-C", str(cached), "rev-parse", "HEAD") != source["commit"]:
            raise ValueError("Source cache has a different revision")
        # Fetch committed objects, never working files or generated native output.
        upstream = str(cached)
    run("git", "init", str(path))
    run("git", "-C", str(path), "remote", "add", "origin", source["url"])
    run("git", "-C", str(path), "fetch", "--depth=1", upstream, source["commit"])
    run("git", "-C", str(path), "checkout", "--detach", "FETCH_HEAD")
    actual = run("git", "-C", str(path), "rev-parse", "HEAD")
    if actual != source["commit"]:
        raise ValueError(f"Source revision mismatch: {path}")
    run("git", "-C", str(path), "submodule", "update", "--init", "--recursive", "--depth=1")
    modules = run("git", "-C", str(path), "submodule", "status", "--recursive")
    if any(line.startswith(("-", "+", "U")) for line in modules.splitlines()):
        raise ValueError(f"Unverified submodules: {path}")
    return modules


def prepare(root, cache=None):
    root = root.resolve()
    if root == Path(root.anchor) or root.exists() and any(root.iterdir()):
        raise ValueError("Use a new, empty, dedicated build directory")
    root.mkdir(parents=True, exist_ok=True)
    spec = json.loads(LOCK.read_text(encoding="utf-8"))
    provenance = {
        "lockSha256": hashlib.sha256(LOCK.read_bytes()).hexdigest(), "sources": [],
        "recipeHashes": {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                         for path in sorted(RECIPE.iterdir()) if path.suffix in {".py", ".sh", ".pro"}},
    }
    for source in spec["gitSources"]:
        path = root / source["path"]
        path.parent.mkdir(parents=True, exist_ok=True)
        modules = fetch_pinned_source(path, source, cache)
        provenance["sources"].append({**source, "submodules": modules})
        print(f"Pinned {source['path']}: {source['commit'][:12]}", flush=True)
    downloads = root / "downloads"
    downloads.mkdir()
    for source in spec["archives"]:
        archive = downloads / "lua-5.2.4.tar.gz"
        urllib.request.urlretrieve(source["url"], archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != source["sha256"]:
            raise ValueError("Archive checksum mismatch")
        extract_lua(archive, root / source["path"])
        provenance["sources"].append(source)

    wrapper = root / "mpv-android"
    scripts = wrapper / "buildscripts"
    # Use one pinned toolchain, including its C++ runtime, throughout the closure.
    replace_once(scripts / "include/depinfo.sh", "v_ndk=r27c", "v_ndk=r28c")
    # Keep shared dependencies, full GPL player features and an explicit closure.
    replace_once(scripts / "buildall.sh", "default_library = 'static'", "default_library = 'shared'")
    replace_once(scripts / "buildall.sh", "-Wl,-z,max-page-size=16384", "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
    replace_once(scripts / "scripts/mpv.sh", "-Dlibmpv=true -Dcplayer=false", "-Dgpl=true -Dlibmpv=true -Dcplayer=false")
    replace_once(scripts / "scripts/ffmpeg.sh", "--enable-{gpl,version3}", "--enable-gpl --disable-nonfree --enable-version3 --enable-postproc --disable-autodetect --enable-zlib")
    replace_once(scripts / "scripts/ffmpeg.sh", '--extra-ldflags="-L$prefix_dir/lib"', '--extra-ldflags="-L$prefix_dir/lib $LDFLAGS"')
    replace_once(scripts / "scripts/libass.sh", "--enable-static --disable-shared", "--disable-static --enable-shared")
    replace_once(scripts / "scripts/libplacebo.sh", "-Dvulkan=disabled -Ddemos=false", "-Dvulkan=disabled -Dshaderc=disabled -Dglslang=disabled -Ddemos=false")
    # The pinned Git source needs its configure script generated before the existing build.
    replace_once(scripts / "scripts/unibreak.sh", "mkdir -p $build", "[ -f configure ] || autoreconf -fi\n\nmkdir -p $build")
    # FFmpegKit's wrapper contains C sources only. NDK r27 manages the C++
    # runtime through APP_STL; c++_shared is not an ordinary imported module.
    # Keep the real FFmpeg dependencies, without allowing missing modules.
    replace_once(
        root / "ffmpeg-kit/android/jni/Android.mk",
        "LOCAL_SHARED_LIBRARIES := libavfilter libavformat libavcodec libavutil libswresample libavdevice libswscale c++_shared",
        "LOCAL_SHARED_LIBRARIES := libavfilter libavformat libavcodec libavutil libswresample libavdevice libswscale",
    )
    ffmpeg = scripts / "deps/ffmpeg"
    provenance["ffmpegKitProtocols"] = enable_ffmpegkit_protocols(ffmpeg, root / "ffmpeg-kit")
    provenance["patches"] = []
    for name in ("ffmpeg-7359.patch", "ffmpeg-force-mpegts.patch"):
        patch = RECIPE.parent / name
        run("git", "-C", str(ffmpeg), "apply", "--check", str(patch))
        run("git", "-C", str(ffmpeg), "apply", str(patch))
        provenance["patches"].append({"file": name, "sha256": hashlib.sha256(patch.read_bytes()).hexdigest()})
    # Save exact changes for corresponding-source delivery.
    diffs = root / "patches"
    diffs.mkdir()
    for name, path in (("mpv-android", wrapper), ("ffmpeg", ffmpeg)):
        (diffs / f"{name}.diff").write_text(run("git", "diff", cwd=path) + "\n", encoding="utf-8")
    (root / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    (root / "sources.lock.json").write_bytes(LOCK.read_bytes())


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source-cache", type=Path)
    args = parser.parse_args()
    prepare(args.root, args.source_cache)
