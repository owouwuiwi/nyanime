# Playback track preparation

The extension supplies track URLs and labels through the existing Video contract.
The player does not infer a service, language, transport or audio layout from a
source identity. No provider-specific parser or native binary is added here.

## Preparation and selection

The service pauses ordinary local playback while preparing the preferred audio
and subtitle. It retains the user's playback intent and resumes only after the
required audio has opened. Rooms retain ownership of their playback barrier.
An unavailable selected audio track fails visibly instead of starting silent or
silently changing languages. Missing subtitles are reported without discarding
working audio.

Native pause notifications report the decoder's actual state; they are not new
Pause/Play commands. In particular, acknowledging the temporary preparation hold
must not replace the pending playback intent. Explicit next/previous selection
starts when ready, quality replacement retains the previous intent, and a real
Pause/Play command received during preparation remains authoritative. Stale native
notifications are ignored when they disagree with the decoder's current property.
Ordinary native pauses still save eligible progress; preparation and scrub holds
do not create new pause commands or override room ownership.

Native track metadata is decoded from one atomic `track-list` snapshot on IO.
Unopened alternatives remain visible as metadata; opening the menu does not open
their URLs. A selection opens only the requested alternative. The native `auto`
add flag avoids selecting a cancelled request before its generation/token is
checked. The catalog and a fresh snapshot reuse previously opened URLs.

Metadata notifications only update choices. They never reapply startup language
preferences. Separate audio and subtitle selection tokens reject superseded
results, preserving the existing two-subtitle controls. Track operations share the
native ownership lock; closing waits before destruction. Media replacement keeps
the engine's cancellation of outstanding track adds, so changing video does not
wait for the network timeout of an obsolete alternative.

## Seeking and diagnostics

Independent audio uses explicit exact relative and absolute seeks. Embedded audio
retains the user's fast/precise seeking preference. Native MPV already coalesces
queued seeks; the app does not add a delayed recovery seek or a permanent offset.
An older exact-seek completion cannot acknowledge a newer, different target.

Exceptional playback warnings and restarts can request a bounded timing snapshot:
at most one every two seconds and 32 per media generation. Reports contain numeric
position, A/V timing, speed, cache and dropped frames, with no stream URLs, source
labels, cookies or credentials. Normal playback adds no periodic polling loop.

## Validation

JVM fixtures cover missing metadata, unopened alternatives, duplicate URLs, native
IDs, exact seek policy, stale seek acknowledgements and diagnostic bounds.
`PlayerPauseObservationTest` exercises the actual native-event boundary with
controlled engine/model contracts: next episode, quality replacement, commands
during loading, stale notifications and command-feedback prevention. These JVM
checks do not replace playback validation on the signed device build.
`PlaybackTimingDeviceProbe` is an opt-in instrumentation runner: content IDs are
supplied locally, timing reports are numeric, tracking is disabled and incognito
is restored after the run. It is not part of the application APK.

Build the opt-in test APK with `-I scripts/playback_timing_probe.init.gradle
:app:assemblePreviewAndroidTest` and the same signing configuration as the tested
application. Run its instrumentation with `mode=inspect` and a local `title`
argument to inspect numeric episode IDs/progress, or supply `animeId` and
`episodeId` to exercise startup and seeks. Do not use the probe in an active room
or while the person is using the device. Inspect progress before and after testing.

Device checks must distinguish startup/seek transients from steady playback and
verify audible output as well as numerical A/V timing. A successful build or a
small measured clock difference alone does not establish perceptual lip sync for
every remotely supplied stream. Use the existing Anime4K telemetry when checking
rendering load; this change does not retune Smart or upgrade native dependencies.

Relevant native behavior is documented in the pinned engine sources:
[queued seeks](https://github.com/mpv-player/mpv/blob/d82701962f99051a18d65c215b70d41ebadd9a22/player/playloop.c#L395)
and [external track selection](https://github.com/mpv-player/mpv/blob/d82701962f99051a18d65c215b70d41ebadd9a22/player/command.c#L5720).
