#!/usr/bin/env bash
set -euo pipefail
root=$(cygpath -u "$ANIYOMI_NATIVE_ROOT")
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
ndk=$(cygpath -u "$ANIYOMI_NATIVE_NDK")
abi=$ANIYOMI_NATIVE_ABI
prefix=$root/prefix-$abi
build=$root/build-$abi
export PATH="$root/make/usr/bin:$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin:$PATH"
case "$abi" in
    arm64-v8a) arch=aarch64; triple=aarch64-linux-android; cpu=armv8-a ;;
    armeabi-v7a) arch=arm; triple=armv7a-linux-androideabi; cpu=armv7-a ;;
    x86) arch=x86; triple=i686-linux-android; cpu=i686 ;;
    x86_64) arch=x86_64; triple=x86_64-linux-android; cpu=x86-64 ;;
esac
mkdir -p "$build/ffmpeg/libavcodec" "$build/ffmpeg/libavutil"
cp "$prefix/lib/libavcodec.so" "$build/ffmpeg/libavcodec/"
cp "$prefix/lib/libavutil.so" "$build/ffmpeg/libavutil/"
cd "$build/ffmpeg"
if [[ ! -f ffbuild/config.mak ]]; then
    "$root/ffmpeg/configure" \
        --target-os=android --enable-cross-compile --arch="$arch" --cpu="$cpu" \
        --cc="clang.exe --target=${triple}21" --cxx="clang++.exe --target=${triple}21" \
        --host-cc="clang.exe --target=${triple}21" \
        --ar=llvm-ar --nm=llvm-nm --ranlib=llvm-ranlib --strip=llvm-strip \
        --enable-shared --disable-static --enable-gpl --enable-version3 \
        --disable-programs --disable-doc --disable-debug --disable-devices \
        --enable-jni --enable-mediacodec --disable-vulkan --disable-autodetect \
        --enable-mbedtls --enable-libxml2 --enable-zlib --disable-x86asm \
        --extra-cflags="-I$prefix/include -I$root/libxml2/include -I$build/xml" \
        --extra-ldflags="-L$prefix/lib -Wl,-z,max-page-size=16384" \
        --pkg-config="$script_dir/pkg-config-xml.sh"
fi
# Only replace the demuxer/muxer library; retain the existing codec and utility ABI.
make -j8 -o libavcodec/libavcodec.so -o libavutil/libavutil.so libavformat/libavformat.so
llvm-strip --strip-unneeded libavformat/libavformat.so
llvm-readelf -d libavformat/libavformat.so | head -45
