# Mihon integration checkpoints

Reference: Mihon v0.21.2, commit `9612d955db3165e1578fdf9ff19ca9b7762c4a46`, Apache-2.0.
The implementation is adapted to the existing host contracts, two databases and presentation.
Upstream code retains its attribution; this document records the selected changes and validation.

## Delivery sequence

1. Network/reader lifecycle stability and tracker dates/concurrency.
2. Bounded parallel reading, page priority, preload policy and optional navigation.
3. Atomic download indexes, batched filesystem work and batched backup restoration.
4. Query/SQLite concurrency and release-pattern prediction.
5. Optional tracker/account conveniences and local metadata improvements.

Each checkpoint is committed separately and validated before proceeding. The user authorized
a final main/dev push after validation. Public extension contracts, legacy backup reading and existing user choices
must remain compatible. Source-specific behavior belongs to extensions, including test fixtures.

## Validation

- Distinguish compilation/unit tests from physical-device validation.
- Compare the same navigation and loading scenarios on the same USB device before and after.
- Treat pre-existing session frame/memory diagnostics as context, not a controlled benchmark.
- Exercise cancellation, concurrent failure, corrupt caches, offline work and local filesystem naming.
- Verify database/backup changes on copies, retaining IDs, relationships and app-specific data.
- Check the video player, PiP, casting, shared sessions and guided reading on a real device.
- Keep signed APK identity stable and increase the distributed version for each new test APK.

## Progress

Baseline source: `8a8f67ae19f29ded5cdea7da8d03226153b53bb7` (main/dev).
Initial physical device has version 0.43.0.3 (233), Android minimum remains API 26.
Implementation and validation results are recorded below as each checkpoint completes.

### Checkpoint 1: network and tracking

- Adapted upstream host coordination, isolated verification bridge, non-GET root verification and renderer failure handling. The existing interceptor constructor and extension HTTP APIs stay compatible.
- A cancelled call stops waiting for verification. Concurrent requests share both success and failure, and host entries are released when the last request closes.
- Browser-screen HTML inspection is cancelled on navigation/disposal; a renderer failure closes the affected screen without exposing visited URLs in diagnostics.
- Historical track dates keep their stored epoch, ignoring null, zero and invalid history dates. Existing verified associations and progress across gaps remain in place.
- Account renewal is serialized; unsuccessful/invalid renewals cannot publish a broken token, and logout cannot be overwritten by a renewal already running.
- Empty/invalid select, sort and tri-state filters remain editable without silently rewriting their source state.

Validation: 4 core coordination tests and 13 application tracking tests passed.
Application and test sources compiled successfully. Physical-device verification of these changes
will use the final signed APK; the installed baseline has not been replaced yet.

Controlled baseline on the USB device (0.43.0.3): five Home/Library return cycles recorded
423 frames, 20 janky frames (4.73%), p95 28 ms. Three forward/back page pairs recorded
378 frames, 12 janky frames (3.17%), p95 5 ms. These small samples are comparison inputs,
not a guarantee of performance across other titles or networks.

Coordination: ESTENSIONI owns the external extension template/demo kit and its documents.
Its host-only documentation commits are preserved; no simultaneous host Gradle or USB work remains.

### Checkpoint 2: reader

- Three image transfers per reader, shared across its current/adjacent chapters; prefetch is limited to two slots and a small nearby window. Existing data-saver behavior is preserved.
- Visible pages are prioritized; page jobs and repeated image URLs are deduplicated. A cancelled body is closed outside the UI thread even if its socket read ignores interruption.
- Chapter loading is serialized per chapter. Cancellation recycles partial loaders and returns to a retryable state instead of publishing a failed/half-loaded chapter.
- Reader initialization follows activity cancellation; delayed webtoon taps cannot manipulate a detached view.
- Split pages retain their actual chapter/page owner, and webtoon rebind notifications stay within adapter bounds. Zoom respects padding and changing window size.
- Wi-Fi-only preload and a vertical page rail are optional and initially off. Downloaded/local chapters remain available on other networks.
- Both navigator layouts defer a drag's page jump until release. The optional rail uses ModernMotion and the existing reduced-motion preference, with a horizontal fallback in short windows.

