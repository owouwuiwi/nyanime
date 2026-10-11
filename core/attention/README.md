# Viewing attention

Optional, local front-camera observations for the host native player.
Original code uses `it.lorelore.nyanime.attention.core`. There are no provider
names, network requests, extension parsers, identity recognition or saved images.

`AttentionPolicy` is independent of Android and consumes timestamped evidence
plus an opaque playback epoch. Adapters issue explicit pause/resume/seek commands;
external commands revoke the policy's authority. Unknown observations never
initiate automatic playback changes. `FaceGeometry` measures eyes in a face-local
frame, so head roll alone does not change the measurements.

`AttentionCamera` owns only its analysis/optional preview use cases, never unbinds
other CameraX consumers and closes every image. It runs CPU inference off the UI
thread at no more than five samples per second, with bounded backpressure and
stale-result fencing supplied by the visible host. No camera foreground service
is added. The host must stop it outside the visible, permitted playback context.

The pinned model is checked before builds. `src/main/assets/attention` contains
the original Apache-2.0 license, provenance and modification notice. The Android
host transforms the SDK statistics factory to the provided no-op implementation;
consumers of this module must apply the same privacy transform before distribution.

Run `:core:attention:testDebugUnitTest`; app storage/backup tests are in
`it.lorelore.nyanime.attention.AttentionPreferencesTest`. Physical gaze accuracy
and performance need separate real-device validation. See
[the integration guide](../../docs/viewing-attention.md).

The module consumer rules preserve MediaPipe JNI/protobuf contracts and Flogger
stack frames required by `forEnclosingClass`; see [upstream issue #6138](https://github.com/google-ai-edge/mediapipe/issues/6138).
The unused SDK statistics implementation stays outside that keep rule; the app
still verifies its local no-op factory and removal of the old transport logger.
Failures emit one local diagnostic per camera instance, without frames or face
measurements. An unavailable detector leaves playback under manual control.
