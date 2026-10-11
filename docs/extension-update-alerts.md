# Extension update alerts — 2026-09-11

## Confirmed cause

MainActivity starts both extension checks on composition. The old daily guard applied
only when fromAvailableExtensionList was true; the Activity used false. Both APIs also
shared last_ext_check. Every successful check could post the identical notification again.

## Fix

- Shared automatic-check gate, with independent persistent anime/manga timestamps and
  process-wide per-kind mutexes; recreation cannot multiply requests.
- 24-hour successful-check interval; 15-minute retry interval for propagated failures.
  Cancellation is propagated and does not commit a completed check.
- Version-aware announcement ledger (package, versionCode, library API), bounded to 1024
  entries. Dismissed, reordered, subset and temporarily missing-repo results do not re-alert.
- Separate notification IDs; anime clearing cannot dismiss manga updates.
- Manual catalogue refresh continues to use findExtensions and remains immediate.
- MainActivity checks run independently; one failing catalogue cannot suppress the other.
- Existing app startup updater, extension trust, source Home and Anime4K are unchanged.
- Release asset selection accepts only known signed app filenames, picks a supported ABI
  or the universal APK, and ignores extension APKs, checksums and source archives.

## Verification

spotlessApply testDebugUnitTest passed locally with all eight new policy tests discovered:
recreation, daily boundary, kind independence, overlapping API instances, failure retry,
cancellation, clock rollback, persistent version deduplication and bounded storage.
The signed universal APK is produced and tested separately by the GitHub preview workflow.
Four additional asset-selection tests cover mixed attachments, architecture order,
official stable filenames and rejected look-alike/unsigned packages.
No phone UI, playback or ADB diagnostics were used for this change.
