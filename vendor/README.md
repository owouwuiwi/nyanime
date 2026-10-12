# Pinned artifact mirror

Nyanime keeps immutable build inputs here when the configured remote artifact
cannot be relied on for rebuilding. Each mirrored coordinate is explicitly
restricted in settings.gradle.kts; unrelated versions still resolve remotely.

## FlexibleAdapter

JitPack returned HTTP 500 / missing artifacts for FlexibleAdapter on September 9, 2026,
blocking the stability release. This is the unmodified AAR and POM recovered from the
local Gradle cache of the project's existing dependency, not a version downgrade.

Coordinates: `com.github.arkon.FlexibleAdapter:flexible-adapter:c8013533`.
Source: https://github.com/arkon/FlexibleAdapter/tree/c8013533
License: Apache-2.0, included as `FlexibleAdapter-LICENSE`.

SHA-256:

- AAR: `41929c785c249e0395faf89fd6bb253aafd65d44d88dbeaa46ecd9658d706cc4`
- POM: `7ee6f68cfc5a369efbd1dea8c4ee2b7b93979a4abe16d2f289e21acecaa44fdf`

Repository content filtering limits this dependency to that exact version. New upstream
versions resolve normally. The source files and native libraries in the AAR are unchanged.

## FFmpegKit maintenance build

`com.github.jmir1:ffmpeg-kit:1.18-hls1` is a local maintenance artifact containing
the generic HLS seek backport in libavformat. It is a separate pinned dependency,
not part of the unmodified FlexibleAdapter artifact above.
See the [native build guide](../tools/native/README.md) for source revisions,
patches, build instructions, validation and licensing.