Validation: 10 distinct reader regression cases passed, including the blocked-body cancellation
case, shared transfer budget, duplicate loads, split ownership and bounds. About/ResponsibleUseCard
from ESTENSIONI also compiled successfully in the same application build. Device comparison still
requires the final signed APK.

### Checkpoint 3: download index and restoration

- The existing v3 download indexes use Android AtomicFile and bounded decoding (16 MiB). A damaged snapshot is discarded and rebuilt; a failed scan does not publish a partial index.
- Reconciliation uses bounded IO concurrency and waits for source initialization. Existing directory/CBZ/video names stay recognized; no rename or file migration is required. Reader/download indicator notifications are conflated.
- Batch enqueue/delete lookup lists a title directory once, with cached queue membership and duplicate entry IDs excluded.
- Restore transactions contain at most 100 root entries. A failed transaction rolls back before entry-by-entry fallback; cancellation never starts fallback. Season relationships and existing Nyanime fields follow their original restorers.
- These changes leave backup encoding, portable settings, `.nyabk` and `.tachibk` handling unchanged.

Validation passed: real SQL-backed rollback/fallback, cancellation, bounded index input and
inaccessible directories, plus the existing payload/format/guided-reading/spread backup cases.
Final device checks will use the signed APK.

### Checkpoint 4a: library work and release patterns

- Tracker ID sets are computed once per filter pass instead of rebuilding the full map for each entry.
- Library queries aggregate only favorites and relevant child seasons. Manga history is aggregated separately from chapter statistics; excluded scanlators and category rows retain their existing meaning.
- Query changes leave stored views, schema versions, IDs and relations intact. The existing WAL and off-UI flows were already present and are retained.
- Adapted Mihon's weekly, monthly, last-weekday and batch patterns, median phase, lateness and hiatus backoff to the epoch/java.time models shared by video and manga.
- Four observations are retained for small histories so a monthly pattern or delayed weekly release can be recognized. Nonpositive/far-future timestamps are excluded; fallback fetch dates and explicit negative user intervals remain supported.
- A newly found release advances the next check. Metadata-only changes do not. Zone-aware midnight uses the supplied date/time, including daylight-saving changes.
- These are polling predictions only: they do not create release dates, calendar entries or notifications.

Validation: 31 domain scheduling cases passed; the real-schema SQL comparison passed for video
and manga, including category duplicates, excluded scanlators, empty entries and child seasons.

### Checkpoint 4b: SQLite concurrency

- Kept SQLDelight 2.0.2 and the current Android driver/schema instead of importing Mihon's new storage architecture wholesale.
- Separate, bounded read and writer dispatchers per database; one reader on low-RAM devices, two otherwise, and one transaction worker. Existing WAL/NORMAL configuration stays enabled, with a 3000 ms writer busy timeout.
- A suspending transaction retains its caller Job. Its control Job is a child of the caller, so cancellation during a transaction or while acquiring its worker cannot leave the worker parked indefinitely.
- Nested transactions retain the same dispatcher and rollback together; reads inside a transaction do not leave its context.

Validation: both real file-backed databases passed 40 concurrent nested writes plus concurrent
reads, active writer cancellation/rollback, a later successful write and integrity checking of
reopened file copies. The original implementation hung on the cancellation regression case;
the corrected implementation completes it. No table/column migration or identity change is involved.

### Checkpoint 5: account conveniences and local metadata

- Manual AniList search accepts `id:123`, reusing the existing verified ID lookup. Automatic title/edition matching is unchanged.
- Account metadata refresh is explicit, deduplicated and invalidated by logout or identity changes. The stable credential identifier is retained; the readable account name is stored separately.
- Optional local AniList score scales affect presentation and selection. The default follows the account; stored 0–100 values and remote account encoding are retained.
- Local chapter ComicInfo reads Number/Title/Translator and complete, valid Year/Month/Day dates from directories and supported archives. Invalid or partial dates keep their filesystem fallback. Existing chapters.json overrides remain last.
- The ComicInfo constructor/copy ABI is preserved: date fields are optional serialized body properties. Existing public methods retain their parameters.
- MangaBaka and Hikka were evaluated as account trackers. Their authenticated integration requires a Nyanime-owned OAuth registration; no upstream application credentials were copied. Existing public metadata support is retained.

