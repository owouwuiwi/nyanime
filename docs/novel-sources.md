# Optional novel sources

This family is separate from anime/manga contracts and the retired `publication` medium. Nyanime ships no provider, address, catalogue, selector or site rule.

## Package declaration

The existing signed package/import pipeline accepts medium `novel`, schema2, API1. A novel package must include `assets/nyanime/package-v1.json`, explicit source IDs and a `minHostVersionCode` of289 or later. Normal host version bounds still apply. Missing declarations cannot fall back to inferred IDs.

The Android declaration uses feature `nyanime.novelextension` and metadata `nyanime.novel.api=1`, `nyanime.novel.factory=fully.qualified.Factory`. Component-free APKs can be imported privately through the existing installer. Legacy RIN wrappers retain their signature and hash checks.

The factory implements `it.lorelore.nyanime.source.novel.NovelSourceFactory`, or an entry point implements `NovelSource` directly. The source API and coroutine ABI use the host class identity; private implementation dependencies stay isolated by the existing extension loader. Loading executable packages requires the existing signer-bound execution consent. This does not sandbox arbitrary APK code.

Every loaded ID, name and language must match the signed active declaration. Duplicate IDs within a package are rejected. Duplicate novel IDs across active packages remain unavailable instead of selecting whichever loads first. Dormant declared identities can survive package updates without being instantiated. Adult capabilities require the package content declaration and respect visibility preferences.

## Content contract

Search has independent opaque cursors and declared filters. Entries, aliases, details, chapter references and resource references are bounded DTOs. Unknown provider numbers and release dates remain unknown. A title match never establishes identity; the key is the source ID plus its exact book reference.

Chapters must be returned in reading order. The host preserves that order rather than guessing from titles or sorting missing numbers. Book and content responses must retain the exact requested reference. Repeated image HTML references may only identify the same retrieval reference; conflicting declarations are rejected.

Chapter content explicitly declares text or HTML. Resource references are supplied by the source. Request headers are transient session data and are not portable book metadata. The local EPUB/TXT reader remains archive-only and does not obtain a network client from this capability.

The resource bridge uses HTTP/HTTPS only. Required cookies, authorization or referer headers must be explicitly supplied by resourceRequest; unrelated host cookies, authenticators, interceptors and header logging are excluded. Cross-origin redirects discard session headers and HTTPS downgrades are rejected. Return a directly resolved resource request when a different origin needs its own authorized headers. Reading a cached chapter does not require loading provider code; a cache miss rechecks the installed revision and execution consent.

## Implementation status

### Optional Home and chapter groups

`NovelHomeSource` is an optional capability alongside `NovelSource`, not a new
mandatory method. A source declares up to 16 distinct `NovelHomeSection` values
and returns bounded `NovelSearchPage` title metadata for `NovelHomeRequest`.
The Home never substitutes an empty search, prepares a chapter index, fetches a
body, or adds discovered titles to the library. Sources without this capability
retain their normal per-source search through **Explore your sources**.

The visible Romanzi Home loads declared sections progressively with at most
three concurrent requests and a 20-second timeout. Its memory-only cache holds
at most 32 section previews for five minutes, with at most 24 distinct titles
per preview. Refresh bypasses that cache; source identity/revision and access
are checked before use and after a response. Downloaded-only mode performs no
source discovery, obsolete requests are cancelled, and incognito changes clear
the in-memory cache. Source covers keep using the existing artwork broker.

The Romanzi category observes local declarations from each internal registry,
including active Studio definitions. A source does not need a library favorite
or an unrelated APK to expose the category. This availability check never
instantiates an APK factory, opens a chapter index or starts Telegram. Registry
eligibility and execution authorization reuse the existing policies; metadata
availability itself is not an execution grant. Language/adult visibility and
duplicate identities follow the unified inventory's rules.

Studio sources reuse their existing `home`, `homeFlows` and section filters.
There is no separate graph format or provider-specific discovery path. An APK
that chooses this capability must require host versionCode 290 or newer;
existing API-1 sources without it remain compatible.

`NovelChapter.group` supplies optional grouping labels for the details screen
and the reader's index. The host does not infer arcs, volumes, seasons or chapter
numbers. Groups follow first appearance, retain source order within each group,
and initially expand the current chapter's group (or the first group). Chapter
search reveals matching chapters/groups without overwriting the user's collapsed
state. The actual reading order, references, locators, downloads and progress
remain unchanged. Local EPUB table-of-contents nesting remains available.

The0.80 delivery connects the contract, package inspection, consent-aware loader, hosted inventory, Store family, enablement screen, Atlante, online reader, durable downloads and Studio Novel. Registry metadata is observed lazily; execution remains consent-gated. Automated tests cover identities, opaque cursors, access, preparation and stale responses. Private device candidates also exercise the real Store, reader, downloads and Studio; final APK receipts identify the exact release checks. Legado import remains deferred and is not an app dependency.

Existing anime/manga APIs remain unchanged. Retirement cleanup matches only the old `publication` medium, with a regression test retaining `novel` records.
