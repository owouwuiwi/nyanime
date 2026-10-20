# Episode sections

The Android detail page can render expandable sections without learning provider
names, addresses, HTML, numbering conventions or catalogue rules. The normal flat
list remains the fallback. This feature is currently private NyanimeB work.

## Optional episode metadata

An extension may attach this JSON object to `SEpisode.memo`:

```json
{
  "nyanime.episode-group.v1": {
    "id": "opaque-stable-group-key",
    "title": "Published collection title",
    "order": 0
  }
}
```

`id` is an opaque, case-sensitive key unique within the owning entry. It remains
stable across refreshes and is not derived or interpreted by the host. `title`
is the source's displayed label, including named collections or specials.
`order` is the source's declared zero-based ordering. Limits are 1,024 characters
for the key, 200 for the title, and 0–100,000 for order; control characters are
rejected. Unknown JSON fields are ignored. Malformed grouping falls back to an
ordinary episode; it never makes a playable row disappear.

No new extension method or mandatory API upgrade is required. Hosts that do not
implement this optional key retain the original list. Extensions compiled against
older APIs may use the same optional memo accessor convention as existing card
presentation metadata. Neither `scanlator`, URL text nor title patterns are
implicit season declarations. Sources created in Studio can opt into this same
contract when they expose episode memo data; the host does not add site rules.

Missing grouping in a partial update retains saved valid grouping. An explicit
JSON `null` under this key clears it. The normal episode backup memo field already
carries this object, so there is no backup or SQL schema migration.

## Native season entries

The existing source API can instead return separate season entries. Expanding a
season subscribes to its saved episodes and only then performs a due/missing-data
refresh. Requests are deduplicated and share one lane per source. Search does not
fetch all unloaded seasons: their headers remain visible and can be opened.
Nested season containers retain access to their own detail page.

Every child action uses the real episode ID and its owning anime ID: playback,
downloads, deletion, quality selection, sharing and tracking must not use the
parent container as the episode owner. Flat snapshots are not forcibly converted
into child entries. Existing episode IDs, URLs, progress and download paths remain
unchanged when an extension starts providing grouping metadata.

## Presentation and progress

The resume section opens initially; other sections can be expanded independently.
Expanded keys and list position survive ordinary navigation and state recreation.
Search reveals matching cached rows across closed sections; index and resume
navigation expand the target before scrolling. Rows remain individually lazy and
keyed by real identity. Motion only fades rows and rotates the chevron; it does not
animate the height of thousands of episodes.

Headers count all known episodes and their watched states, while rows respect the
existing filters. Unassigned rows go under a localized other-episodes header only
when at least one declared group exists. Episode-number sorting respects declared
group order. Number-based duplicate progress is scoped to the group, including
newly enriched snapshots, so the first episode of two seasons is not one episode.

## Verification status

The 19 October 2026 candidate passes 42 focused checks: metadata validation,
sorting, partial updates, backup round trips, resume, filtered group totals,
cached and unloaded sections, real owners, source request serialization and
5,000-episode presentation. Unit tests use synthetic titles and opaque keys only.
Extension parser tests and provider-owned markup remain in the companion
repository.

The signed arm64 candidate also passes package identity, signature, archive,
16 KiB alignment, optimized ViewModel ABI, JNI and attribution-inventory checks.
Its native library inventory is unchanged from the preceding private candidate.
These checks do not replace a native/source licensing audit or physical UI
validation.

On the USB-connected physical phone, the candidate displays 9 declared sections
across 198 episodes and 5 collections across 21 episodes. A read-only comparison
before and after refresh confirms all 219 episode IDs, URLs, bookmarks, watched
flags and playback positions are unchanged, including all 8 previously watched
episodes. Expanding/collapsing sections and searching for an episode in a closed
section were checked through the UI. A refreshed 26-episode entry also opened in
the existing player and reached its playing state. The private diagnostic APK
was removed after inspection; the updated extension remains imported normally.

The separate-entry season path, unloaded/error handling, backup round trips,
large lists and download ownership are covered by focused tests. They are not
claimed as physical-device coverage of every extension or network condition.
No push, release or OTA is authorized for this work.
