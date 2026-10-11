# Manga Home capabilities

Manga source APKs may expose the same declarative asset as anime sources:
`assets/aniyomi/home-v1.json`. The manga registry only inspects installed,
trusted sources returned by the manga extension loader, including private APKs.
Package ownership, source name and language must match the installed instance.
Disabled sources and languages do not expose a Home.

The unified Home shows each installed reading category declared by the extension,
using its stable `id` and display `title`. Equal declared IDs share a category;
video and reading categories use separate navigation namespaces. The default
reading category remains available without a compatible extension. Library search,
categories, downloads, selection actions, title details and the reader keep their
existing routes.

The category ID describes the reading kind, not the provider or its language:
use `id: "manga"` for providers contributing to the Manga category, and declare
the language separately in `source.lang`. A language suffix creates a different
category even when its display title is identical. Titles are presentation only;
they never establish work identity. All featured declarations in a shared category
contribute to its carousel, including differently named sections and shared sections
whose other providers choose a different layout.

## Presentation data

The stable SManga interface is unchanged. Hosts may additionally implement
SMangaHomeMetadata on objects returned by SManga.create(). Its public
setHomePresentation(String) setter accepts optional versioned JSON. Extensions
compiled against an older API can discover this public setter; if it is absent,
they must still return ordinary valid SManga entries.

Example presentation:

```json
{
  "version": 1,
  "id": "chapter-event-18",
  "badges": ["Manga", "In corso"],
  "details": ["Letto: 12500 volte"],
  "rank": 1,
  "chapters": [
    {"url": "/series/example/read/18", "label": "Volume 03 · Capitolo 18", "date": "12 settembre", "isNew": true}
  ],
  "sectionTitle": "Ultimi capitoli"
}
```

The host bounds this payload to 8192 characters, six short badges/details and
five chapter actions. Unsupported versions and malformed optional metadata are
ignored. Chapter URLs must be relative source paths; protocol-relative URLs,
external URLs and control characters are rejected.

Event identity is separate from library identity: two trending chapters may share
one manga URL and local manga ID. Metadata lives only in the Home item, never in
the manga description or library fields. Source artwork is presented without
replacing library custom covers or changing chapter flags and reading progress.

## Layouts and archive links

Manga sections support chapters, updates, ranking, featured and posters layouts.
Unknown layouts fall back to posters. Section order and filter choices come from
the extension. The updates layout displays all provided chapter actions and dates;
ranking displays the supplied position and details.

An optional moreFilters map on a section opens the existing source catalogue with
those public select-filter values. It uses the same bounded label/value rules as
filters. Unsupported archive filters hide only that optional action. Categories
and search also use the source's public filters and existing catalogue screen.

## Requests, navigation and privacy

Each request creates fresh filters, runs off the main thread and is limited to
30 seconds. Two concurrent requests are allowed. Access is checked again before
results are accepted. Per-section failures are retryable and retain existing
content while refreshing. Pagination deduplicates presentation identities.

Mixed feeds preserve both failed refreshes and partial provider errors through
identity enrichment. A failed response is not a successful empty page. Single-provider
feeds do not fetch per-title details merely to enrich identities: no cross-source
merge is needed. Resolving alternatives on explicit user action stays available.

Optional `browseFilters` declarations support bounded single choices, checkbox
groups and text controls using the same rules as video Homes. The registry exposes
only supported controls; requests reject removed or incompatible values before
contacting the provider. The extension remains responsible for applying selections.

A chapter action resolves the exact local chapter URL. If missing, it fetches
and synchronizes the source chapter list, then resolves that URL again. It never
guesses by chapter number and never inserts a partial chapter list that could
delete other chapters.

Continua a leggere uses the existing local history and chapter order. In Solo
scaricati no Home feed requests run and resume selects an available download.
Incognito hides local reading history and changes to extension access discard
Home rows. There is no additional persistent manga Home cache.

Local history and update rows also observe source registration and initialization.
After an app upgrade or a cold start, stored rows must become visible when their
extensions finish loading, even if neither the database nor user preferences change.
Extension replacement reevaluates visibility without deleting or rewriting history,
chapter progress or bookmarks. This observation does not fetch a source Home or
refresh a chapter list.

## Declared reading categories

The same category identity is used by Home, Atlas search and exploration covers.
Only providers declaring that identity contribute its remote sections, genres
and archive routes. Local resume and update rows follow those declarations;
sources without a Home remain in the default reading category. A category with
no enabled provider disappears and an active removed category returns to the
default reading Home. Source initialization is observed without starting network
requests. NSFW, language, disabled-source and incognito preferences continue to
control access. Category names and site-specific parsing remain extension-owned.

Local reading is classified using the registry's validated declaration snapshot,
including declarations whose remote Home is hidden. Hiding a category cannot move
its stored history into the default category. History and update row limits are
applied after category selection, so activity in another category cannot displace
its resume entries. These rules do not modify stored progress.
