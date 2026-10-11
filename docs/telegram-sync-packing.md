# Compact Telegram recovery

The 0.53 work changes transport layout, not causal meaning. The active register already retains only causally maximal revisions of each field. Unsent revisions that have been causally replaced are omitted before encryption, keeping their digests for revision-reuse detection. An uncertain, assigned outbox packet is immutable. Concurrent offline alternatives, rewinds, independent fields, removals and privacy exclusions remain distinct.

## Observed baseline

A read-only scan of the selected real archive on 21 October 2026 counted 5,653 messages and 1,477 documents (45,896,571 bytes). Declared metadata classified 1,441 documents as checkpoint parts, 24 as commits, 11 as deltas and one as pairing data. No document was downloaded or decrypted for this inventory. 1,457 documents were younger than seven days. These are inventory observations, not evidence that any file can already be deleted.

## Layout and bounds

Each `.nysync` container starts with `NYANIME_SYNC_PACK_1\n`, a 32-bit big-endian parent-packet length, the parent packet, then length-prefixed ordinary encrypted checkpoint packets. The parent envelope authenticates a versioned index: checkpoint identity, each child's identity, SHA-256, encoded length and contiguous offset. A signed checkpoint commit binds the parent envelope and block count.

Bounds: 128 children, 64 MiB per document, 128 KiB header and 12 MiB per encoded frame. Existing checkpoint chunks remain limited to 1,024 changes and about 2 MiB serialized input; AEAD and bounded gzip decompression are unchanged. No increase to the global 4,096-logical-block checkpoint limit. Frames are written progressively; reads open and close a bounded random-access frame without leaving a descriptor suspended in a lazy iterator. The container never creates one giant plaintext array. No new native or third-party dependency is added.

Framing is validated and the encrypted file copied atomically before advancing a receive cursor. On recovery, all authenticated blocks are validated before the transactional projection. Persistent maintenance cursors include the child position, with causal coverage and cursor advanced in the same SQLite transaction. Partial uploads reuse their durable parent identity and exact bytes.

An interrupted upload-file copy first recovers AtomicFile's own saved bytes, including the legacy `.bak` layout, then checks them against the prepared container. A different existing identity is rejected rather than silently replaced. Corrupted receive data fails authentication; no partial database restoration is committed.

## Migration

Signed version-1 checkpoint commits advertise optional `packingVersion=1`. A current-epoch signed advertisement is required from **every authorized member**, including offline devices. App versions and last-seen time never substitute for this proof. Unsupported members keep legacy writes and historical recovery intact. Membership changes require fresh proofs.

Each advertisement also carries a small detached reader signature bound to account, space, epoch, membership control and original author. Once fenced, another authorized member can relay that exact signature as a tiny encrypted `capability` text packet. The proof does not restore data and does not depend on retaining the original checkpoint's parts. Compact proof anchors take priority over daily checkpoint declarations, so an offline reader cannot permanently pin an entire obsolete recovery copy merely as evidence of compatibility. Relay retries persist their envelope identity; concurrent relays can leave a bounded few text proofs, not duplicate full checkpoints.

After unanimous support, the private archive is fenced with `maintenance=2` before publishing containers. Existing readers supporting only `maintenance=1` reject archive access before consuming an unfamiliar packet; they must update. New readers accept both layouts. Two complete compact recovery copies are established cooperatively before ordinary daily rotation resumes. The latest two physical copies still undergo independent full causal verification before deletion.

The UI reports the device capability count and documents/blocks in the last authenticated checkpoint declaration. It does **not** present that count as the number of files still on Telegram. Earlier messages keep the seven-day minimum age, pin/ownership rules, two-copy requirement and exact causal coverage. Backup documents and foreign messages are never pack-compaction targets. Retention is not shortened to make the file counter drop immediately.

## Release gate

This format description alone is not a successful personal-archive migration claim. Required evidence: framing/crypto/causality and compatibility tests, actual SQLite/outbox interruption tests, a synthetic real-server inventory and cold restore, UI checks, bounded-memory/time observations and appropriately scoped independent-client validation. Keep the #35 release immutable; publish the next version only after its own gates pass.

### Evidence collected so far

- 81 Telegram-core and 1,812 app unit tests completed without failures; seven pre-existing app tests were skipped. An old, already encrypted prepared commit is resumed unchanged and does not falsely count as a new packing-capability announcement.
- The opt-in real-server fixture passed in about 120 seconds on the USB phone. Its private synthetic archive went from seven to four documents, with eleven message removals confirmed by actual absence and 94,608 document bytes removed. Two packed recovery documents remained, together with the pinned document and the older data still required by exclusions. Portable reader proofs were text messages, including the original proof of an offline author whose full legacy copy was retired.
- A fresh logical device recovered the last value, 30, after edits 10/20/30, while preserving concurrent offline changes. Revocation, unsent edits, an orphan part and an unrelated post were also checked. Fixture archive cleanup was confirmed.

- The actual Android SQLite/Keystore/AtomicFile fixture also passed: 2,049 synthetic records, 17 legacy documents (1,680,108 bytes) to one container (1,682,300 bytes), about 20 seconds for the complete fixture. Grouping reduces file count; it is not an additional compression claim. The maximum sampled whole-process heap was about 257 MiB, not a measured peak or an attribution of all that memory to compaction. Old prepared-commit resume, stable prepared restart, immutable uncertain outbox, 10/20/30 coalescing, AtomicFile backup recovery, cold restore with an offline edit, and complete transaction rollback after post-validation corruption were checked. This local fixture made no network requests; its receipts were synthetic.

These server results used three isolated logical identities/stores on **one physical phone and one TDLib client**. They are not a two-client network/cache or background-delivery test. The personal archive was outside the mutation fixture. The user has currently limited device access to USB only. Final signed APK, UI, source/native-material and OTA reconstruction checks are separate from these runtime fixtures and must identify the exact artifact being distributed.
