# Private Cloud synchronization

Preview implementation: optional personal synchronization is available through Cloud's Sync tab.
Two-device delivery/latency is a separate acceptance gate; compilation and unit tests do not
establish it. The existing backup archive remains available independently of synchronization.

## Boundaries

Remote maintenance is implemented in `it.lorelore.nyanime.cloud.sync`. It uses no site,
title, provider or extension rules. The rebuildable coverage index is private operational
state, outside portable backup preferences. No new dependency, image model or service is added.

### Remote history maintenance (0.52)

- Two distinct signed commits and every encrypted part are read through the Telegram transport
  and authenticated before a deletion plan can run. Parts are indexed transactionally, one
  per bounded pass. A checkpoint/field index proves coverage using exact causal registers,
  not equal positions, title similarity, timestamps or probabilistic membership tests.
- Data packets must be at least seven days old and causally covered by both checkpoints.
  Unrepresented concurrent/offline edits, privacy-excluded fields, pins and unknown content
  remain. Root, control, pairing and recovery messages are never garbage-collected.
- Old checkpoint parts are eligible only after every current member has signed an optional
  `maintenanceVersion=1` checkpoint in the current epoch. The latest declaration per member
  is retained with its complete parts, in addition to the two recovery checkpoints. A new or
  old offline member blocks checkpoint removal until it upgrades and declares support.
- Immediately before deleting a superseded checkpoint, the archive descriptor gains a fourth
  `maintenance=1` line. Old three-line-only clients fail archive validation before reading
  removed parts. Updated clients restore the newest complete verified checkpoint first, even
  if an older commit had already been staged before cleanup. Downgrading a linked device
  after this transition requires upgrading it again; the library remains local and intact.
- One candidate or part is processed per pass, at most once every 30 seconds while idle.
  The existing periodic worker can advance up to twenty cooperative passes in sixty seconds
  to drain a backlog; it never waits behind an interactive operation or bypasses error backoff.
  The sync coordinator bounds each pass to twelve seconds and retains ownership of its normal
  mutex. Player/reader activity, incognito, downloaded-only, restore, account changes and
  cancellation stop maintenance. Cursors and candidate part lists survive interruption;
  deletion acknowledgements are reconciled by exact server message identity.
- Each delete rechecks ownership, payload, pins, current account, private-archive configuration
  and TDLib's deletion capability. The coverage index expires after verification, so large
  snapshots can finish incrementally without restarting every day. Completed scans run daily.
- Automatic full-backup retention is independent. Its pending state is scoped by account/chat,
  and a separate retry path does not regenerate or upload a backup. Each pass deletes at most
  ten eligible copies, retaining pinned copies and the newest copy of each device.

Verification must distinguish unit/fake-transport and isolated SQLite device probes from
real two-account/long-offline recovery. Compilation alone is not evidence of remote deletion
or complete cross-device recovery. See issue #33 for the delivery evidence and limits.

The synchronization protocol is in `core/telegram/.../sync`. It does not depend on an Android
database schema, a content catalog, an extension implementation or a player. Android adapters
live in `data/cloud/sync`; the existing centralized Telegram client remains the account owner.
Consumer-scoped chat subscriptions prevent the rooms and Cloud from unsubscribing one another.

Synchronization is explicitly opt-in. Downloaded media, generated media, caches, Android
permissions, hardware calibration, filesystem paths and Telegram sessions are not portable.
An account login and an enabled backup schedule do not imply synchronization consent.

## Data and conflict rules

- A content reference is a media kind, numeric source identifier and exact source-owned title
  and item references. Local database row IDs and display names are not content identities.
- Every local operation has a stable device UUID, a strictly increasing revision and a causal
  context. Registers retain concurrent alternatives and resolve deterministically. Wall clocks
  do not decide whether an operation supersedes another operation.
- Completion and position are separate fields. Positions are scoped to their originating
  device and session; a rewind is not replaced by the maximum previously reached position.
- Receiving an operation is distinct from projecting it into the local database. A local
  edit made before projection must not claim to have observed the incoming edit.
- Deletion markers remain in the replicated state. Ordinary field edits cannot resurrect a
  deleted record; an explicit addition after observing its deletion can.
- Category identifiers are mapped locally. Initial, unambiguous category labels can share a
  deterministic identifier; subsequent renames retain their established identity.
- Tracker content associations are portable. Account-specific tracker library identifiers
  are retained from the receiving phone, rather than copied from another account.

## Durability and player isolation

Source database triggers coalesce dirty references in the same transaction as user writes.
The journal is disabled by default, excludes incognito sources, and can be suppressed for a
remote projection. It records references instead of serializing a library inside a user write.

