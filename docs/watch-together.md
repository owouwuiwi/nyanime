# Watch and read together

Create a room, share its eight-digit code, HTTPS link or QR, and approve friends
as they arrive. The same room supports episodes, independent manga reading and
live TV, with up to eight people. No account, video upload or router configuration.
Each participant opens **their own stream** through an installed, trusted extension.
The owner selects the content. References remain opaque; resolved stream URLs,
cookies and credentials never become room messages. A missing extension is an
explicit error, never a silent substitution. The website opens Android or offers
the app download. Old invitations and custom-scheme links remain supported.

## Continuity

Confirmed playback intent and the logical room clock are separate from every
decoder, including the owner's. After the initial shared start, buffering pauses
only that person's playback. When ready, they catch up locally. **Wait for everyone**
is an explicit owner option. Drift within one second does not trigger correction.
Filtered speed changes stay within three percent. Persistent drift above three
seconds permits a local seek, with a cooldown; very large drift and explicit seeks
have separate recovery paths. The optional initial cache preparation runs in
parallel for at most fifteen seconds, never an increasing wait per participant.
Missing buffer estimates have a shorter grace period. This cannot make an
unavailable stream playable or guarantee frame-exact synchronization.

Explicit play, pause, seek and speed remain shared and host ordered. Timers, local
safety pauses and audio-focus loss cannot be undone by remote Play. Unknown or
transient native duration is not evidence of a different edition: mismatches need
stable known durations. The owner alone advances episodes, with existing autoplay
and timer checks. Guest preparation blocks advancement only with Wait for everyone.
Manga retains independent navigation and annotations. TV retains local buffering
and pause. Anime4K stays unavailable while a room is active.

The controller outlives activities but never accesses a detached native player.
Outside a room it does not alter solo playback or audio routing. Chat, voice and
room control are independent of eye control and its lifecycle.

## Lounge, chat and voice

The native lounge adapts Piik's living-room idea: seats, names, loading and
microphone status, invitations, voice and chat. Chat opens below in portrait and
at the side in landscape. Reading older messages never forces a scroll to the
latest. Optional video message overlays start disabled; reactions can be disabled.
The player opens the conversation directly, with compact microphone and options
controls in one header. Its heading opens participants and invitations. The panel
remains open when transport controls hide, does not pause, resize or darken the
video, and preserves the draft and chat position when closed. Portrait leaves more
room above the conversation; landscape uses an explicitly measured side panel.
Reduced motion is respected. Chat keeps at most 200 messages of 1,000 characters
in memory. Leaving clears chat and drafts; backups exclude them. Delivery has
acknowledgements, duplicate suppression, bounded retries and a failed/retry state,
even when disconnected before the first send. New participants receive no history.

**Join voice** connects audio only with the microphone initially muted. Permission
is requested only when enabling it. Conversation volume is separate from video.
The room notification provides mute and leave controls. Voice uses Android's media
output without changing the global audio mode or selecting the telephone earpiece.
Leaving releases native audio. No camera, screen capture or recordings.

WebRTC initializes only after joining voice. Up to seven peer links use public
STUN discovery and DTLS-SRTP. Healthy links remain while failed ones retry with
finite budgets; connection leases and per-join session IDs reject obsolete callbacks.
Restrictive networks can prevent direct audio. Voice then reports unavailable
while reachable room control and chat remain usable. No paid TURN or proprietary
signaling server. Voice peers and STUN services can observe IP addresses. Muting
disables microphone capture, not the connection. OS microphone indicators and
background-service notification remain visible. Audio effects and Bluetooth
behavior depend on device support; no universal routing guarantee is implied.

## Transport, privacy and admission

Control uses [Nostr NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md),
outbound TLS WebSockets and signed ephemeral kind 20971 events. This is a relay
architecture, not pure P2P. Availability and rate limits are external dependencies.
Reconnects preserve working subscriptions. Commands have a separate monotonic lane,
acknowledgements and bounded retries. Followers pause on owner loss rather than
electing competing owners. Room generations reject obsolete callbacks.

Each identity is ephemeral. The invitation contains a 128-bit random secret and
96-bit owner-key fingerprint. Schnorr signatures use ACINQ's Apache-2.0 secp256k1
bindings. Control envelopes use AES-256-GCM, fresh 96-bit nonces and domain-separated
authenticated data. Relays see IPs, room tags, authors, timing and ciphertext sizes;
ephemeral delivery does not guarantee that a relay retains no copy.

Chat, presence and audio negotiation add a pairwise envelope: secp256k1 ECDH,
RFC 5869 HKDF-SHA256 and AES-256-GCM. Keys and AAD bind the room, fresh owner
interaction session, authenticated roster and both endpoints. Text and presence
remain owner ordered; admitted voice peers negotiate directly through encrypted
relay events. This is an application protocol, not NIP-17 or NIP-44.

Removal updates the roster/key context and closes that participant's voice links
on clients receiving the update. The former member cannot decrypt new pairwise
messages. Admission becomes locked after a removal, including for new identities
holding old full invitations. Already received messages cannot be remotely erased,
and offline peers cannot receive instant removal notifications. Optional features
are negotiated independently of coordination version 2 for older clients.

Rooms expire after 24 hours. Existing crash recovery stores invitation, identity
and reading state encrypted with Android Keystore in backup-excluded storage.
Explicit leave, closure or expiry removes it. Chat and voice never silently resume
after process death. Cloud backup/sync is independent of rooms.

## Attribution and verification

The lounge and bounded-recovery design are inspired by
[Piik](https://github.com/TNTcraftHIM/Piik), MIT, Copyright 2026 TNTcraft and Piik
contributors. Piik's desktop backend, SFU and host-video routing are not included.
The voice binding is isolated in `core/room-voice`, with pinned provenance, native
version and licenses bundled and accessible in the app.

JVM tests cover logical clocks, local buffering, wait-all, duration stability,
commands, stale messages, crypto, bounded chat, roster changes and audio-only signal
validation. `NYANIME_WATCH_NETWORK_TEST=1` enables synthetic relay exchanges with
no sources or user credentials. Build and simulated-network tests do not replace
physical-device proof. Actual device results and limits are recorded separately.
