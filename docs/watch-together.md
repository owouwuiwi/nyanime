# Guarda insieme

The Home group button and the player's More sheet open the same room interface.
Create a code, share it, and let friends enter it in their Home. The creator chooses the title and
episode. Guests resolve that catalog entry through their own installed, trusted extension and the
normal player loader. No video is relayed and no resolved hoster links, request headers or cookies
are sent. The same usable extension is required for automatic opening; a missing source is reported
with a retry action instead of silently starting a different title.

## Connection and privacy

The transport implements Nostr NIP-01 using ephemeral kind 20971 events, a random x room tag,
and outbound TLS WebSockets to two independent relays. This is a decentralized relay architecture,
not pure peer-to-peer and not serverless. It needs no application account, Nyanime backend,
incoming port or router configuration. Public relay availability remains an external dependency.
Subscription readiness, publish rejections, reconnect backoff and duplicate delivery are handled.
An explicit custom relay list is supported by the invitation codec and controller.

The compact NY1. code encodes a 128-bit random secret and a 96-bit fingerprint of the creator's
ephemeral public key. It is a capability: sharing the code admits someone into the room.
Every participant has a separate ephemeral Schnorr identity. Full event signatures are verified
with ACINQ's Apache-2.0 Kotlin bindings to Bitcoin Core libsecp256k1; the creator's fingerprint
pins the authoritative sender. The AES-256-GCM key is domain-separated from the secret,
with fresh 96-bit nonces and room, author and timestamp bound as authenticated data.
This is an application envelope, not NIP-44 messaging.

Relays can observe IP addresses, random room tags, event authors, timing and ciphertext sizes.
They cannot decrypt room metadata. Ephemeral events are not expected to be stored by compliant
relays; this is not a storage guarantee. An active room expires after 24 hours. To allow explicit
re-entry after a crash, the app temporarily stores the invitation, signing identity and reading
annotations encrypted with an Android Keystore key in a file excluded from backups. Explicit
leave, closure or invitation expiry removes this local state. Playback never resumes by itself.

## Playback ownership

The creator orders shared play/pause/seek/speed requests and broadcasts complete snapshots.
Commands have a separate monotonic sequence lane from presence messages, with acknowledgements
and bounded retries. Newer commands win; late older seeks cannot reverse a newer seek.
Room generations reject callbacks from closed connections. Guest observations never become commands.

Playback intent is separate from effective pause state. Buffering pauses the group by default
without losing the intent to resume; pressing Pause while waiting cancels that intent for everyone.
Old-episode readiness cannot release a new episode. The creator can disable shared controls or
waiting for all participants. There are at most eight participants including the creator.

Clock probes use monotonic timestamps and low-RTT samples. Followers extrapolate the authoritative
position and use a deadband, bounded 3% speed corrections and seek cooldowns. Explicit seeks carry
a revision and bypass the cooldown once. Temporary corrections do not change the chosen base speed
or repeatedly reset Anime4K calibration. Different-duration editions stay paused with an explanation.

Sleep expiry, audio-focus loss and backgrounding create a local safety hold. A remote Play cannot
clear that hold; an explicit local Play is required. Guest autoplay cannot pick its own episode.
The host's existing next-episode flow chooses the next catalog reference and guests open it
automatically. Existing native HLS, buffering recovery, decoding and Anime4K algorithms are unchanged.

The application room owner outlives player activities. A foreground connected-device service keeps
room messaging alive while browsing, with an explicit leave notification. Native player attachment
and detachment are explicit; stale source-resolution results cannot open an older selection.
Source catalog references are constrained to the installed source's origin before a remote fetch.
If an extension refreshes an episode URL, the uniquely resolved local entry is bound to the host's
selection. Its actual duration is still checked, and another local episode cannot inherit that binding.
When the creator disappears, followers pause and reconnect; they do not elect competing creators.
Leaving a room restores the original speed and keeps local playback paused.

Unavailable native duration or position keeps the attachment unready. Opening and closing do not
query native timing before the file is usable. Detachment preserves only an inactive cached sample;
it never reads a released player, including in solo playback. Inactive room controls do not sample
or modify the native player.

## Verification

WatchRoomTest exercises clocks, shared commands, lost/reordered messages, readiness barriers,
local holds, episode changes, creator loss, closure, room limits and stale callbacks.
WatchCryptoTest covers authenticated envelopes, invitation parsing, tampering, expiry,
foreign origins and malformed fields.