Capture writes the observed baseline, causal operation and outgoing journal in one local
transaction, then acknowledges the exact source-journal revision. A newer local modification
therefore cannot be cleared by acknowledging the previous revision.

Network retries reuse a persisted envelope identity. Only a confirmed server message or its
verified historical receipt can drain the outgoing queue. Remote projections are acknowledged
after the source transaction commits; interruption before acknowledgment repeats an idempotent
projection. Remote data must not seek or pause an active player or move an open reader page.

## Cryptography

- Content envelopes use AES-256-GCM with fresh random 96-bit nonces and authenticated account,
  space, protocol version, epoch and envelope identity.
- Decompression is bounded. Invalid authentication, unsupported versions and recognized but
  corrupt synchronization payloads must not advance a successful-sync state.
- Each device has separate P-256 signing and HPKE encryption keys. HPKE uses the standardized
  X25519 / HKDF-SHA256 / AES-256-GCM suite from Tink Android 1.23.0 (Apache-2.0).
- Local secrets are wrapped by an account-scoped Android Keystore key and stored under
  `noBackupFilesDir`. Missing Keystore material requires approval or recovery; it must not
  silently generate a replacement for existing encrypted data.
- Pairing uses an expiring QR containing the new phone's public keys and a random one-time
  approval secret. The secret is not sent through Telegram. HPKE provides confidentiality;
  HMAC authenticates the grant against the physically approved QR. The approval ID must be
  consumed durably with the accepted grant.
- Membership changes have a canonical server-message order, reference their predecessor,
  rotate the data key, and seal the new key separately for each retained device. Old membership
  revisions cannot authorize themselves again. Removal protects future data, not copies that
  were already downloaded.
- Recovery material is random, checksummed and independent of phone numbers and Telegram
  credentials. Never include it in ordinary backups, application logs or normal share links.

## Android integration

Cloud contains Sync, Devices and Backups. Enrollment is explicit and displays the local library
counts, explains merging, and creates an authenticated encrypted local full backup before applying
remote records. This copy can be previewed/restored through the existing backup restoration screen.
The recovery code must be saved before creating the first archive. Ordinary Telegram login does
not grant access to encrypted content.

Source database journals capture library additions/removals, content outside the library, category
membership, seen/read state, position, bookmarks, release subscriptions, history and tracker links.
Display metadata comes from the existing local source contract. Opaque extension memo blobs are
not exported; their meaning and potential embedded credentials are not an application contract.
Individual intro-skip choices, hidden resume cards and acknowledged/dismissed release notices map
to exact content references, and category preferences map through replicated category identities.

Portable application settings are an explicit allowlist in PortableSettings. Each group can remain
local on this installation. Re-enabling a group resumes reconciliation of its local edits.
Unknown preferences do not silently become portable. The RIN adapter preserves catalogue identity,
signer, installed inventory and enabled state. It installs only recorded user choices from a trusted
matching catalogue; it does not install an entire catalogue. Android's legacy-package removal still
uses the existing explicit migration flow. Unavailable dependencies remain pending and visible.

Player/reader hooks signal only after existing local saves. They do not add remote control or native
player calls. Small outgoing changes run in the foreground; continuous video progress is coalesced
for up to ten seconds, and final/page saves request an earlier send. Delivery depends on network and
Telegram limits. Incoming database/settings projections, initial exports and bulk transfers wait
while a player, reader or Cast session is busy. The original local save remains authoritative during
active use. Android WorkManager provides best-effort background recovery; there is no Firebase or
permanent synchronization service.

The status panel separates the last successful reconciliation from the last confirmed data upload.
An empty archive check updates only local verification metadata, without publishing heartbeat messages.
Periodic empty checks retain the settled status; explicit checks and pending work show their activity.
Verification requires a ready Telegram connection and a completed history pass, not cached authorization.
Operation-owned deadlines produce retryable transport errors while caller cancellation remains cancellation.
An interrupted send refreshes a failed TDLib message before reading its retry countdown: TDLib
does not publish updates when that countdown changes. Only successful immutable receipts are
reused from the event cache. Fractional retry intervals are rounded up before resending.
This preserves the foreground loop and queued envelope identities after interrupted uploads/downloads.

Preferences use persisted projection intents plus an off-main-thread disk barrier before receipt
acknowledgment. A complete checkpoint confirms outgoing revisions only after every encrypted part
and its signed commit have server receipts. Interrupted sends retain envelope identity and queued
TDLib message IDs. Local journal payloads may then compact while retaining revision digests,
current causal registers and deletion markers. Server history is never deleted merely on the
assumption that every offline device has already received a checkpoint. Only the authenticated,
doubly covered redundant messages described above are eligible for remote maintenance.