Validation: account refresh concurrency/logout, ID search and numeric display scale cases, plus
real XML round trips, invalid chapter dates and the legacy ComicInfo constructor/copy signatures.
Private device backup validation uses an ignored local payload and isolated databases; CI uses
generic synthetic entries. No private payload is committed or used as a fixture.

### Restoration follow-up

The device's portable backup exposed an existing URL-only history lookup: repeated paths in
different entries could return multiple rows or bind another entry's history. Restoration now
loads chapter/episode IDs and history for the current title, using bounded maps. No schema or
extension-specific assumption is needed. The generic fixture repeats the same relative path
across 250 video titles and 250 manga titles, with distinct dates, categories and tracker IDs.

## Consolidated checks

On 14 October 2026, full Spotless and JVM verification completed: 1,667 passing cases,
7 opt-in live/interop cases not enabled, zero failures/errors across app, domain, core/common, Telegram,
panels and smart-search. The 119 distribution/signing/Lua telemetry script cases also passed.
The real portable device backup restored in isolated databases after the history correction.
Both read positions, category membership, tracker IDs/progress and season relationships were checked;
a second application reused the same database IDs without duplicate roots.

Release prepared: 0.44.0.0, Android code 234, preview channel. Final signed-device checks
are recorded separately below; unit checks are not physical-device proof.

## Signed device validation

Test APK source: `84accae29`. All five generated APKs passed production package,
certificate, non-debuggable and version checks. The arm64 APK updated the existing USB
Galaxy Z Flip6 to 0.44.0.0 (234), retaining Android minimum 26. No other phone was used.

Full portable exports before/after installation retained 57 manga, 161 video entries,
7,161 chapters and 8,278 episodes: no missing entries/items, changed positions, category
memberships or tracking records. Extension payloads, hidden resume items, guided reading
and spread records were unchanged. Of 180 existing preferences, only the Home's seen-update
marker changed while navigating. Private exports and screenshots remain outside Git.

Same navigation timing and page gesture pairs as the baseline:

| Sample | Before 0.43.0.3 | After 0.44.0.0 |
| --- | --- | --- |
| Five Home/Library cycles | 423 frames; 4.73% jank; p95 28 ms | 422 frames; 3.32% jank; p95 16 ms |
| Three forward/back page pairs | 378 frames; 3.17% jank; p95 5 ms | 378 frames; 2.91% jank; p95 6 ms |
| Navigation PSS | 405,144 KiB | 361,017 KiB |
| Reader PSS | 455,075 KiB | 393,300 KiB |

The post-update navigation was warmed with two cycles. These are small cached-content samples,
not a global FPS/network benchmark; memory varies with process lifetime and reclamation.

- Reader: images loaded, vertical rail drag selected page 6, its next-chapter button opened
  the next chapter; changing font scale recreated the reader without a crash. Font scale and
  navigator preference were returned to their initial values.
- Download: one test chapter was queued, completed, recognized after process restart and
  opened offline. Deleting it through the app removed its CBZ and offline indicator.
- Tracking: account refresh returned a readable name and cleared its busy state. Local 0–10
  scale selection worked and was returned to the account format; no rating was edited.
- Player: video playback, first Home/PiP entry and return continued in PLAYING state with an
  advancing position and a complete frame. Stopping playback did not create an empty PiP.
  No fatal exception, native fatal signal or ANR was found in the captured test windows.
- Rooms: a new single-member room reached ready state and closed back to the creation screen.
  No invite was sent to another person.

Limits: multi-phone room synchronization and playback on a physical Cast/companion receiver
were not exercised. Their deterministic protocol/lifecycle tests passed; opt-in live/interop
cases remain distinct from the device smoke. Large restoration was tested with actual restorers
in isolated databases, not by overwriting the phone's library. Parallel queue lookup and corrupt
index/failure recovery were exercised in automated tests; the phone download sample was one chapter.

The final main/dev push includes documentation-only validation commits after the APK's source;
no application code changed after that signed build.
