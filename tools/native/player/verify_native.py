#!/usr/bin/env python3
"""Check actual configuration, ELF linkage and JNI before considering new AARs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
from elf_page_policy import relro_page_layout

SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
    "libEGL.so", "libGLESv2.so", "libGLESv3.so", "libjnigraphics.so", "libOpenSLES.so",
    "libaaudio.so", "libnativewindow.so",
    # Public Android NDK MediaCodec API, supplied by Android since API 21.
    "libmediandk.so",
}
REQUIRED_LIBS = {
    "libplayer.so", "libmpv.so", "libavcodec.so", "libavformat.so", "libavutil.so",
    "libavfilter.so", "libavdevice.so", "libswresample.so", "libswscale.so",
    "libffmpegkit.so", "libffmpegkit_abidetect.so", "libfribidi.so", "libplacebo.so",
    "libass.so", "libc++_shared.so", "libpostproc.so",
}
ABI_SUFFIX = {"arm64-v8a": "-arm64", "armeabi-v7a": "", "x86": "-x86", "x86_64": "-x64"}
ELF_ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}
MPV_NATIVE_METHODS = {
    "create", "init", "destroy", "attachSurface", "detachSurface", "command",
    "setOptionString", "grabThumbnail", "getPropertyInt", "setPropertyInt",
    "getPropertyDouble", "setPropertyDouble", "getPropertyBoolean",
    "setPropertyBoolean", "getPropertyString", "setPropertyString", "observeProperty",
}
MPV_CLIENT_SYMBOLS = {
    "mpv_create", "mpv_initialize", "mpv_request_log_messages", "mpv_wakeup",
    "mpv_terminate_destroy", "mpv_command", "mpv_command_node", "mpv_set_option",
    "mpv_set_option_string", "mpv_get_property", "mpv_set_property", "mpv_free",
    "mpv_observe_property", "mpv_wait_event", "mpv_error_string", "mpv_free_node_contents",
    "mpv_render_context_create", "mpv_render_context_update",
}


def required_symbols(library):
    if library == "libplayer.so":
        # This wrapper binds by exported Java_* names, not RegisterNatives/JNI_OnLoad.
        return {"Java_is_xyz_mpv_MPVLib_" + method for method in MPV_NATIVE_METHODS}
    if library in {"libffmpegkit.so", "libffmpegkit_abidetect.so"}:
        return {"JNI_OnLoad"}
    if library == "libavutil.so":
        return {"av_set_saf_open", "av_set_saf_close", "av_get_saf_open", "av_get_saf_close"}
    return MPV_CLIENT_SYMBOLS if library == "libmpv.so" else set()


def require_exported_symbols(text, required, library):
    exported = set()
    for line in text.splitlines():
        fields = line.split()
        # llvm-readelf --dyn-syms: Num Value Size Type Bind Vis Ndx Name.
        if len(fields) >= 8 and fields[0].endswith(":") and (
            fields[3] in {"FUNC", "IFUNC"} and fields[4] in {"GLOBAL", "WEAK"} and
            fields[5] in {"DEFAULT", "PROTECTED"} and fields[6] != "UND"
        ):
            exported.add(fields[7].split("@", 1)[0])
    missing = required - exported
    if missing:
        raise ValueError(f"Missing exported functions in {library}: {sorted(missing)}")


def validate_elf_header(data, abi):
    expected_class, expected_machine = ELF_ABIS[abi]
    if len(data) < 20 or data[:4] != b"\x7fELF" or data[4:7] != bytes((expected_class, 1, 1)):
        raise ValueError(f"Invalid ELF class or encoding for {abi}")
    kind, machine = struct.unpack_from("<HH", data, 16)
    if kind != 3 or machine != expected_machine:
        raise ValueError(f"Native output is not a shared library for {abi}")


def require_macros(text, values):
    definitions = dict(re.findall(r"^#define\s+(\w+)\s+([01])\s*$", text, re.MULTILINE))
    for key, value in values.items():
        if definitions.get(key) != str(value):
            raise ValueError(f"Configuration does not satisfy {key}={value}")


def parse_dynamic(text):
    needed = re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", text)
    soname = re.findall(r"\(SONAME\).*?\[([^\]]+)\]", text)
    return needed, soname[0] if len(soname) == 1 else None


def validate_dependencies(inventory, abi='arm64-v8a'):
    if not REQUIRED_LIBS.issubset(inventory):
        raise ValueError(f"Missing native libraries: {sorted(REQUIRED_LIBS - inventory.keys())}")
    if any("torrserver" in name for name in inventory):
        raise ValueError("Torrent source belongs to a separate verified artifact")
    for name, item in inventory.items():
        if item["soname"] != name:
            raise ValueError(f"Cannot package {name} with SONAME {item['soname']}")
        missing = set(item["needed"]) - inventory.keys() - SYSTEM_LIBS
        if missing:
            raise ValueError(f"Unresolved native dependencies for {name}: {sorted(missing)}")
        minimum = 16384 if abi in {'arm64-v8a', 'x86_64'} else 4096
        if item["minimumLoadAlignment"] < minimum:
            raise ValueError(f"Insufficient ELF page alignment: {name}")
        if item.get("relroPageLayoutCompatible") is not True:
            raise ValueError(f"Missing or incompatible RELRO page-layout proof: {name}")


def page_layout(headers, abi='arm64-v8a'):
    loads = []
    ranges, regions = [], []
    for line in headers.splitlines():
        fields = line.split()
        if fields and fields[0] == 'LOAD':
            loads.append(int(fields[-1], 16))
            flags = ''.join(fields[6:-1])
            ranges.append((int(fields[2], 16), int(fields[5], 16),
                           (1 if 'E' in flags else 0) | (2 if 'W' in flags else 0) | (4 if 'R' in flags else 0)))
        elif fields and fields[0] == 'GNU_RELRO':
            regions.append((int(fields[2], 16), int(fields[5], 16)))
    if not loads:
        raise ValueError('No LOAD segments')
    page_size = 16384 if abi in {'arm64-v8a', 'x86_64'} else 4096
    return {'minimumLoadAlignment': min(loads), 'verifiedPageSize': page_size,
            **relro_page_layout(ranges, regions, page_size)}


def verify(root, abi, ndk):
    suffix = ABI_SUFFIX[abi]
    sources = root / "mpv-android/buildscripts/deps"
    ffbuild = sources / f"ffmpeg/_build{suffix}"
    ffconfig = (ffbuild / "config.h").read_text() + (ffbuild / "config_components.h").read_text()
    mpvconfig = (sources / f"mpv/_build{suffix}/config.h").read_text()
    require_macros(ffconfig, {
        "CONFIG_GPL": 1, "CONFIG_NONFREE": 0, "CONFIG_VERSION3": 1, "CONFIG_POSTPROC": 1,
        "CONFIG_HLS_DEMUXER": 1, "CONFIG_MOV_DEMUXER": 1, "CONFIG_HTTP_PROTOCOL": 1,
        "CONFIG_HTTPS_PROTOCOL": 1, "CONFIG_MEDIACODEC": 1, "CONFIG_JNI": 1,
        "CONFIG_SAF_PROTOCOL": 1,
    })
    require_macros(mpvconfig, {"HAVE_GPL": 1, "HAVE_ANDROID": 1, "HAVE_GL": 1,
                               "HAVE_EGL_ANDROID": 1, "HAVE_LIBASS": 1, "HAVE_LIBPLACEBO": 1})
    readelf = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    inventory = {}
    directory = root / "jni" / abi
    if not directory.is_dir():
        raise ValueError("No native build output")
    for library in sorted(directory.glob("*.so")):
        with library.open("rb") as stream:
            validate_elf_header(stream.read(20), abi)
        def inspect(*args):
            return subprocess.check_output([str(readelf), *args, str(library)], text=True)
        dynamic = inspect("-dW")
        needed, soname = parse_dynamic(dynamic)
        headers = inspect("-lW")
        layout = page_layout(headers, abi)
        inventory[library.name] = {
            "sha256": hashlib.sha256(library.read_bytes()).hexdigest(), "bytes": library.stat().st_size,
            "needed": needed, "soname": soname, **layout,
        }
        symbols = inspect("--dyn-syms", "-W")
        required = required_symbols(library.name)
        require_exported_symbols(symbols, required, library.name)
        inventory[library.name]["requiredExportedFunctions"] = sorted(required)
    validate_dependencies(inventory, abi)
    configs = root / "verified-config" / abi
    configs.mkdir(parents=True, exist_ok=True)
    for name in ("config.h", "config_components.h", "ffbuild/config.mak"):
        shutil.copyfile(ffbuild / name, configs / ("ffmpeg-" + name.replace("/", "-")))
    shutil.copyfile(sources / f"mpv/_build{suffix}/config.h", configs / "mpv-config.h")
    report = {
        "state": "native_checks_passed_not_release_approval", "abi": abi,
        "provenance": json.loads((root / "provenance.json").read_text()), "libraries": inventory,
        "ffmpegConfigSha256": hashlib.sha256(ffconfig.encode()).hexdigest(),
        "mpvConfigSha256": hashlib.sha256(mpvconfig.encode()).hexdigest(),
        "remaining": ["Package corresponding sources and notices", "Recomposition test", "Signed app and physical-device regression tests"],
    }
    (root / f"native-verification-{abi}.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Verified {len(inventory)} native libraries for {abi}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--abi", choices=ABI_SUFFIX, required=True)
    args = parser.parse_args()
    verify(args.root.resolve(), args.abi, args.ndk.resolve())
