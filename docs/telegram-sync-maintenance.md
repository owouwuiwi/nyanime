# Telegram sync progress and history maintenance

Data synchronization and history maintenance have independent status. A successful data pull/send is published before optional maintenance. Maintenance is cooperative, performs at most one candidate/part per step, yields between passes, waits during playback/restore/incognito/offline, and respects persisted Telegram backoff. It never creates network requests merely to animate a screen.

## Persistent evidence

The existing rebuildable maintenance SQLite index stores the typed plan and its verified chunk cursors atomically with exact causal coverage. Additional optional fields retain a bounded page queue (at most twenty IDs), per-pass examined/protected/young/uncovered counts, confirmed removals and the last completed activity. Old plans deserialize with defaults. Per-pass counts are distinct from lifetime confirmed removals. Queued candidates survive process death; every destructive request rechecks ownership, archive privacy, pin status, exact payload and permissions.

A changed signed capability set adds or replaces the required device proofs without resetting verified chunks. Matched checked anchors remain checked; a newly selected anchor is checked again. Membership epoch changes and expired causal proofs still invalidate the corresponding proof. No capability is inferred from app version alone.

## Snapshot growth

A backlog above 256 changes is not a checkpoint trigger. Ordinary deltas drain it. Initial creation, membership capability announcements and interrupted checkpoint uploads retain their explicit reasons. Daily rotation requires pending changes and uses the latest complete verified archive checkpoint, including a peer copy. Concurrent publishers can still race, but an unchanged library is not uploaded on every sync tick.

Deletion requires two independently verified complete checkpoints, exact causal coverage, at least seven days of age, current authorized membership and unpinned owned messages. A successful delete request is followed by checking absence; an uncertain result keeps the candidate and is not reported as removed. A growing chat can still be expected during the initial seven-day retention period, proof verification or incompatible device protection. No finite archive-size guarantee is implied while edits continue.

The [0.53 container layout](telegram-sync-packing.md) groups physical documents while preserving bounded logical-block work. Proof, candidate and deletion cursors include the block position inside a container. Causal coverage is checked for every block before removal. Compact portable reader signatures can replace full-copy compatibility anchors only after unanimous signed support and the newer archive fence; they never act as data recovery copies.

## Verification

Unit tests exercise snapshot bursts, durable queues, incomplete/unknown totals, restored counters, late capabilities, cancellation, backoff, causal exclusions and protected messages. The opt-in real-server test creates a private nonce-bound archive with synthetic data, advances only its injected maintenance clock, checks actual removals and protection, then removes that test archive. Production clocks, privacy options, personal records and retention are not changed by that probe.

OTA delivery uses the retained complete 0.52.0.1 checkpoint and the bounded v2 release graph. Signed target APKs are still built and verified locally; patch-only publication omits their repeated upload, not build/signature/license verification.

Protocol references: TDLib [deleteMessages](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1delete_messages.html)
and [getMessage](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1get_message.html).
The counter requires the deletion response and subsequent observed absence; it does not
infer deletion from a spinner or from an archive-size estimate.

## Local projection passes

The local item check spans all bounded database pages. Its total is not the size of the current page. A persisted input-generation counter changes transactionally when new projection data arrives; normal acknowledgements do not restart the pass. Unchanged unresolved items are retried quietly after 30 seconds. New input, extension changes, foreground entry and an explicit sync bypass that ordinary delay. Playback pauses preserve the in-session page cursor. Empty background polls do not replace completed work with a flashing progress indicator.
