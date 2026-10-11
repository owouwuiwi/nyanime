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

## Native player

`it.owouwuiwi.native:nyanime-mpv:1.0.0` and
`it.owouwuiwi.native:nyanime-ffmpeg:1.0.0` preserve the original bridge APIs
and replace the complete native closure with a pinned GPL build.
The [native guide](../tools/native/README.md) identifies its sources,
patches, build configuration and corresponding-source archives.
The AAR inventory and SHA-256 values are in
[aar-verification.json](../tools/native/player/aar-verification.json).
Every ABI and every library is checked before packaging; no unverified native
entry from the original AARs is carried into these artifacts.

The historical `1.18-hls1` dependency is no longer used. Its generic patches
and original maintenance recipes remain available for provenance.
