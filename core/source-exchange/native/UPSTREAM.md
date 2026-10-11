# Lightning-compatible source exchange

The native transport is separated from the Tauri application and connected to
Nyanime's native source-exchange screen through an on-demand Android JNI worker.
Private build and device verification are documented in `docs/source-studio.md`
at the repository root; integration does not imply a public release or OTA.

Reference: https://github.com/Kerim-Sabic/lightning-p2p

Reviewed revision: `6960642a46f549c53d53abef3f2db7327857e488`.

`src/ticket.rs` is adapted from `src-tauri/src/transfer/ticket.rs` at that revision,
Copyright 2025-2026 Kerim Sabic, Apache-2.0. Its license and NOTICE are retained in
this directory. Modifications are documented at the top of the adapted file.

The application and its UI, chat system, updater, keyring, discovery databases,
and Tauri dependencies are not imported. Protocol compatibility targets iroh 1.0,
iroh-blobs 0.103.1, raw blob tickets and Lightning `fd2:` tickets. Version 0.103.0
was yanked from crates.io; the successor patch is pinned rather than bypassing
the registry restriction. See the upstream v0.103.1 changelog.

`native/android/provenance.json` (relative to the module) pins the native source
and artifact hashes. `tools/source-exchange/package_android.py` checks ELF machine
and 16 KiB alignment. Gradle rejects a changed source or mixed native generations.
`tools/source-exchange/build-android.sh` documents the Linux/NDK r27c API 26 build;
the ordinary 0.49.0.0 generation contains verified arm64-v8a, armeabi-v7a,
x86 and x86_64 builds. Rust 1.99.1 is the compiler recorded for this generation.
`bundle_sources.py` retains the exact native source, locked vendored Cargo
closure, recipes and original notices for offline rebuilding.

The Android TLS support AAR is pinned in `tools/source-exchange/rustls-android.json`
and verified before packaging. It initializes Android platform certificate trust;
the R8 consumer rules retain the JNI entry points. Certificate checks are not
disabled to make relay connections work.

Complete Cargo notices and an exact unmodified copy of the MPL-2.0 attohttpc source
are included in the module assets. `collect_notices.py` verifies archive checksums
and original notice bytes against Cargo.lock. The Rust distribution's original
standard-library copyright document and license texts are retained under
`source-exchange/rust`; the toolchain ships a superset of license texts, which is
not a claim that all its compiler/documentation components are linked into the app.
The root project's licensing status is separate from this component's license.


The Android verifier implementation source is retained in the application's source
archive under vendor/maven/org/rustls alongside its AAR/POM. Its commit is pinned
by the published rustls-platform-verifier-android 0.2.0 crate's .cargo_vcs_info.json,
separate from the Maven artifact branch commit. Both identities and the source
archive hash/public URL are recorded in tools/source-exchange/rustls-android.json.
