# Atlante

Atlante is the shared video and manga search surface. The bottom navigation is
Home, Library, Search, Releases and More. Browse remains available in More with
its source management, extension updates, migration and native source filters.

## Opening and navigation

Search turns the floating navigation surface into a text field. The header stays
outside the page transition so opening or closing Search never fades the logo.
Page changes use the existing ModernMotion fade. Genres and Filters
appear above the field; the normal Home no longer duplicates these controls.
The compact All selector opens the same categories in the same saved order as
Home. Categories come from installed Home capabilities, never provider names.

Opening Search starts on All with empty text and the keyboard closed. Initially,
Atlante uses bounded public snapshots already obtained by Home. It never starts
an empty search across all extensions to populate this view.

Existing installations receive a short, dismissible introduction to Search and
the Browse shortcuts after upgrading. Fresh installations do not receive it,
including on later upgrades. The installation-local marker is initialized before
the previous Android version is updated, works when preview revisions share a
version code, and is excluded from portable backups. Dismissing the guide or
choosing Try Search acknowledges it permanently on that installation.

Search settings, also available in Settings → Browse, offer:

- Remember last search: off initially; remembers text, category and genres.
- Open keyboard immediately: off initially.
- Search as you type: on initially, with a 350 ms debounce.

Submitting searches immediately. Back first dismisses the keyboard, then closes
Search and restores the previous tab and its position. Holding Search opens Browse,
including the search action inside the expanded field. Returning from a title
preserves the query, filters and grid position. A new query resets the grid to the
beginning. Incognito queries are not saved as a remembered search.

## Results and assistance

Video and manga have separate title search sessions and remain separate works.
Both sessions reuse core/smart-search, including equivalent spelling recovery,
numbered edition precedence, aliases, verified recovery cursors and exact mode.
Assistance occupies a reserved row above the results; tapping it opens alternatives
and the exact/intelligent search choice. A catalogue suggestion is never presented
as an available extension result or turned into an automatic title association.

Each route retains its own pages. Results appear progressively, keeping the
arrival order of existing cards when other routes or later pages complete.
Only verified catalogue IDs can combine copies from different sources. Conflicting
IDs are kept separate; video and manga are never merged. Combined cards preserve
their concrete source choices. Matching text alone cannot combine works.

The coordinator limits extension search operations to five at once. Video and
manga share a maximum of three external catalogue requests for the logical query.
Cancellation, retries, category changes and exact-mode changes do not replenish
that allowance. A changed query starts a new allowance. Older responses cannot
publish over the active query. Failed providers can be retried independently.

## Filters and privacy

Home-declared genre options are mapped back to their concrete source selections.
Only providers declaring the selected options participate. Multiple genres require
a declared multiple-value control; a single-choice control cannot silently drop a
selected genre. The filter sheet reports the compatible provider count and retains
access to each provider's advanced native filters. Changing categories keeps the
compatible genres and briefly explains any removal.

Providers without a Home remain searchable using their existing native search API.
Language, visibility and adult-content preferences continue to apply. Downloaded-only
mode prevents extension and catalogue requests; local library search stays separate.
Cached exploration excludes private providers and does not persist its snapshots.
If an incognito extension participates, its medium uses a private assistance
session; changing privacy scope does not replenish the catalogue allowance.

No additional extension method is required. All routes, controls, identifiers and
artwork are extension-owned data; the host and versioned tests contain no provider
specific rules or names.

## Verification

Synthetic tests cover strict identity merging, conflicting identities, distinct
media, native providers without Home, declared genre combinations, category scope,
advanced filter transport and the shared catalogue allowance. Existing title
search and source runner regressions cover recoveries, numeric names, paging,
partial failures, concurrency and non-cooperative stale responses.

Native layout, keyboard, themes, large text, transitions and navigation require
real-device verification. Unit tests and screenshots alone do not prove those
interactions or every installed extension's availability.
