# Nyanime 0.49.0.0: build and corresponding-source provenance

The signed Android application is 0.49.0.0, code 280, on the Recommended channel.
Its compiled revision is `3de8394ba0f015828d044c046cd93e9ce9e5469b`; this is also the commit identifier embedded
in the app. The full corresponding source reference is recorded as `sourceRevision`
in the public release manifest.

After compilation and device validation, the corresponding source includes two
completed, non-runtime changes: the typed source-manager fixture in
`app/src/test/java/eu/kanade/tachiyomi/discovery/MangaHomeIntegrationTest.kt` and this provenance note. The fixture avoids MockK confusing a Flow
property accessor with a List-returning method sharing the same JVM name.
All original assertions remain active, and all 1,708 app tests complete without
failures (seven existing skips). No production code, resource, native library, manifest, dependency,
compiler configuration or shrinker rule differs from the compiled revision.

Before publication, the exact changed-path list and both Git trees were compared.
Excluding only those two non-runtime files, the SHA-256 of the canonical recursive
Git tree records (including paths, modes and blob/gitlink identifiers) is
`5d8c400d60b8c1f184082d74640922a59758a41c81c7569514857bec5c1aaca7` for both references. The signed APK bytes tested on the USB device
are the bytes published; a test or documentation fix does not repackage the app.
An independently rebuilt export can have different Git/build metadata and signing
bytes; bit-identical rebuilds are not claimed.

| APK architecture | SHA-256 |
| --- | --- |
| arm64-v8a | `fd4a20926a2c6d13a6a8c941aaea458e87ac27390e0d431223ba87a57e886e29` |
| armeabi-v7a | `486a0bb9100750a0e8a3ce7c6bed7a9fa85f852c066826e01398ea9822a1ee8d` |
| x86 | `78b2111a2496edd7ce1bdd8068f4af49190760d8b7d4f5d467ef12e65afd2c96` |
| x86_64 | `b24288fdd0a29a1a93aea5ce6842a8100eb85c3b61a2b323cbfafaa1de247ff7` |
| universal | `5f4ef06a0d8473eb040f58e199d6a3692d98cb7a026bed6cc3bb3ce3486ddcb3` |

The source ZIP contains the corrected tests, this note, and all production inputs.
Native source packages and notices remain declared in
[`tools/native/distribution.json`](../tools/native/distribution.json).
