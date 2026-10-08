# Primo piano: title details and item management

The Android anime and manga detail screens use the approved Primo piano design:
fixed compact header, rounded artwork, reading/viewing progress, one resume action,
library/release/room shortcuts, and the complete lazy episode/chapter list. Details
remain a separate tab; Manage is for batch operations. Android TV is not changed.

## Existing functionality

The presentation delegates to the existing screen callbacks for playback/reading,
release subscriptions, tracking, refresh, source settings, categories, migration,
intro settings and downloads. It does not introduce a source-specific protocol.
The details section retains descriptions, genres, related works, continuation into
the other medium, cached broadcast information and local download management.
The overflow menu retains the alternative player, sharing, filler markers, marks
for previous items, removing bookmarks and deleting downloaded files.

## Item identity and selection

The manager uses local item IDs. Number searches span the complete list after native
source filters, including fractional numbers, specials and duplicate editions.
The inline list has no three-item cap or mandatory pagination. Its search shares
the same matching predicate as the manager. Index shortcuts contain twenty actual
positions and scroll to a stable local item ID; chapter numbers are not array indices.
Native ordering and advanced filters remain available.

Artwork, progress, optional notice, pinned tabs/controls and source-owned extras have stable
lazy keys defined by one section contract. Each episode/chapter has its own keyed
lazy item; season grids compose one row at a time. Back to your place uses the actual
resume ID and compensates for the measured sticky tab height. A filtered-out resume
target disables the shortcut instead of navigating to another item.

Search, index and resume remain accessible while scrolling. Short displays use a
compact resume icon in that toolbar instead of the secondary text-action row.

The list and details tab keep separate saved scroll states. Returning from details,
the player, reader or manager restores the list position. Search/filter changes
return to the list controls rather than the artwork; ordinary data updates retain
the visible lazy item key and offset when that item remains present.

Short search results reserve only the unoccupied part of the measured viewport,
including the pinned controls, source extras and keyboard insets. This keeps the
controls anchored for zero or one result without adding empty scrolling to a long
list. Keyboard space is bottom content padding, not a smaller scrolling container.
That padding and the remaining result area change together in one calculation, so
the total short-result content height stays stable while the keyboard opens/closes.
The minimum row height is shared by layout and measurement, with actual row
heights used for the small result sets that need this reservation.
Viewport height comes directly from the list's current measure constraints, rather
than a later onSizeChanged update. Viewport and reserved area therefore participate
in the same layout pass rather than using a previous viewport size.

Selection belongs to the presentation and does not replace or truncate the source
item list. Selections persist while changing groups, and Select visible adds only
the displayed IDs. Long-press ranges use the currently visible list. Removed or
filtered-out IDs are removed from selection before further actions.

New means an existing release notice for an unread/unwatched item, not every unread
item in the catalogue. The anime resume target uses the same ResumeEpisodeSelector
as the Home and player; manga retains its existing unread selection rules.

Numeric markers grow only as needed for fractional numbers and larger fonts, retaining
the full identifier. Focus, keyboard and selection-back handling belong to the modal
window rather than the parent Activity.
On short displays, secondary controls scroll with the item list; the title, close
action and batch actions remain available instead of squeezing the list to zero height.

## Motion and loading

Loading and loaded layouts share the header and artwork bounds. The retained source
image is used during navigation, with matching destination corner geometry in both
directions. The gradient travels inside the artwork's animated bounds and clipping;
title text fades in the existing shared-transition overlay.
Sections fade through ModernMotion and respect reduced motion. Static skeletons
reserve space without a continuously running shimmer. Automatic refresh does not
display a pull-to-refresh spinner.

Tab changes fade only the section content: artwork and fixed chrome are not recreated
in an AnimatedContent containing a whole list. Progress and resume controls reserve
matching loading/resolved metrics, including larger fonts. Optional notices do not
show a speculative skeleton that would disappear when there is no notice; their
small dedicated lazy section handles size changes independently from the progress.
Opening inline search requests keyboard focus once, not again on returning from
another tab. Reduced motion switches tabs without an intermediate transparent frame.

Implementation names describe responsibilities rather than the visual design:
TitleDetailsScreen owns common presentation, the anime/manga adapters retain native
callbacks, DetailParts owns matching and selection, and DetailListLayout owns the
shared list navigation contract. The design name does not enter saved state or APIs.

Destination shapes use computed screen getters. They are never stored in Voyager's
serializable screen fields, so opening the player, reader or room Activity can save
navigation state without serializing Compose geometry.

## Validation

Automated tests cover index completeness, duplicate/fractional numbers, search across
groups, actual new releases, complete large lists, identity-based jumps, season
columns, range selection, selection across pages, missing IDs and shrinking lists.
Regression tests round-trip the actual anime
and manga screen state through Java serialization, including after accessing artwork
geometry. Device checks and any remaining limitations are reported separately from
compilation and unit tests.

The local 0.20.2.0 preview passed 982 unit tests, with seven existing live-integration
tests skipped and no failures or errors (989 tests total). The suite also covers
short-result viewport sizing, measured heights, empty results, large lists and
constant content height while keyboard padding changes.
Global Spotless checks, optimized preview assembly, signing verification and JNI
checks for all five APK variants passed. The arm64 APK updated the installed main
preview package in place, retaining the library and progress.

On a real SM-F741B, checks covered dark/light themes, portrait/landscape layouts,
320 dp width with 1.3 font scaling, fractional chapter labels, shared artwork in
both directions, keyboard dismissal and selection, sharing, and entering/returning
from the room screen. Native video playback and manga reading preserved the actual
resume position after returning. No new crash occurred in the final device checks.
These checks did not create a room or verify a multi-device room session, and do not
constitute a guarantee of zero frame drops on every device.

The completed 0.20.2.0 device checks additionally covered a complete 1,076-chapter
list, the final index group, native filtering and long-press selection, and retaining
the actual chapter/page after returning from the reader. Inline searches with one
or zero results kept identical control positions before/after closing the keyboard
at normal width. The single-result check also passed at 320 dp with 1.3 font scaling.
Returning from Details retained
the query without reopening the keyboard. Temporary display, theme and rotation
settings were restored after the checks.
