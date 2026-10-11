# FFmpeg 7.1 HLS seek backport

`1.18-hls1` is a local FFmpegKit maintenance build, not an upstream release.
Only `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libavformat.so` differs from
FFmpegKit 1.18. MPV, codecs, Java APIs and the other native libraries remain intact.
This avoids packaging duplicate libraries or relying on `pickFirst` order.

## Sources and licensing

The [source index](sources.json) links immutable revisions and ZIP downloads
for the native projects, their original build recipes and Nyanime modifications.
The application release source archive includes this index and the patches.

The original MPV build recipe fetches dav1d, libass and libplacebo from moving
branches. The AAR hash establishes artifact identity, but does not recover those
commits. This limitation remains explicit in the index; a complete native
reproducibility/compliance claim requires the original dependency snapshot or
an independently verified pinned rebuild. Do not substitute today's branches
and describe them as the source of the shipped binary.


- FFmpeg n7.1: `b08d7969c550a804a59511c7b83f2dd8cc0499b8`.
- Backport: [FFmpeg 380a518](https://github.com/FFmpeg/FFmpeg/commit/380a518c439d4e5e3cf17b97e4a06259e8048f99),
  resetting the MOV fragment/sample indexes after an HLS seek.
- `ffmpeg-force-mpegts.patch` preserves the optional upstream FFmpegKit Android
  `force_mpegts` setting. Its default remains false; do not force MPEG-TS on fMP4.
- Mbed TLS 3.6.2: `107ea89daaefb9867ea9121002fbbdf926780e98` (Apache-2.0 OR GPL-2.0-or-later).
- libxml2 2.13.5 headers: `de918d45e1b2276a28a4cd32bcf556bef65284e4` (MIT).
- FFmpeg is configured with GPL/version3; the resulting demuxer uses GPL-3.0-or-later.
  Corresponding source, configuration and all patches are identified here and in the
  preparation/build scripts. Upstream license texts are retained in the source checkouts.
- The rest of the original [FFmpegKit artifact](https://github.com/jmir1/ffmpeg-kit/tree/1.18)
  is unmodified and retains its original licensing requirements.

### MPV artifact identity

The coordinate `aniyomi-mpv-lib:1.18.n` has an upstream JitPack recipe that
downloads the **1.17.n** binary. The cached AAR's SHA-256 matches the unmodified
`aniyomi-mpv-lib-1.17.n.aar` release exactly, not the 1.18.n release asset.
Its corresponding Android bridge source is
[jmir1/mpv-android 484373b7](https://github.com/jmir1/mpv-android/tree/484373b7cb772d43405c4312af1ad4f508f6dbd0).
Its build scripts pin libmpv to `d82701962f99051a18d65c215b70d41ebadd9a22`.
The wrapper is MIT; the linked native player retains its GPL requirements.
This attribution correction does not change the player binary.

The native patch is generic: it contains no site, device, series, episode or signed URL.
It does not bypass server access checks. It corrects local demuxer state.

## Rebuilding on Windows

Prerequisites: PowerShell, Git for Windows/Bash, tar, NDK r27c (27.2.12479018),
CMake 3.22.1/Ninja and the two existing dependency AARs. Use a dedicated short
build path without spaces. Preparation never resets or deletes an existing checkout.
Use a fresh directory when changing sources, NDK, configure flags or this recipe;
incremental configure output is intentionally reused within one build.

```powershell
./tools/native/Prepare-NativeSources.ps1 -BuildRoot C:/native-hls-build
foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')) {
    ./tools/native/Build-PatchedAvformat.ps1 -BuildRoot C:/native-hls-build `
        -Ndk '<NDK path>' -CmakeBin '<CMake bin path>' `
        -FfmpegKitAar '<ffmpeg-kit-1.18.aar>' -MpvAar '<aniyomi-mpv-lib-1.18.n.aar>' -Abi $abi
}
./tools/native/Package-PatchedAvformat.ps1 -BuildRoot C:/native-hls-build -FfmpegKitAar '<ffmpeg-kit-1.18.aar>'
./tools/native/Test-PatchedAvformat.ps1 -BuildRoot C:/native-hls-build -Ndk '<NDK path>' -FfmpegKitAar '<ffmpeg-kit-1.18.aar>'
```

`--host-cc` uses the target compiler because this restricted library-only target
does not run host executables. This recipe is not a general FFmpeg tools build.
TLS, XML and zlib support are enabled. The library SONAME and LIBAVFORMAT_61 API
are retained. New ELF segments use 16 KiB alignment. x86 assembly is disabled for
this demuxer build; original optimized video/audio codecs are not rebuilt.

Android/GitHub builds consume the checked-in, pinned AAR. They do not need the
Windows native toolchain. Updating the upstream MPV/FFmpeg major version requires
a new ABI review; do not silently apply this binary to another FFmpegKit release.

## SHA-256

- Original FFmpegKit AAR: `f3cfbd97f85ba25bcd6c594227d10df612f69ad4499576b65a4aa01190be21d9`
- Original MPV AAR: `a08c2d3345fb1f46f7ffe2f68999f244666de4a1ed1f90a0cef6c1c761a6d793`
- Patched AAR: `d0c159c4ee80dfcd2eff84ff895495f8d2dca1a110baa2f0c6ce6592af3ee777`
- arm64-v8a: `47cf133a7ac8ad233bc71bb3799a900692038d14ab937b0f3de90f82af467dad`
- armeabi-v7a: `c48e57a84f82a273d9541651fd7e23a762b8051ddb6c33df3d0f3940b09357d8`
- x86: `8fc1d6fd34a702eff7196a140ad3ea5dc65dc4c2be75f2cabc47253d886ccc1f`
- x86_64: `e3bbcd32d54b0083fed192b0f25bb3d1eab992e5e5ee3b542dd49424a5bee636`

Packaging is deterministic for the same input binaries/toolchain. A native rebuild
in another absolute path can differ in embedded configure metadata; compare ABI,
features and behavior as well as hashes. Build success is not a playback test.