Full Cloud backups made by an enrolled device use version-2 manifests and authenticated streaming
encryption. Earlier manifests remain readable and visibly labeled as not encrypted by Nyanime.
Unlocking a protected backup does not itself activate automatic sync. Exported manual .nyabk files
remain compatible with the existing restorer; .tachibk input compatibility is unchanged.

## Optional source preference contract

An installed signed RIN may contain assets/nyanime/sync-v1.json:

```json
{
  "version": 1,
  "settings": [
    {"source": 73, "key": "preferred_quality", "type": "string", "maxLength": 80},
    {"source": 73, "key": "portable_access", "type": "string", "credential": true}
  ]
}
```

This example uses a synthetic source ID. A declaration must reference only source IDs declared
by the same signed package. Supported types are boolean, int, long, float, string and strings;
numeric bounds and bounded string lengths are validated. References bind the source, preference
key and verified signer. The app never runs extension code to discover this capability or copies
all source preferences. No declaration means no source-preference synchronization. Credentials
additionally require the user's separate, initially disabled consent. Browser cookie stores,
Telegram sessions and Android permissions are outside this capability.

Malformed optional contracts/values do not stop library synchronization and leave a waiting state.
Existing RIN packages require their own explicit portability declaration to participate.

## Acceptance on two devices

Use two phones linked to the same Telegram account and explicitly enable sync on both:

1. Configure the first phone, save its recovery code, then approve the second phone's QR.
2. Compare existing libraries/categories, items started outside the library, bookmarks and settings.
3. Pause a video on A and open Continue on B; repeat with a manga page. Check the device label
   and alternate saved points. No already-open player/reader should move remotely.
4. Edit different settings/progress on both phones, including a rewind. Disconnect one phone,
   remove a library title on the other, then reconnect: stale progress must not re-add that title.
5. Interrupt an upload/restart the app, check pending state and subsequent confirmation. Do not
   count a queued upload as successful delivery.
6. Disable one preference group and verify it stays local. Test incognito and a private source.
7. Test protected full-backup preview/restore, recovery code and device removal with key rotation.
8. Compare player, rooms, Cast, RIN updates and regular full backups with sync disabled/enabled.

Record actual delivery timings and foreground/background/network conditions. Do not claim an
unmeasured instant-delivery guarantee or two-device success from a single-device database probe.

## Preview validation (0.33.0.0)

- The signed arm64 preview was installed over the existing application on a physical Android
  phone, preserving its account, library and backup archive. Startup and the Sync, Devices and
  Backups tabs were inspected; no new crash appeared in the crash buffer during these checks.
- The isolated instrumentation probe passed concurrent-edit/projection, rewind, persisted-outbox
  restart, exact receipt acknowledgment, exclusion, checkpoint, compaction/tamper and immutable
  packet-file checks. It uses random test storage and never modifies the user's library.
- Initial setup reached its protected recovery-code step on the phone, exercising the actual
  Keystore and cryptographic initialization. Setup was then closed before activation: no personal
  synchronization archive was created and automatic synchronization remains disabled.
- Protocol unit tests and Android journal tests pass. Existing full backups remain listed in the
  Backups tab. Italian/English synchronization resource keys match.

Pairing, actual cross-device convergence/latency, encrypted full-backup recovery and device removal
still require the two-device acceptance run above. No emulator or claimed two-device simulation
substitutes for that run.

References: [Tink hybrid encryption](https://developers.google.com/tink/hybrid),
[Tink Java releases](https://github.com/tink-crypto/tink-java/releases/tag/v1.23.0),
[TDLib](https://core.telegram.org/tdlib/).


### Physical-device/server validation (0.52)

The opt-in server probe passed on one USB Android phone with the real TDLib1.8.67 transport
and three independent logical device identities/stores on one Telegram account. Only a new,
nonce-bound private fixture archive was used. It exercised document transport, two complete
causal recovery proofs, preservation of pins/unrelated posts/orphan parts, the legacy-reader
fence, a locally staged obsolete commit, new-device recovery with unsent concurrent/rewound
positions, a field excluded from later snapshots, and device revocation with retained edits.
The fixture archive was deleted and cleanup was confirmed. A separate actual Android SQLite
probe passed v1-to-v2 migration, restart, part/cursor rollback and causal-index persistence.

These results are not two-physical-device latency measurements or a long-offline/background
acceptance run, and do not assert a full-backup restore. Test cleanup uses the pinned native
schema's deleteChat capability only after nonce, ownership and private-archive checks. Pending
fixture cleanup is persisted across rate limits; test errors are not masked by cleanup failures.
