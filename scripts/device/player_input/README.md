# Player input regression on a physical device

This optional, launcherless test APK targets its own package. It observes the
selected app through Android accessibility and MediaSession APIs and injects real
touch events. It does not restart the selected app, read its database or depend
on app classes, source names, domains or extension metadata. No test code is
included in the normal APK.

When the selected application has several active media sessions, pass the exact
technical tag with `-e sessionTag '"SESSION TAG"'` (PowerShell). Ambiguous session
selection fails before sending transport commands. The result records the selected
tag; a paused music session must never be mistaken for the video under test.

The matrix changes playback position and leaves playback paused. Open a test
video in fullscreen landscape before running it. It checks:

- tapping to show controls;
- native pause and a visible destination readout while dragging;
- commit and restoration of playing/paused intent;
- raw Android cancellation without committing the preview;
- an explicit pause overriding automatic resume;
- a minimal subsequent drag not inheriting an earlier target;
- controls remaining responsive after consecutive gestures.

Build with Python, JDK 17, Android SDK platform 36/build-tools 36.0.0 and a standard
Android debug keystore. The test requires Android 10 or later; the current
physical-device validation uses the recent Android API's accessibility-cache
refresh and explicitly selects display 0 on phones with multiple displays.

```powershell
python scripts/device/player_input/build_probe.py --sdk "$env:ANDROID_HOME" --java-home "$env:JAVA_HOME"
adb -s DEVICE_SERIAL install -r -t .local/player-input-probe/player-input-probe.apk
adb -s DEVICE_SERIAL shell am instrument -w -e applicationId APPLICATION_ID -e mode matrix nyanime.qa.playerinputprobe/nyanime.qa.PlayerInputDeviceProbe
adb -s DEVICE_SERIAL uninstall nyanime.qa.playerinputprobe
```

For an inline player, use `-e mode tap -e videoTop TOP_PX -e videoHeight HEIGHT_PX`.
These bounds limit control detection to the video, excluding similarly named
buttons in the page below. Instrumentation arguments are strings. Keep a
failing result from the previous APK and compare with the corrected APK using
the same probe and viewport. MediaSession and screenshot comparisons can be
used independently to confirm the native clock and frame stay fixed during the
hold. Shared-room and timeline tests are separate from this fullscreen matrix.

For an independently observed shared session, `-e mode shared -e holdMillis 8000`
runs only the playing/commit case with a long stationary hold after crossing the
drag threshold promptly. It reports wall-clock boundaries for comparison with a
passive peer. The probe does not create a room or connect to a server. A slow
whole-screen swipe that reaches the drag threshold after Android's long-press
timeout exercises the existing hold-speed gesture instead, so it is not a valid
scrubbing test. The normal matrix keeps its short hold.

`-e mode timelineCancel` locates the visible expanded timeline through its
accessibility bounds and injects cancellation there. It must preserve the
original playback position. This separate case matters because Material3 can
call its completion callback on cancellation without forwarding the Android
cancel event to `motionEventSpy`.

Add `-e initialPaused true` to test timeline cancellation from pause.
`-e mode accessibility` independently checks `ACTION_SET_PROGRESS`, after its
initial pause has settled. Its position check allows the existing approximate
keyframe seek policy; it does not change precision preferences in the app.
It records MediaSession states until the requested position has remained paused
for at least 600 ms, within a nine-second bound. A transient `BUFFERING` state
after position acknowledgement is not a resumed frame. Any observed `PLAYING`
state, unresolved buffering or position outside the three-second tolerance
still fails the check. `remainedPaused` and `settledAtTarget` are reported
separately so a keyframe mismatch is not confused with an unwanted resume.
Use the optional `-e seekFraction 0.20` to choose a target in the 0..1 range;
the default alternates 0.65/0.20 according to the starting position.

Run the probe with other app sheets and mini players closed. Another accessible
window over the video can hide the timeline from the probe; that is an
inconclusive viewport setup, not evidence that the renderer lost its timeline.

`-e mode doubleTap` separately captures both seek directions, then rapid direction
changes, with frames around the 800 ms dismissal. It leaves playback paused and
reports the actual capture times, not just the requested offsets. Pull its PNGs
from the test package's `cache/double-tap` directory with `adb exec-out run-as
nyanime.qa.playerinputprobe cat ...` before removing the probe. The mode records
visual evidence; its PASS result is not an automatic verdict about animation
quality. Inspect the frames for a disappearing label jumping sides, turning to
zero, overlapping the opposite direction or remaining after dismissal.

`-e mode diagonalScrub` starts a horizontal seek in the centre, then moves the
held finger vertically. The native position must stay paused and the seek
preview must remain visible; the presentation container must not take over.
`-e horizontalDirection -1` and `-e verticalDirection -1` independently reverse
the two directions. A held-frame screenshot is retained in the probe cache.
