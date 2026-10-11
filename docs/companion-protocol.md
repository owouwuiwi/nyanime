# Nyanime Companion v1

An app running on the TV can act as a Cast receiver without Google Cast or DLNA.
The Android sender uses the existing relay, foreground service, remote controls,
episode queue and progress writer. Source resolution remains on the phone. The
webOS app retains its independent catalogue and playback mode.

## User flow

1. Open Nyanime on the TV and a video on the phone, on the same IPv4 LAN.
2. Open Cast on the phone and select the receiver marked **App Nyanime TV**.
   If multicast discovery fails, use **Collega app TV** and the address shown by
   **Collega telefono** on the TV. No router port forwarding is required.
3. Compare the six digits on both devices and approve on the TV. A differing code
   must be rejected. The request expires after two minutes.
4. Use the phone remote or browse another title. The phone resolves new episodes
   and supplies its temporary relay URLs. Both phone and TV controls report state.
5. Stop, return to the phone, or disconnect on the TV. The TV returns to its Home.

The TV app must stay open; this protocol does not wake a powered-off TV. The phone
must remain connected because it supplies the relay. Guest network isolation can
prevent both discovery and streaming. Pairing is per session; no passwords,
cloud accounts, source headers or persistent pairing keys are exchanged.

## Portable receiver contract

Implement this contract in a future TV app to appear in the same Android picker.
The receiver core and player adapter must be separate. No model-specific routing
or catalogue logic is required in Android. Currently the TV adapter is webOS;
other platforms still need their own receiver and native decoder validation.

- HTTP on the receiver's LAN IPv4 interface, port **38473** by default. Advertise
  a different port when the default is occupied. Paths begin `/nyanime-cast/v1`.
- SSDP M-SEARCH target: `urn:nyanime:device:CompanionReceiver:1`. Respond with that
  ST, a UUID USN and LOCATION `http://<receiver-ip>:<port>/nyanime-cast/v1/info`.
  Android accepts LOCATION only on the responder's own address and exact path.
- `GET /info`: `{protocol:"nyanime-cast",version:1,id,name,capabilities}`. IDs are
  16 random bytes as 32 lower-case hex digits; keep one ID for the service lifetime.
  Names are non-empty, at most 100 characters, without control characters.
- Capabilities: `{formats:["hls","mp4","webm","mkv"],volume,brightness,subtitles}`.
  Advertise only implemented controls and supported containers. Codec support is
  still constrained by the TV. Unsupported volume/brightness controls are hidden.
- POST requests and replies are JSON, bounded to 96 KiB; redirects are forbidden.
  Reject browser Origin headers and Host values other than the local interface
  and port. Bind only while the receiver UI is available. These are native app
  APIs, not a browser-accessible remote control.

## Pairing and authenticated channel

The protocol uses platform cryptographic primitives, P-256 ECDH and AES-256-GCM.
The short code authenticates the exchange through the user's comparison; it is
not an encryption key. Both peers commit before either reveals its public key.
There has been no independent cryptographic audit of this new protocol.

All strings use UTF-8. Byte strings use canonical padded standard Base64. Public
keys are SEC1 uncompressed P-256 points (65 bytes beginning with 04); nonces are
16 random bytes. `L = "nyanime-cast/1"`.

1. Phone generates its ephemeral key and nonce. `POST /pair` contains version 1,
   receiverId, phone name (1–64 characters, no controls), and commitment:
   `Base64(SHA256(L + "/commit|" + publicKey + "|" + nonce))`.
2. Receiver generates an ephemeral key, nonce and random sessionId. Reply contains
   version, receiverId, sessionId and its own commitment, without revealing key
   or nonce. Allow one pending/approved owner and at most five attempts per minute.
3. Phone `POST /reveal` contains sessionId, publicKey and nonce. Receiver verifies
   the commitment and replies with version, receiverId, sessionId, publicKey and
   nonce. Phone verifies IDs and receiver commitment. Reject changed reveals.
4. Join with a single newline and no trailing newline: L, receiverId, sessionId,
   phone name, client publicKey, server publicKey, client nonce, server nonce.
   This is the transcript. Obtain the 32-byte ECDH shared secret.
5. Salt is SHA256(transcript); PRK is HMAC-SHA256(salt, shared secret).
   Each directional key is HMAC-SHA256(PRK, UTF8(L + "/" + purpose) || 0x01),
   with purposes `client`, `server` and `verification`.
6. Code: HMAC-SHA256(verification key, transcript), first four bytes as unsigned
   big-endian integer, modulo 1,000,000, padded to six digits. Display on both UIs.
   Only the TV UI may approve; approval is never an HTTP command.
