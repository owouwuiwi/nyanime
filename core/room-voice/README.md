# Optional room voice

Audio-only Android WebRTC adapter. No player, source-extension, profile, eye-control,
camera, video-capture or screen-capture dependency. The application creates this
engine only after explicit Join voice; microphone capture starts disabled. Pure
signal models do not initialize the native library. One serialized worker owns all
connections and releases; stale callbacks are rejected by connection identity.

Pinned artifact: `io.github.webrtc-sdk:android:144.7559.15`.
Upstream binding: https://github.com/webrtc-sdk/android (MIT).
Native WebRTC revision: `30d5e63ae91da483e06577b5c35ee91cc5e5c3db`.
Third-party notices from binding revision `f0252bec67ad90432a77dce9af8041e3717c57ed`.
The artifact is used unmodified. `consumer-rules.pro` preserves both the
`org.webrtc` and `org.jni_zero` JNI entry points, including classes loaded directly
by `JNI_OnLoad`. Release validation checks actual DEX class definitions against
the native distribution manifest; Java string references alone do not pass.
The 64-bit ELF load segments were inspected for 16 KiB alignment; the 32-bit
libraries retain their normal 4 KiB alignment.

`src/main/assets/licenses` bundles the MIT binding license, WebRTC BSD license,
patent grant and full third-party notices. The app exposes them in Library licenses.
These licenses apply separately from the app's license; the notices do not claim
ownership of the upstream components.

Nostr supplies encrypted, pairwise signaling between admitted room members.
WebRTC carries DTLS-SRTP audio directly, with public Google and Cloudflare STUN
discovery. No TURN service, SFU, hosted backend or video forwarding is included.
Restrictive NAT/firewalls may make voice unavailable. That failure is separate
from chat and synchronized independent streams.

The factory uses a Java audio device module, system-supported processing and
echo/noise suppression constraints. It does not request audio focus from the
video player. Communication mode is scoped to an enabled microphone and restored
on mute/leave. Per-conversation volume is applied to received audio tracks only.
OS routing, headsets and Bluetooth must also be verified on physical hardware.

With hardware capture disabled, this SDK's external recorder fills silent PCM
without blocking on AudioRecord. `RoomVoiceCaptureClock` supplies the required
10 ms cadence through its `AudioBufferCallback`; the callback also makes the SDK
forward a complete buffer. It drops missed silence frames after suspension instead
of bursting through the native mixer, and preserves hardware timestamps without
adding latency while the microphone is enabled. Each recorder thread owns its
clock. Muting never relies on keeping the hardware microphone recording silence.