NostrWatchTransportTest is opt-in through NYANIME_WATCH_NETWORK_TEST=1.
Two independent clients exchange synthetic encrypted events in both directions through each
configured default relay. It does not send user content or credentials.

Compose screenshots cover entry, active-room, error, portrait, landscape and large-text layouts.
Host-side tests and renders do not replace two-phone playback validation.

## Coordinated viewing

Coordination version 2 adds shared start deadlines, skip cues and next-episode preparation.
Both phones need this version; an older participant produces an explicit update message.
The host remains authoritative and all commands retain episode identity, sequence and acknowledgement checks.

After a pause or buffering, readiness must remain stable for one second before the host announces
a two-second countdown in its monotonic clock domain. A new buffering event, manual pause,
local hold or lost connection invalidates that deadline. Guests use their measured clock offset
to start against the same deadline. Network delay still prevents a universal frame-exact guarantee.

Play immediately transforms into an animated play/heart/circle glyph while the request awaits
acknowledgement or the readiness barrier. The same central control displays the room's countdown
with a short caption over the video, without a separate preparation card. Tapping it again sends
Pause when shared controls permit it. The button keeps its original footprint; animation runs in
drawing only, stops outside preparation and respects reduced motion and control locking. Menus
and picture-in-picture suppress the shared animation. Unconfirmed requests retain the existing
retry policy; all countdown numbers come from room state, never an independent animation timer.

Preparation shows participant initials and readiness, with a caption naming the friend still loading.
After fifteen seconds the caption opens room details while the central Play/Pause remains usable.
Unconfirmed commands expire after eight seconds even when every relay is offline. An explicit retry
uses a fresh command sequence, checks the original episode and preserves local safety holds.
Late acknowledgements clear the error. A connection retry refreshes unavailable relay sockets only,
is throttled to once every two seconds and leaves usable subscriptions intact.

Optional activity metadata in the host's timeline attributes manual play, pause, seek and speed
changes. It is presentation only, remains compatible with coordination version 2, expires after
3.5 seconds using the measured clock offset and is not replayed by duplicate/reconnect snapshots.
Automatic skips and drift corrections do not impersonate a participant's manual action.
The caption never forces the playback controls open and stays hidden behind menus and in PiP.

An active-room minibar on browsing and detail screens shows the selected episode and participant
count. One tap opens that episode through the existing guarded resolver; the group button opens
room details. It reads room snapshots only, never a detached native player. Modern player surfaces
also use a compact skip cue, smaller next-episode card and animated solo loading glyph. Legacy
styling, reduced motion, skip cancellation, autoplay and sleep-timer rules are preserved.

Anime4K is unavailable for the entire time a room is active, including local holds and reconnects.
Entering a room removes its shaders and stops Smart telemetry; quick presets, custom presets and
diagnostics are hidden. Solo episode choices and the automatic Smart preference are preserved.
Leaving the room restores the current episode's solo choice, with a fresh Smart measurement.

Follower corrections filter timing error, use separate enter/exit thresholds and gradually vary
speed within three percent. Brief jitter does not trigger seeking. Persistent large errors and
explicit seeks retain bounded recovery paths. Native speed callbacks recognize recent managed
values so delayed callbacks do not overwrite the user's base speed or recalibrate rendering.

Only the host offers automatic intro/ending skips. The shared cue has an identity, target and
optional deadline. Guests can skip or cancel through shared controls; duplicate or obsolete cue
commands cannot trigger a second skip. Pausing suspends the countdown and cancellation remains
effective for that segment. Solo playback keeps its existing local skip behavior.

Within the last ninety seconds the host announces the next catalogue entry. Guests prepare one
bounded local catalogue reference, without resolving or downloading the stream. At EOF both
phones show the same next-episode card. Preparation and local safety checks gate the shared
ten-second countdown; cancellation applies to the whole room. The host alone changes episodes,
after which the normal player loader and readiness barrier apply again. Sleep timers and local
holds remain authoritative. Source failures expose retry or extension actions.

Invites include an Android deep link and a locally generated QR using ZXing core (Apache-2.0):
https://github.com/zxing/zxing . Opening a link validates the exact route and asks the user to enter;
it never silently replaces an active room. The entry activity respects the existing app lock.
The compact code remains available for messengers that do not make custom-scheme links clickable.

Protocol reference: https://github.com/nostr-protocol/nips/blob/master/01.md

Cryptography implementation: https://github.com/ACINQ/secp256k1-kmp
