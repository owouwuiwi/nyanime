# Installed and imported extensions

Nyanime accepts publisher-signed APK extensions. The catalogue offers **Install**
(Android installation, shared with compatible hosts) and **Import** (private to
Nyanime). Both modes use the same artifact and verify its publisher, version and
API before activation. Legacy `.rin` files remain readable; they are not APKs and
cannot be converted by renaming them.

## Public contracts

| Family | APK feature | Entry metadata | API |
| --- | --- | --- | --- |
| Anime | `tachiyomi.animeextension` | `tachiyomi.animeextension.class` | Existing 14/16/17 contracts |
| Manga | `tachiyomi.extension` | `tachiyomi.extension.class` | Existing supported manga profiles |
| News | `nyanime.newsextension` | `nyanime.news.factory` | `nyanime.news.api=1` |
| TV | `nyanime.tvextension` | `nyanime.tv.factory` | `nyanime.tv.api=1` |
| Guide | `nyanime.guideextension` | `nyanime.guide.factory` | `nyanime.guide.api=1` |
| Hosted addon | `nyanime.addon` | `nyanime.addon.factory` | Declared hosted addon API |

Keep package names and source IDs stable. A catalogue cannot change an installed
publisher, reinterpret a source ID, impose an uninstall, or select another work
by title similarity. Each source remains responsible for its own website and
transport rules; the host contains no provider adapters or default catalogue.

### Optional capabilities

Base anime/manga factories must link only against the chosen upstream-compatible
compile-only API. Do not put Nyanime-only interfaces in their supertypes, method
signatures or implementation dependencies. A matching numeric API version does
not prove matching JVM signatures or shared dependencies.

Use existing declarative Home assets where possible. For code capabilities,
declare a **separate** factory through `nyanime.optional.factory` implementing
`nyanime.extension.api.SourceCapabilitiesFactory`. Nyanime calls `attach` after
the trusted base sources have loaded. The resulting sources retain the same
ordered medium/source identities. If an optional implementation fails, the base
sources remain usable. Other hosts do not load the optional factory.

For hosted TV, guide and addon APKs, place the normalized package declaration at
`assets/nyanime/package-v1.json`: schema, package identity, version, family, API,
entry points, source identities, optional declarations and host version bounds.
Its identity must agree with Android metadata. APK signatures authenticate the
whole artifact; the declaration does not need a second payload signature.

An addon requiring Android activities, services, providers or its own application
runtime must declare `nyanime.extension.requires_android=true`. It supports
Android installation only. A component-free `HostedAddon` or
`EpisodeGuideProvider` can use private import. These names extend the existing
interfaces, preserving their binary contract.

## Consent and updates

Adding a catalogue does not approve executable code. Users can approve each
selected extension, or explicitly trust current and future extensions they
choose from that catalogue. This option starts disabled and can be revoked.
It never installs every catalogue entry or bypasses signature validation.
Execution approval and update origin are separate decisions.

Imported extensions default to automatic updates. Checks reuse a six-hour
network-constrained worker and are deduplicated when the app resumes. An update
must come from the associated origin and preserve publisher/API/source identity.
Verified certificate rotation can preserve publisher continuity; a new unrelated
signature cannot silently replace it. Per-extension automatic updates can be
disabled. Imports without a known origin remain manual.

Each version has an immutable executable file. The active pointer changes only
after validation and preflight. Playback, reading, Cast and active addon tasks
defer activation; failure retains the previous version. Old executable files are
retained for existing objects and collected at a later cold start.

## Verification requirements

The starter pins upstream API artifacts and checks compiled JVM references before
D8, as well as checking that host APIs/shared libraries are not bundled into DEX.
Anime/manga interoperability is conditional on the target host implementing that
profile. TV, news, guide and addon APIs require hosts implementing those families.

Before claiming support, install the **same signed APK** privately and in Android,
test its search/details/content path, then test the declared profile in a released
compatible host. Signature tests, compilation and ABI checks alone are not real
device evidence. All host regression fixtures must remain synthetic and generic.
