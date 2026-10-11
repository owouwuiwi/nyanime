#!/usr/bin/env bash
# Build in a fresh prepared root. No app build, signing, release or publication.
set -euo pipefail
recipe=$(cd -- "$(dirname -- "$0")" && pwd)
root=$(realpath -- "${1:?prepared root}")
ndk=$(realpath -- "${2:?NDK r28c}")
abi=${3:-arm64-v8a}
jobs=${4:-2}
[[ $(uname -s) == Linux ]] || { echo 'This full recipe requires Linux.' >&2; exit 2; }
[[ $jobs =~ ^[1-9][0-9]*$ ]] || exit 2
[[ $root != /mnt/* && $root != / ]] || { echo 'Use a dedicated Linux filesystem directory.' >&2; exit 2; }
grep -Eq '^Pkg.Revision *= *28\.2\.13676358' "$ndk/source.properties"
for tool in python3 pkg-config meson ninja cmake make autoreconf nasm; do command -v "$tool" >/dev/null; done
python3 - "$root" "$recipe" <<'PY'
import hashlib, json, sys
from pathlib import Path
root, recipe = map(Path, sys.argv[1:])
p = json.loads((root / 'provenance.json').read_text())
assert p['lockSha256'] == hashlib.sha256((recipe / 'sources.lock.json').read_bytes()).hexdigest(), 'Source lock changed: prepare again'
for name, digest in p['recipeHashes'].items():
    assert hashlib.sha256((recipe / name).read_bytes()).hexdigest() == digest, f'Recipe changed: prepare again ({name})'
PY
case $abi in
    arm64-v8a) arch=arm64; suffix=-arm64; ndkarch=arm64; prefixvar=PREFIX64 ;;
    armeabi-v7a) arch=armv7l; suffix=; ndkarch=arm; prefixvar=PREFIX32 ;;
    x86) arch=x86; suffix=-x86; ndkarch=x86; prefixvar=PREFIX_X86 ;;
    x86_64) arch=x86_64; suffix=-x64; ndkarch=x86_64; prefixvar=PREFIX_X64 ;;
    *) echo 'Unsupported ABI' >&2; exit 2 ;;
esac
wrapper=$root/mpv-android
scripts=$wrapper/buildscripts
prefix=$scripts/prefix/$arch
export cores=$jobs
export SOURCE_DATE_EPOCH=1729555200
mkdir -p "$scripts/sdk"
[[ ! -e $scripts/sdk/android-ndk-r28c ]] || { echo 'Use a fresh root for each ABI.' >&2; exit 2; }
ln -s -- "$ndk" "$scripts/sdk/android-ndk-r28c"

cd "$scripts"
bash ./buildall.sh --arch "$arch" mpv
toolchain=$ndk/toolchains/llvm/prebuilt/linux-x86_64
export PATH="$toolchain/bin:$PATH"

# Preserve the original JNI implementation and its complete dependency list.
env "$prefixvar=$prefix" "$ndk/ndk-build" -C "$wrapper/app/src/main" \
    "APP_ABI=$abi" APP_STRIP_MODE=none \
    "APP_LDFLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    "NDK_OUT=$root/obj-player" "NDK_LIBS_OUT=$root/staging/player" -j"$jobs"

# Build the exact compatibility dependency used by the original FFmpegKit wrapper.
kit=$root/ffmpeg-kit
prebuilt=$kit/prebuilt/android-$ndkarch
cmake -S "$root/cpu-features" -B "$root/obj-cpu" -G Ninja \
    "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" \
    "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-21 \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_TESTING=OFF \
    "-DCMAKE_INSTALL_PREFIX=$prebuilt/cpu-features"
cmake --build "$root/obj-cpu" --target install --parallel "$jobs"
mkdir -p "$prebuilt/ffmpeg"
cp -a -- "$prefix/lib" "$prefix/include" "$prebuilt/ffmpeg/"
ffmpeg=$scripts/deps/ffmpeg
cp -- "$ffmpeg/_build$suffix/config.h" "$prebuilt/ffmpeg/include/"
# The wrapper's fftools use private FFmpeg headers, as in its pinned build recipe.
while read -r header; do
    mkdir -p -- "$prebuilt/ffmpeg/include/$(dirname -- "$header")"
    cp -- "$ffmpeg/$header" "$prebuilt/ffmpeg/include/$header"
done <<'HEADERS'
libavcodec/mathops.h
libavcodec/x86/mathops.h
libavcodec/arm/mathops.h
libavformat/avio.h
libavformat/network.h
libavformat/os_support.h
libavformat/url.h
libavutil/bprint.h
libavutil/emms.h
libavutil/getenv_utf8.h
libavutil/attributes_internal.h
libavutil/internal.h
libavutil/libm.h
libavutil/reverse.h
libavutil/thread.h
libavutil/timer.h
libavutil/x86/asm.h
libavutil/x86/timer.h
libavutil/arm/timer.h
libavutil/aarch64/timer.h
HEADERS
cat > "$kit/android/jni/Application.mk" <<APPLICATION
APP_OPTIM := release
APP_ABI := $abi
APP_STL := c++_shared
APP_PLATFORM := android-21
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
APP_CFLAGS := -O3 -DANDROID -DFFMPEG_KIT_BUILD_DATE=20261010 -Wall -Wno-deprecated-declarations -Wno-pointer-sign -Wno-switch -Wno-unused-result -Wno-unused-variable
APP_LDFLAGS := -Wl,--hash-style=both -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
APPLICATION
"$ndk/ndk-build" -C "$kit/android" "APP_ABI=$abi" \
    APP_STRIP_MODE=none "NDK_OUT=$root/obj-kit" "NDK_LIBS_OUT=$root/staging/kit" -j"$jobs"

# Include every freshly built shared dependency. This must close all DT_NEEDED links.
# ndk-build deletes installed modules it does not own. Never let the second
# invocation remove the player's JNI output. Reject differing duplicate bytes.
python3 "$recipe/merge_native.py" \
    --input "$root/staging/player/$abi" --input "$root/staging/kit/$abi" \
    --input "$prefix/lib" --output "$root/jni/$abi"
# Strip exactly once, after comparing raw module bytes, before recording hashes.
# NDK's default per-invocation stripping otherwise conflicts with the raw prefix.
for library in "$root/jni/$abi"/*.so; do
    "$toolchain/bin/llvm-strip" --strip-unneeded "$library"
done
python3 "$recipe/verify_native.py" --root "$root" --abi "$abi" --ndk "$ndk"
echo "Native build verified for $abi; app coordinates remain unchanged."
