# Private Cloud synchronization

Preview implementation: optional personal synchronization is available through Cloud's Sync tab.
Two-device delivery/latency is a separate acceptance gate; compilation and unit tests do not
establish it. The existing backup archive remains available independently of synchronization.

## Boundaries

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

Preferences use persisted projection intents plus an off-main-thread disk barrier before receipt
acknowledgment. A complete checkpoint confirms outgoing revisions only after every encrypted part
and its signed commit have server receipts. Interrupted sends retain envelope identity and queued
TDLib message IDs. Local journal payloads may then compact while retaining revision digests,
current causal registers and deletion markers. Server history is retained; it is not deleted on
the assumption that every offline device has already received a checkpoint.

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
