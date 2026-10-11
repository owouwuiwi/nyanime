# Paused seek previews on NyanimeB

The MPV host uses one `PlaybackScrubCoordinator` for horizontal video gestures
and timeline dragging. Starting a valid preview captures the actual position,
duration, playback identity and pause intent, then temporarily pauses native
audio and video. Pointer movement changes only the bounded preview target.
Release commits one seek through the existing seek policy and restores the
latest playback intent. Cancel commits nothing. A new gesture never inherits
an old preview target.

Explicit play/pause, episode or video changes, surface ownership, PiP and process
background policy can revoke the interaction. Playback restoration belongs to
the original session and episode only. Delivered Android `ACTION_CANCEL` events
are observed on the common video ancestor. This alone is not sufficient:
Compose can cancel a pointer coroutine without forwarding the raw event to
`motionEventSpy`, and Material3 Slider still invokes its completion callback.
The thin timeline handles coroutine cancellation before its final callback;
the expanded host Slider requires an observed `ACTION_UP` to commit a touch
sequence. Completion without a release cancels first. Non-pointer keyboard
and accessibility actions retain their normal completion path.
Cancellation observers must never be fullscreen siblings above the gesture layer:
non-consuming observers still participate in Compose hit testing and can hide
underlying siblings from input. Timeline completion without an active coordinator is ignored.

## Feedback and performance

The readout is drawn independently of control visibility, optional gesture bars
and thumbnail availability. It always displays the destination and signed
difference. Existing storyboard tiles are optional; missing, late or invalid
tiles do not hide the text. Large text takes precedence over the image, and the
card is positioned within the current video bounds. Brief opacity transitions
respect reduced motion. The legacy timeline's extra readout is suppressed only
in this host; embedded video defaults remain unchanged.

Only the readout and timeline consume preview targets. The surrounding player
observes the interaction's start and finish, preserving the existing isolated
playback clock.
There is no second decoder, frame capture, native seek while dragging or new
network service. Thumbnail responses from another playback identity are ignored.
Existing seek precision and external-audio flags are retained. SM shaders,
presets, calibration and renderer code are unchanged.

## Shared playback

The local `WatchPlayback.scrubbing` field is optional and never enters the wire
protocol. During the preview the sample describes the undisturbed logical clock,
not the frozen native frame or the finger's destination. Room heartbeats continue
normally, without reporting the deliberate local pause as buffering. Drift
corrections are deferred while previewing. Remote explicit seeks can revoke the
interaction, and remote pause/play remains authoritative on release. The same
distinction is used by the Syncplay compatibility controller.

## Validation

Focused checks cover pause intent, initialization, bounds, reversals, repeated
gestures, cancellation, stale ownership and the distinction between preview and
shared clocks. Host/guest room regressions cover long previews without false
buffering or correction seeks. Device and signed-APK results are recorded with
the candidate; compilation and unit results do not substitute for those checks.

This change is limited to NyanimeB. Embedded video playback, live rewind and Cast
remote commands retain their existing behavior. No push, release or OTA is part
of this private trial.

### Physical regression check, 19 October 2026

The optional [player input probe](../scripts/device/player_input/README.md) is a
separate self-targeted test APK with no launcher or dependency on app classes.
It uses real input and public MediaSession/accessibility APIs; it is never
packaged in NyanimeB and is removed after the run. Its package argument and
synthetic test data do not identify any extension.

The signed arm64 trial `0.48.2.20` / code `276`, SHA-256
`a71960a7695980c9f0ae3cad48a85ce4f8a7d9a6888a22947fb147297d586409`,
passed USB checks for inline/fullscreen taps, a playing and initially paused
drag, raw cancellation on both the video and expanded timeline, explicit pause
taking precedence, a subsequent minimal drag, and controls remaining responsive.
The expanded timeline cancellation was checked while playing and while paused,
with the preview deliberately far from the actual position. A separate
accessibility `ACTION_SET_PROGRESS` case committed its target while preserving
pause. Independent MediaSession samples
confirmed the paused native clock for both video and timeline drags; two
screenshots also confirmed identical held video pixels. A Home transition
during the hold cancelled the preview without a jump and continued in PiP.

The earlier `1661cbcf…` trial exposed the timeline cancellation bug and is
superseded. On that earlier build, an official local Syncplay server and a
passive desktop peer verified that an eight
second hold kept the phone's native position fixed while the peer received a
continuing logical clock without pause or intermediate seek commands. Release
committed the new position. The final fix changes only host Slider release
handling; the shared-playback controller is unchanged. This is not a test with
two physical phones.

The final build passed 93 selected app tests (68 room, 11 coordinator, 8 seek
feedback and 6 release-gate tests). The earlier consolidated build also passed
12 Syncplay tests including the official server and 8 concurrent game tests.
The preparation suite passed 14 tests. Signature, optimized ViewModel/Compose
ABI, JNI, 16 KiB alignment and attribution inventory checks passed. All 40
native libraries were byte-identical to the baseline. These inventory checks
do not establish overall source/licensing compliance.

Local-file playback, a physical Cast receiver, a second phone in a Nyanime room,
and the full text-size/reduced-motion device matrix are not
claimed by this run. No push, release or OTA was performed.
