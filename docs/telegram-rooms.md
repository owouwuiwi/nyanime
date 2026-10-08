# Telegram room conversations

This checkpoint adds optional private Telegram conversations to the existing watching
and reading rooms. It uses the same account and TDLib process as Cloud backups. Room
consent is separate from Cloud login. Playback synchronization still uses the existing
room transport. Optional calls use the isolated adapter described in
[Room call engine](room-calls-engine.md).

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
Room sends use a local account/chat/send correlation key so a late TDLib response can
still bind the real message ID to the durable outbox. Reconnect also reads known
pending message IDs without resending them. A failure before dispatch may safely retry.

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

Chat setup is also exposed directly in watching and reading room controls. The
player chat icon remains visible before group creation and opens that setup when
the conversation is not ready. Account login, separate room consent, participant
readiness and group preparation have explicit states. Direct chat navigation only
accepts a conversation registered for the current consented account.

The active session card exposes Close room to every admitted participant, with confirmation.
The technical Nostr owner orders that command without giving different playback controls
to guests. Closing the session queues removal of every conversation used during it.
The Telegram group creator deletes the verified session group for everyone; other members
leave and remove their local Telegram history. The account-scoped cleanup queue survives
network loss and process restart. Only a private group with its expected membership
marker is eligible; backup archives and unrelated chats are excluded.
Ended groups are tombstoned and cannot be recovered as a new session's conversation.
An explicit chat departure applies to the current session, not all future rooms.
Changing or ending the room dismisses an outstanding confirmation; the hub also
rejects an action targeting a previous room identity.

The signed 0.32.0.3 update was tested on a physical Galaxy Z Flip6 with a temporary
single-participant room. The owner action was visible in the hub; its confirmation
explained that Telegram messages remain available. Cancel kept the room active,
and confirming Close room returned to the idle hub. Three existing controller
regressions passed: owner closure, participant departure after disconnection, and
late events from a previous session. No new application crash was recorded during
the device check. This does not replace the pending two-account physical-device
conversation tests.

Preview 0.32.0.2 corrects the isolated Telegram application's configuration callback:
rotation, locale and night-mode changes must not resolve the main process's theme
dependencies. The real-device IPC probe has a configuration-change mode with four
rotation rounds and 200 native responses, without requesting a login or sending
messages. Local watching, reading, optional Telegram metadata, PiP geometry and
voice-note tests passed (89 cases).

The signed local 0.32.0.1 build was installed as an update on a physical Galaxy
Z Flip6, preserving the existing account and room. Changing the system font scale
reproduced the original `ThemeController` dependency crash in 0.32.0.0. The same
change after the fix kept the Telegram process alive with the same PID; the original
font scale was restored. The four-round configuration IPC probe also passed with
200 confirmed native responses. The recovered room exposed the Chat setup card;
the other participant was offline, so this test did not verify two-account group
creation or message delivery. The publication version is 0.32.0.2, with a higher
Android version code and the simplified “Watch together” title.

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

Voice-note recording and animated sticker playback remain separate checkpoints.
Minute/page links, mute and participant controls are implemented. Optional voice/video
uses the isolated adapter documented above; playback synchronization remains on Nostr.
An old uncertain send without a native message ID is never guessed to have been delivered.

No Firebase dependency or closed-app push is included. `RoomMessageAlerts` provides
the boundary for a later push adapter; current Telegram room reception requires the
application to be open. Playback never depends on chat or notification availability.

Preview 0.32.0.7 passed 1,172 app unit tests (7 excluded), 43 core Telegram tests and
18 release/versioning tests. Its signed APK was installed on the physical Galaxy
Z Flip6. Room playback resumed, and the landscape workspace appeared below the film.
The second phone was no longer reachable; fresh two-account reaction delivery,
call media quality and server-side group deletion remain part of the remote trial.

Preview 0.32.0.8 was installed on the same physical phone. In landscape, room
controls use a separate full-window modal instead of inheriting the smaller film
viewport. Its close button stayed visible while the content scrolled, Back returned
to ongoing playback, and Close room restored the inactive session controls.
The preview retained all 1,179 app test cases, with 7 excluded and no failures.
