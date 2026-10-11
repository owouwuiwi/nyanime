# Novel reader core

Local EPUB/TXT import and Readium3.4.0 integration, with independent identities,
SQLite metadata, structural locations, annotations and a disposable chapter search index.
No provider rules, catalogue, translation, OCR, TTS or PDF engine is included.

The current core has38 passing JVM tests and48 isolated Android checks.
The ordinary minified app has also been exercised on a physical phone for navigation,
selection, notes, search, rotation and resume. Portable backup/restore has been exercised in-app;
the Cloud adapter passed seven isolated two-store checks. Online sources and the complete
integration are not finished. This is not a release claim.

## Durable online downloads

`NovelDownloadStore` and `NovelDownloader` retain explicit selections outside the disposable
reader cache and metadata backup. A separate private SQLite ledger records immutable document
snapshots, generation leases, resources and completed chapters. A chapter is completed only after
its body and every declared illustration pass integrity checks. Pausing or replacing an attempt
cannot let a late worker commit to the new job. Completed chapters and shared illustrations are
reused; interrupted selections can be resumed. Execution code, request headers and authentication
sessions are not part of the ledger schema.

Files are copied with bounded buffers, checked by SHA-256, synced and atomically renamed before
the SQL entry is committed. The limits are2GiB and20000 owned resources per book, with32MiB disk
headroom. Live reading sessions pin their files. Physical ownership accounting includes superseded
objects while pinned; collection touches only this store's verified filename patterns.

`NovelDownloadAccess` prefers durable files and uses the ordinary source-mediated cache for missing
chapters. Its cache-only view never fetches; a durable-only view also works without an installed
source. Download availability is local state in schema3 and never enters portable records. Merely
owning a cover or online manifest still grants no offline availability.

Android checks cover cache loss/store reopen, illustrations, selected-chapter merging, stale leases,
monotonic progress, cancellation/resume, corruption, live-file protection and storage limits.
Host WorkManager and UI integration still need their separate minified-app checks.

## Disposable reader sessions

A reader may supply its own document root, metadata store and search database without changing
the ordinary shared library. Accepted metadata writes outlive the Activity. Session cleanup joins
indexing and publication releases, drains accepted writes, then closes the disposable search
database; the caller owns its metadata store. No fixed cleanup delay is used.

Three JVM tests cover FIFO writes, error isolation, waiting for publication releases and rejection
after shutdown. Three checks in the self-targeted Android APK verify separate metadata/files/FTS
and that writes finish before cleanup. This establishes the core lifecycle, not the Studio preview
UI, authorization or process-recreation behavior.

## Boundaries

- Import keeps the external original untouched. It copies and hashes into private storage,
  checks archive resources, validates through Readium, then publishes files and metadata.
- TXT conversion streams bounded XHTML chapters into EPUB. Encoding can be selected explicitly;
  decoding errors are reported rather than replacing arbitrary source text silently.
- Each edition has its own generated directory. Restoring a missing local file can preserve
  the same content identity, reading position and notes without overwriting another edition.
- EPUB locators and notes never enter manga page/progress columns. Displayed page numbers
  are not permanent identifiers. The content version must match before automatic resume.
- Local books use a blocked HTTP client and an archive-only container. Publication scripts
  are stripped before Readium injects its trusted navigation bridge. The allowed virtual origin
  `https://readium_assets` is an implementation detail of pinned Readium3.4, not a remote service.
- The search index is derived cache, not backup data. Index one chapter at a time; use bounded
  passages and cancellable work. Do not load a complete novel into a single string.
- Do not erase unknown optional metadata when exporting/restoring or applying cloud projections.
  Private file paths and cached chapter text must not be sent as cloud book metadata.
- SQLite schema2 keeps private document ownership in its own table. Portable records have a
  versioned, bounded envelope; the local latest-per-entity journal and compare-and-set projection
  preserve edits made while offline. These data must not be flattened into manga progress.
- Backup and Cloud use the lightweight shared store, without initializing Readium or fetching
  a source. A restored local book needs its original file to be reconnected by verified hash.

## Dependency and asset audit

Readium shared/streamer/navigator3.4.0 are pinned Maven Central artifacts, BSD-3-Clause.
The toolkit's EPUB parser is used; no PDF or DRM provider is enabled.

The navigator AAR embeds22 PhotoView2.3.0 classes, byte-identical to Nyanime's existing
direct dependency. When integrating, remove the duplicate direct app dependency after
verification; do not suppress duplicate-class diagnostics. Retain PhotoView's Apache2 notice.

Notices copied from the exact toolkit source are under `src/main/assets/novel/licenses/`.
Font licensing is separate from toolkit code: Accessible-Dfa and iA Writer Duospace carry OFL
notices. The actual embedded OpenDyslexic-Regular.otf is a legacy build whose name table states
Bitstream copyright plus CC BY3.0 for Abelardo Gonzalez's changes. Do not relabel those bytes
as current OFL merely because newer upstream releases changed license.

Before distribution, compare the real resolved dependency graph and final APK assets, keep
all applicable notices available in-app, test local network denial in the real navigator,
and complete the app/library/backup integration and device tests.
