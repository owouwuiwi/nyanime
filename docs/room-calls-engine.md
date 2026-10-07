# Room call engine

Room calls are optional and separate from the playback and Telegram account processes.
No microphone, camera, native call library or audio route is initialized until the user joins.

The first Android adapter uses NTgCalls 3.0.2, pinned to the published Maven AAR SHA-256
f069a1bd2f693261f4276c610078e0edb07cb6fc46d4509634ba723e6bdae181.
It supplies an Android SDK, camera capture, received video frames and group-call transport.
TDLib remains the account and Telegram signaling client.

The upstream Java binding releases its instance in a finalizer. Nyanime replaces only that
generated binding with an AutoCloseable binding. close() calls the same native destructor
explicitly and idempotently; there is no finalizer. The call service serializes all native
operations and invalidates callbacks before destruction. The native library is unchanged.
Both the modified binding source and LGPL license are included in the APK assets.
The upstream consumer rules are retained, including the WebRTC and JNI entry points.
Optional Dav1d/Logging bindings omitted by the upstream Android artifact are not enabled.

Native calls run in :roomcalls, not the native player process. Native video frames are rendered
there onto Surface handles supplied by the UI. Frames do not cross Binder. Disconnection from
the service and process death end the local call and leave the room and playback intact.
Each visible video surface owns an independent slot. Remote video subscriptions follow
those slots, and closing a pane releases only its own surface. Capture starts muted with
the camera off; background, system mute and hold stop capture before network signaling.
Camera capture starts at 640 x 360, 20 fps. Audio endpoints belong exclusively to Core-Telecom.

Playback synchronization continues through the existing Nostr room transport. Telegram
signaling, chat and calls never become another authority for pause, seek or playback time.

Upstream sources: https://github.com/pytgcalls/ntgcalls/tree/v3.0.2
The official tgcalls engine remains an alternative behind the same adapter boundary.

Acceptance requires two-account audio/video tests, permission refusal, headset routing,
rotation, background camera suspension, reconnect and deterministic release.

## Chat and presentation

The player keeps its existing native video surface above the room workspace, in both
orientations. One action row contains chat, microphone, camera, reactions and session controls.
In landscape, camera tiles share the lower workspace with messages; they do not displace the
movie into a sidebar. Keyboard insets preserve the composer and reduce the workspace header.
Message previews can be hidden independently of muting conversation notifications.
The reader uses the same composer, previews, reactions and call controls without forcing a
shared reading position. A scene or page link always requires an explicit navigation action.

Reference projects: Android's [Jetchat](https://github.com/android/compose-samples/tree/main/Jetchat)
for input/focus/IME handling, and [LiveKit Components Android](https://github.com/livekit/components-android)
for compact participant and visible-track presentation. No LiveKit server or SDK is added.
Motion uses Nyanime's existing ModernMotion tokens and reduced-motion setting.

## Verification, 8 October 2026

- 39 core Telegram tests, 56 room controller tests and the native lifetime guard test passed.
- Two authorized Telegram accounts exchanged messages in both directions through a recovered
  room group on real Android devices. Preparing a chat no longer remains unbounded.
- The signed and shrunk preview APK passed three create/stop/explicit-close JNI rounds.
- The isolated foreground call service produced its join payload and stopped on a physical
  Android device, with capture disabled and the temporary audio permission restored afterwards.
- Both authorized accounts joined the same Telegram group call with their native engines.
  Each reported a connected transport, joined state and two participants. This test used no
  microphone, camera or audio playback device; it verifies signaling and transport, not media quality.
- The following device iteration accepted microphone and camera changes on both phones.
  Native playback audio uses the incoming call track, and speaker/headset selection is
  requested through Telecom. A camera-only change no longer rebuilds the audio device.
- Full conversation keyboard positioning, delivery status details, single-player playback and
  the existing PiP path were inspected on the connected phone without a fatal error.
- Measured two-account voice/video quality, Bluetooth routing and camera background
  suspension remain pending. A connected transport or accepted camera toggle alone
  does not establish these results.

This release is a preview. No claim of end-to-end media quality or measured frame-time
improvement is made from unit tests or the isolated native probe.

Automatic film ducking, separate film/call volume controls and adaptive camera quality beyond
the initial capture limit and visible-track subscriptions remain later work; they are not
presented as delivered by this preview.
