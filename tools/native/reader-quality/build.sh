#!/usr/bin/env bash
set -euo pipefail
# Run in a native Linux filesystem. NDK r28c supplies 16 KiB-compatible libc++.
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r28c}"
: "${READER_QUALITY_OUTPUT:?Set READER_QUALITY_OUTPUT to a new Linux build directory}"
source_root=$(cd "$(dirname "$0")/../../.." && pwd)
case "$READER_QUALITY_OUTPUT" in /mnt/*) echo 'Build in the Linux filesystem.' >&2; exit 2;; esac
mkdir -p "$READER_QUALITY_OUTPUT"
for abi in "${@:-arm64-v8a}"; do
    case "$abi" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) exit 2;; esac
    cmake -S "$source_root/core/reader-quality/src/main/cpp" -B "$READER_QUALITY_OUTPUT/$abi" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
        -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
    cmake --build "$READER_QUALITY_OUTPUT/$abi" --parallel 2
done
