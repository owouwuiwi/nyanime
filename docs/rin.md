> Compatibility archive documentation. From Nyanime 0.45, new extensions
> use signed APKs with Install or Import. Legacy RIN remains readable; no forced
> APK-to-RIN migration, precedence, catalogue authority or uninstall runs.
> See [extension interoperability](extension-interoperability.md).

# Nyanime RIN

RIN v1 retains Kotlin and the existing source APIs. A `.rin` is a signed ZIP of
DEX files and source-owned assets, not an Android application. APK extensions
and optional Android addons remain supported alongside RIN.

## Bundle and catalogue

`manifest.json` declares schema, package alias, immutable version, medium, API,
fully qualified entry points, exact 64-bit source IDs and SHA-256 payload hashes.
`META-INF/rin.cert` contains the publisher's X.509 certificate;
`META-INF/rin.sig` signs the exact manifest bytes with SHA256withRSA or
SHA256withECDSA. Catalogue downloads require the catalogue's approved publisher
fingerprint. Manual file imports verify the embedded certificate and ask the user
to authorize that package before any executable code is loaded; no catalogue is required.
Every executable and asset is included in the signed hash map. Unexpected files,
duplicate ZIP names, traversal, oversized contents and invalid identities are
rejected before class loading.

A configured catalogue may additionally declare
`rinList: { version: 1, extensions: [...] }`. Each entry supplies `manifest`,
`url`, `sha256` and `signer`. Older clients continue reading `extensionList`.
Source IDs, package aliases and preference keys remain unchanged; display names
never establish shared identity or trust. Home and distribution contracts stay
inside source-owned assets. No source-specific rules belong in the host.

Live TV uses schema 2, `medium: "tv"`, TV API 1, one `TvSourceFactory` entry
point and explicit `minHostVersionCode` / optional `maxHostVersionCode`.
Its optional `rinTv: { version: 1, extensions: [...] }` catalogue is separate
from the existing `rinList`; older clients keep their supported catalogue.
Signature, payload hashes, exact source IDs and class-loading rules remain
mandatory. The parent host owns the `nyanime.tv.api` ABI.

Existing signed APK releases may be converted without recompiling: the publisher
checks that every DEX file and original asset is byte-for-byte identical. The
initial format conversion can retain the original version only when the previous
APK hash, signature, source IDs and build inputs match. Subsequent RIN updates
remain immutable and require an increased version for changed components.
An optional signed `assets/nyanime/rin-icon` retains the original extension icon.
The icon uses PNG, JPEG or WebP so it remains available without a network.

A publisher may explicitly list `dormantSources` in the signed manifest when an
edition was deliberately disabled in an earlier source update. These historical
IDs remain valid for migration and later updates; they do not create source
instances or make inactive editions accessible. Active and dormant IDs must be
unique, and an update cannot silently discard either set.

## Legacy transition

Startup APK-to-RIN migration and format precedence have been retired. Existing
archives keep their identities and data; changing to a signed APK is an explicit
compatible package update, without catalogue-imposed uninstall or replacement.

## Activation and updates

Store activation is independent from installation. A disabled RIN keeps its signed
module and local settings but registers no source instances. It remains listed
without a network and continues to update automatically while disabled. Enabling
preflights factories before registering sources. Removing, enabling or disabling
is deferred while playback, reading or Cast is active. Library and progress survive
both disabling and uninstalling a module.


Versions live in app-private storage, made read-only before executable bytes
are written. Activation replaces an atomic active pointer; existing source
instances retain their immutable files. Updates run every six hours and when the
application is reopened. Only already installed RIN packages are eligible.
Refreshing the Store also applies newly discovered installed-package updates,
without waiting for the next interval or fetching the catalogue twice. Interrupted
updates schedule a persistent network-constrained retry. The Store keeps the
installed source available through Open while its replacement is prepared.
An available catalogue entry never authorizes installing an additional source.
Player, PiP, reader and active Cast sessions defer activation. Ordinary updates
do not invoke Android installers or show notifications.

The shared Kotlin, coroutine, source and network ABI classes are parent-first;
extension implementations and private dependencies retain child-first loading.
Linkage, factory and initializer failures become recoverable package errors before
legacy removal. Cancellation and fatal VM failures are never disguised as success.
The separate crash-screen process does not start source loading or background work.

Failed or deferred updates retain the previous working version and remain due.
Publisher, catalogue, medium or source identity changes are not silently
accepted. Removal affects only the source module, never the user's library.
Backups mark RIN payloads separately while retaining older APK protobuf fields.
Restoration authenticates the package against the imported catalogue's trust.

RIN code executes with the host's permissions, like existing source extensions;
signatures bind the file to a publisher key, not a sandbox or an endorsement.
Users explicitly authorize a manually selected package, or choose a trusted
catalogue for catalogue downloads. Local approval does not trust other packages
from the same publisher. Manual updates retain package, signer, medium, source
identities and the existing origin, including when that catalogue has been removed.
A standalone import has no update origin; adding a catalogue later does not
silently link it to automatic updates.
