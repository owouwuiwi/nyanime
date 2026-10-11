# Native player presentation

The ordinary 0.49.0.0 app uses `presentation-player`: an isolated graphical
subset of FLOW revision `14afdf9e9426fb8b67e5fcbc76bf4e81af492d16`, adapted to
Nyanime's existing MPV host. There is no FLOW/YouTube engine or MELD module,
submodule, account database, extraction service or Home in this distribution.

The host owns playback, audio, tracks, PiP, Cast, rooms, Smart, attention,
MediaSession and persistent scrub previews. The renderer receives immutable UI
values and callbacks. Horizontal seeking retains its gesture until release;
cancelled drags do not seek. Short native buffering does not reopen hidden
controls, and double-tap feedback retains its last value and side while fading.

## Source and licence boundary

Original Nyanime contracts and bridge code retain Apache-2.0, expressly authorized
by their copyright owner on 19 October 2026. Adapted FLOW graphics retain
GPL-3.0-only. Full texts, original selected snapshots, upstream hashes and the
modification notice are in `presentation-player/third-party/flow-ui`.
`presentation-player/PROVENANCE.md` maps this scope. The AAR packages both sets
of notices; the app lists both entries in its offline licence screen.
This is a combined GPL distribution, not an Apache-only or closed-source claim.

## Verification

```sh
./gradlew :presentation-player:spotlessCheck :presentation-player:testDebugUnitTest :presentation-player:assembleRelease
python -m unittest scripts.tests.test_player_presentation scripts.tests.test_normal_migration
python scripts/verify_player_presentation.py presentation-player/build/outputs/aar/presentation-player-release.aar
python -m scripts.verify_normal_migration --dependencies app/build/reports/ordinary-runtime-dependencies.json --apk app/build/outputs/apk/preview/app-arm64-v8a-preview.apk
```

The Gradle boundary checks compile/runtime dependencies. The AAR check examines
compiled references and exact notices. Final APK checks inspect optimized
Compose/Lifecycle calls, JNI, native inventories, signatures and excluded runtimes.
Device evidence belongs in the version-specific migration report; a library test
is not proof of video fluidity, casting or room synchronization.

Historical NyanimeB 0.48.2.22/.23 device tests established the seek interaction
before this migration. Those binaries included optional engines and are not the
ordinary APK. Their changelog entries remain explicitly marked as experimental
history and are excluded from newly generated ordinary OTA notes.