7. Phone polls encrypted `pairStatus` every 500 ms, receiving `{ok:true,approved}`.
   It can send encrypted `cancelPair` while no media has been loaded, including
   cancellation racing with approval. Other commands require approval.

The encrypted endpoint is `POST /session/<sessionId>` with `{seq,data}`. Sequence
is a strictly increasing integer from 1 to 2^53−1. AES-GCM IV is 12 bytes: four
zero bytes followed by seq as unsigned 64-bit big-endian. AAD is UTF8 of
`L + "|" + sessionId + "|" + direction + "|" + seq`. Encrypt JSON payload bytes
with the directional key; data is Base64(ciphertext || 16-byte authentication tag).
Replies use the same seq and direction `server`, requests `client`.

Cache the last exact encrypted request and response. An identical retry must
return the cached/in-flight response without executing twice. Reject lower
sequences, altered same-sequence requests and different requests while a command
is pending. Sender serializes commands and reuses the exact envelope on retry.
Authenticated traffic renews the owner lease; after 60 seconds without it, stop
playback and release ownership. Closing the UI/service also releases ownership.
Temporary discovery identifiers and keys are never persisted or logged.

## Media and commands

Encrypted replies use `{ok:true,...}` or `{ok:false,error}`. Errors are short
user-readable strings. Command execution requires acknowledgement from the current
player UI, within eight seconds. Android waits up to ten seconds for controls.

- `load`: `{type:"load",media:{id,title,episode,url,mimeType,positionMs,durationMs,subtitles}}`.
  Media ID is 32 random lower-case hex digits. Times are finite milliseconds in
  0–604800000; strings title/episode are at most 300 characters, no controls.
  MIME: video/mp4, video/webm, video/x-matroska, application/vnd.apple.mpegurl or
  application/x-mpegurl. Subtitles: up to 16 `{name,url}` external WebVTT tracks.
  Each URL must be HTTP on the paired phone's numeric IP and explicit relay port,
  without credentials, fragment or whitespace; max 8192 characters. Never fetch
  a source URL or accept a URL to an unrelated LAN device.
- `pause`, `seek`, `volume`, `brightness`, `subtitle`:
  `{type,mediaId,value}`. Pause is Boolean; seek is milliseconds; volume and
  brightness range 0–1; subtitle is a zero-based index or −1 for off.
- `stop`: `{type:"stop",mediaId}`. Release the decoder, revoke control and return
  to the autonomous TV UI. Old session status may report stopped for cleanup.
- `status`: `{type:"status"}` → `{ok:true,playback}`. A local TV disconnect
  also revokes mutations and reports stopped. Re-pair to regain control.

Playback contains mediaId, positionMs, durationMs, paused, buffering, finished,
canSeek, volume, brightness, subtitleIndex, stopped and optional error. It must
describe the native player, not merely echo requested playback positions. Keep
the last valid position during buffering. Report finished only after actual EOF.
Reject controls for a previous mediaId, even when still from the same phone.

The webOS adapter uses HTML video volume and an overlay to dim the video; it does
not change the TV's system/backlight setting or amplify beyond its hardware
volume. Volume and dimming persist when replacing an episode in the same session.
No transcoding, screen mirroring or Anime4K processing is performed on the TV.

## Verification

`CompanionCryptoTest` and the TV `companion.test.ts` share a cross-language vector.
Secret bytes 00..1f, transcript `nyanime-cast/1\nvector` yield code 109905 and client
key `091658ee5f58a8d04ad5d746c4185026d97db3fd07e9dda0fb2a10465a46d449`.
Encrypt `{ "type": "status" }` serialized without whitespace, session 32 `a`s,
direction client, seq 4294967297: data `PC2sJNkzJtY9SDoscnrpuSQxq1ui8OWD1f96ZVDuYSvd`.

For live JVM/TypeScript interoperability, run `node scripts/companion-fixture.mjs`
in the webOS checkout, read `build/companion-fixture-url.txt`, set that value as
`NYANIME_COMPANION_TEST_URL` for Gradle, then run
`:app:testPreviewUnitTest --tests '*CompanionInteropTest'`. The fixture binds only
loopback, approves a synthetic sender and acknowledges simulated media. The test
also discards a load response to check safe idempotent retry. Without the fixture,
this integration test is skipped; regular cryptographic tests still run.

Real LG checks are separate from JVM tests. They cover pairing display/approval,
SSDP discovery, native MP4 start/resume/seek/pause, second-media replacement, EOF,
volume, subtitle selection, dimmer state and return to Home. Inspector screenshots
do not capture the TV's hardware video plane, so they do not establish visual
picture quality or dimming accuracy. HLS/codec combinations, long playback,
phone screen-off and an Android physical-device session require further checks.
