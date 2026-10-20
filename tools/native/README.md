# Native distribution and corresponding source

From Nyanime 0.42.0.2, the complete MPV/FFmpeg native closure is rebuilt from
[immutable source revisions](player/sources.lock.json). GPL/version3 remain
enabled; nonfree remains disabled. The original Android bridge APIs, JNI,
MediaCodec, libass subtitles, libplacebo/user shaders, FFmpegKit callbacks,
SAF protocol and generic HLS seek patches are retained.

## Release materials

The [release page](https://github.com/owouwuiwi/nyanime/releases) accompanies
signed APKs with the application source ZIP and a native manifest linking the
complete player, supplemental and WebRTC source archives by public URL and hash.
When native bytes are unchanged, later releases reuse those immutable archives
instead of uploading duplicate archives under every app version.
The [distribution manifest](distribution.json) binds
all shipped native bytes to component identities, licenses and material hashes.
No production key or personal data is required to build your own version.

- **Player:** complete dependency source trees, recursive submodules, patches,
  build scripts, compiler configuration and native checks for all four ABIs.
  Common source is delivered once. ABI-specific source overlays are preserved:
  upstream scripts change x86 TLS configuration, and those changes are not
  erased or treated as identical to the ARM sources.
- **Supplemental:** original image decoder and static codec sources (including
  MPL/LGPL components), archive libraries, the original player Java API,
  TorrServer and its full Go vendor closure, QuickJS, secp256k1, Zstandard,
  TDLib and OpenSSL. Upstream copyrights and full notices are retained.
- **Source exchange (0.49):** all four Android ABIs from the reviewed Rust inputs,
  Cargo.lock and offline vendor closure, with NDK r27c/API 26 build and packaging
  recipes. Exact source hashes and original notices are retained in the dedicated
  exchange archive; `tools/source-exchange/bundle_sources.py` creates the closure.
- **Camera/inference (0.49):** unmodified CameraX and MediaPipe native Maven bytes,
  original AAR/POMs, published AndroidX sources, CameraX embedded notices and the
  associated complete MediaPipe public release sources. Source-JAR availability
  and internal prebuilt-provenance limits are recorded explicitly in the archive.
- **Room voice:** the separately pinned WebRTC engine and Android binding, with
  original third-party notices, in the archive introduced with 0.47.0.2.
- **Permissive prebuilt artifacts:** published Maven identity and bundled notices
  are recorded explicitly. A release-tag wrapper snapshot does not prove the
  internal compiler/dependency revision of a prebuilt artifact. No invented
  BoringSSL/LiteRT internal revision is presented as recovered provenance.
- **Android runtime/toolchain:** NDK r28c (28.2.13676358), including its original
  runtime notices. Platform libraries and standard toolchain components are
  obtained from the official Android distribution.

The original application contributions remain Apache-2.0. Distribution of the
combined GPL player/application must satisfy the applicable GPL-3.0 conditions;
separately licensed components retain their own notices and copyright.
See [licensing](../../docs/licensing.md) and [NOTICE](../../NOTICE).

## Rebuild the player

Use Linux, Python 3.11+, Git, pkg-config, Meson, Ninja, CMake, make, autoconf,
automake, libtool and NASM, with the official **NDK r28c**. Use a dedicated Linux
filesystem directory, not a mounted Windows build tree. API 21 is the native
minimum; the application minimum remains API 26.

### Using the delivered archive, including offline builds

Verify the archive SHA-256 against the release manifest. Extract into a new
empty build directory for each ABI. The snapshot already contains the complete
modified source; do not apply the preparation patches a second time.

```sh
mkdir /tmp/player-arm64
# Use the verified player source archive from the release.
tar -xzf Nyanime-Native-0.42.0.2-player.tar.gz -C /tmp/player-arm64
python3 tools/native/player/select_archive_sources.py --root /tmp/player-arm64 --abi arm64-v8a
bash /tmp/player-arm64/rebuild-recipe/build-linux.sh /tmp/player-arm64 /opt/android-ndk-r28c arm64-v8a 2
```

Repeat with fresh extracted directories for `armeabi-v7a`, `x86`, `x86_64`.
The per-ABI overlays and saved configurations document all differences. The
64-bit outputs require 16 KiB ELF alignment. Official 32-bit NDK runtimes use
4 KiB alignment; the verifier applies the correct policy to each architecture.
A rebuild can differ in timestamps/embedded paths; matching sources and
configuration does not promise byte-for-byte reproducibility on every host.

For source checkouts created below, run `bundle_sources.py`. For extracted
archives, copy the verified player archive into each build root as
`corresponding-sources.tar.gz` and write its SHA-256 to
`corresponding-sources.sha256`. These files let `package_aars.py` verify the
source package alongside the rebuilt libraries; no Git checkout is required.

### Preparing new source checkouts

```sh
python3 tools/native/player/prepare_sources.py --root /tmp/player-build
bash tools/native/player/build-linux.sh /tmp/player-build /opt/android-ndk-r28c arm64-v8a 2
python3 tools/native/player/bundle_sources.py --root /tmp/player-build
```

The preparer pins every Git commit, recursive gitlink and Lua archive hash.
It fails on unexpected upstream patches or a reused build directory. The
verifier checks architecture, dependency closure, JNI/FFmpeg exports, GPL and
protocol flags, page alignment and RELRO layout. It records the exact configure
files; build success alone is not device playback proof.

`package_aars.py` accepts either the original pinned upstream API templates or
the AARs in `vendor/maven/it/owouwuiwi/native`. It verifies their Java/Kotlin
payload fingerprint and discards **every original native entry**. All native
entries must come from verified fresh builds. The bridge source snapshots are
also in the supplemental archive if you wish to rebuild those APIs.

## Other native components

The image decoder's original Gradle/CMake recipe and pinned codec trees are
provided in the supplemental archive; its source index is
[image-decoder.sources.json](image-decoder.sources.json). TorrServer includes
`build-all.sh`, Go 1.26.4 build metadata, `go.mod`, `go.sum` and `server/vendor`.
Use `-mod=vendor` when rebuilding its Android Go libraries. The original Android
JNI wrapper and upstream build scripts are included.

TDLib uses [the pinned recipe](../../scripts/build_telegram_native.py). The
[reviewed artifact](telegram-artifact.json) is mirrored in `vendor/native`;
`python3 -m scripts.restore_telegram_native` restores and verifies it. This
avoids compiler/path variation changing published native bytes on unrelated
app builds. Native maintenance builds remain available separately; a new
native output must be reviewed before replacing the mirror and manifest.

## Publication checks

`verify_native_distribution.py` compares every `.so` in the actual APK with
this release's declared native inventory and verifies the delivered archive
hashes. Schema 4 publication bundles require the same manifest in the public
source snapshot, every corresponding material and all five APK variants.
Missing archives, changed bytes, undeclared libraries and duplicate ownership
block publication. Historical schemas remain readable without pretending old
releases have this additional proof.

## Historical maintenance builds

The [old HLS maintenance recipe](historical-hls.md) documents `1.18-hls1`.
It is no longer a dependency. Its original MPV snapshot had unrecorded moving
branch dependencies; new source archives must not be described as the source
of those old binaries. Old source records remain available, and the temporary
migration bridge retains its original bytes and URLs.

## Private build bootstrap

The first-publication job restores the two new source archives from a private,
hash-verified mirror before producing the public bundle. The mirror is not the
recipient's source location: the exact archives are included in the public release
and linked by public URL in distribution.json. Later builds can reuse those public
materials. `scripts.restore_release_materials` checks hashes before exposing a
cached file; failed or partial downloads are not accepted.
