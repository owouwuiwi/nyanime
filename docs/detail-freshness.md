# Targeted detail freshness

Explicit detail opens are independent of background release eligibility. Cached content is presented
first; only the opened title is checked. Hidden library categories, completed works and titles outside
the library are not exclusions for this path. Background library policies are unchanged.

The host records the SHA-256 of the exact APK loaded, the verified hash of an imported package, or the
active Studio document fingerprint. No source name, domain, selector or parsing rule is part of the
policy. Extension interfaces do not gain mandatory methods. Existing title and item identifiers are
preserved by the normal metadata/item synchronization interactors; metadata changes are not new items.

The detail coordinator serializes each entry and rechecks freshness after acquiring the lock. A bounded
private preference cache holds hashed entry identities and revision/timing metadata, with a ten-minute
success lifetime and exponential error backoff from one to fifteen minutes. A changed revision bypasses
the previous revision's backoff. Manual pull-to-refresh bypasses freshness. Incognito opens do not read
or write this persistent cache. Downloaded-only mode suppresses automatic remote detail checks.

At most two detail checks for a source can run simultaneously (bounded semaphore stripes). Existing
fetch-and-commit entry gates remain in use. Network work occurs outside the source publication lock.
Source publication and application of the fetched database snapshot share a lock: once a replacement
is published, an older response is rejected without marking the new revision fresh. Cancellation is
not a failed source check. Unavailable sources keep their saved details.

There is no startup, extension-update or library-wide network scan. The first open after an app upgrade
checks that one title. Successful freshness survives reopening and process restart; local device clock
rollback makes the stamp due rather than leaving an indefinite cache.
