# Telegram room conversations

This checkpoint adds optional private Telegram conversations to the existing watching
and reading rooms. It uses the same account and TDLib process as Cloud backups. Room
consent is separate from Cloud login. Playback synchronization still uses the existing
room transport; this checkpoint does not add Telegram calls or camera capture.

## Responsibilities

- `core/telegram`: serializable models, current TDLib wire requests, membership and
  join validation, spoiler-safe previews, outbox states and stable message identities.
  No Android player, UI, database, or content-provider dependencies.
- `TelegramEngineService`: owns TDLib in its existing isolated process. It forwards
  chat updates only for the negative chat IDs registered by the application.
- `TelegramClient`: shared account, IPC requests, native resource leases, ordered
  updates and explicit logout. Cloud and rooms do not close each other's leases.
- `TelegramRoomGroups`: recovers staged creation, verifies the private membership
  marker, prepares the control topic, and approves actual Telegram join identities.
- `TelegramRoomBridge`: projects the consented identities from the existing encrypted
  room, chooses a deterministic creator, and joins or reuses the exact membership.
- `TelegramRoomsRepository`: account-scoped local history, durable sends, drafts,
  pagination, read confirmations, reactions and reconnect recovery on IO dispatchers.
- `RoomMediaRepository`: bounded media IO and re-encoded photo uploads without EXIF.
  Only an explicitly opened attachment is copied into the FileProvider cache.
- `RoomNotePlayback`: one voice-note decoder at a time, deterministic release and
  generation checks which reject late callbacks. It never queries the movie player.
- `ui/rooms`: pure presentation components and thin account/repository bindings.
  Player, reader and standalone conversations reuse the same message and composer UI.

## Persistence and delivery

Room data lives in the application's private `noBackupFilesDir`, separately from the
library backup. Every row is scoped to an account. Logout clears that account's room
projection; a callback from the previous account cannot write a draft into the new one.

The local SQLite outbox is the send authority. A committed send and draft clearing are
one transaction. Native receipts replace the provisional ID while retaining the local
row key and order, so acknowledgement does not remount or move a message.

A definitive send failure and an interrupted request are different states. A request
that may have crossed IPC is marked uncertain after restart or timeout and is not
blindly resent. Rate-limited sends retain their retry deadline. The UI must not present
either an uncertain or a locally queued message as delivered.

Exact Telegram membership is hashed independently of names or member order. A changed
membership creates a separate conversation. New members are not added automatically
to the previous conversation. Leaving a conversation records a membership tombstone
to prevent the bridge from immediately rejoining it.

## Native conversation layout

The standalone conversation has one header and a local artwork card for the active
content. The artwork is looked up from the local library; it is not copied into the
room payload. Received messages use an open layout; outgoing messages have a subtle
accent surface. Replies, masked spoilers, chosen reactions and delivery states belong
to each message. Long names wrap or truncate without displacing timestamps.

The composer combines attachments, quick text reactions, reply context and a spoiler
toggle. Photo captions exceeding the Telegram limit remain editable and cannot be
sent. Selecting a sticker preserves the text draft. Images reserve their bounds during
loading and can be viewed and zoomed without changing the movie or reading page.
Animated stickers currently use their Telegram preview image when available.

The artwork card collapses when the keyboard opens. Message acknowledgement has no
placement animation. Existing `ModernMotion` tokens and reduced-motion preferences
govern conversation reveals and scrolling. Panels use a compact header without a
second content card. The player resizes its existing native view rather than creating
another player; PiP restores the normal view.

## Verification and remaining checkpoints

Unit tests cover membership, current wire requests, join-result handling, spoiler
ranges, update filtering, outbox interruption, media identifiers, row identity and
voice-note resource ownership. Native screenshot previews cover light/dark themes and
320 dp width with 1.5 font scale. Existing watching, reading and PiP unit tests also
remain part of regression verification. These are not a substitute for the planned
two-account real-device group and media tests.

For preview 0.32.0.0, local verification passed the 16 Telegram room contract tests
in both debug and release variants, 147 watching/reading/PiP regression cases, and
the three voice-note lifecycle cases. The three native chat previews also passed:
light theme, dark theme, and 320 dp width with 1.5 font scale. The planned two-account
physical-device tests remain pending.

The following approved checkpoints are still separate work: actionable uncertain-send
recovery, voice-note recording, animated sticker playback, minute/page links, mute and
participant controls, mixed Telegram room transport, and the official voice/video
engine with Android Telecom. No call buttons claim these capabilities prematurely.

No Firebase dependency or closed-app push is included. `RoomMessageAlerts` provides
the boundary for a later push adapter; current Telegram room reception requires the
application to be open. Playback never depends on chat or notification availability.
