#!/usr/bin/env bash
# Native builds run in a dedicated Linux filesystem checkout, never in /mnt/c.
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo "Usage: $0 /absolute/native/source /absolute/android-ndk-r27c" >&2
  exit 2
fi
source_dir=$(realpath "$1")
ndk_dir=$(realpath "$2")
case "$source_dir" in /mnt/*) echo "Copy the source to the Linux filesystem first." >&2; exit 2;; esac
cd "$source_dir"
[[ -f Cargo.lock && -f "$ndk_dir/source.properties" ]]
grep -q '27.2.12479018' "$ndk_dir/source.properties"
toolchain="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
export RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$toolchain/aarch64-linux-android26-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$toolchain/armv7a-linux-androideabi26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$toolchain/x86_64-linux-android26-clang"
export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$toolchain/i686-linux-android26-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export CC_armv7_linux_androideabi="$CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER"
export CC_x86_64_linux_android="$CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"
export CC_i686_linux_android="$CARGO_TARGET_I686_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$toolchain/llvm-ar"
export AR_armv7_linux_androideabi="$toolchain/llvm-ar"
export AR_x86_64_linux_android="$toolchain/llvm-ar"
export AR_i686_linux_android="$toolchain/llvm-ar"
for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android; do
  rustup target add "$target"
  cargo build --locked --release --lib --target "$target" --jobs "${NATIVE_JOBS:-2}"
done
