# Store

The Store groups installed and imported extensions in **My extensions** and
offers the configured catalogues under **Discover**. Anime, manga, news, TV,
guide and addon families use one inventory. No format is ranked above another.
Search, language, content and state filters compose; installed packages remain
manageable offline or without their catalogue.

## Install or Import

The detail sheet offers two choices for the same publisher-signed APK:

- **Install:** Android installs the package; compatible hosts can discover it.
- **Import:** Nyanime loads an immutable private copy, with optional automatic
  updates that do not launch Android's installer.

The last successful choice supplies the default for new packages. Existing
packages show their active mode, version and fingerprint. Addons declaring
registered Android components require **Install**. Existing `.rin` files remain
readable and updateable but are not APKs; no forced conversion or uninstall runs.

## Consent

Every new publisher requires user consent. An optional catalogue-wide permission
starts disabled, covers current and future extensions the user selects, and can
be revoked in catalogue settings. It does not install new extensions in bulk.
Adding a catalogue grants no replacement or uninstall authority. A changed
catalogue URL is not a package publisher identity.

Individual consent survives updates by the same publisher, including a verified
single-certificate rotation. Unrelated certificates cannot replace approved
packages silently. Previous individual approvals stay individual; they do not
become dependent on a catalogue-wide toggle.

## Activation, updates and failures

Download, hash/signature verification and factory preflight precede activation.
The selected package must preserve its family and known source IDs. Among copies
from the approved publisher the host selects the newest compatible version; ties
keep the active mode. Disabled packages remain disabled.

Imported automatic updates start enabled and have a per-package switch. A
network-constrained worker checks every six hours; stale app-open checks reuse
the same coordinator. Updates use only the associated origin. Imports with no
known origin show **Manual updates** and offer an explicit compatible-origin
selection. Busy playback, reading, Cast and addon sessions defer activation.

Immutable version files and an atomic active pointer retain the previous version
when verification or loading fails. Existing objects retain their executable
file. Interrupted transfers remain visible as errors after restart; an Android
installer cancellation is not reported as successful private activation.

The application-lived coordinator prevents duplicate package operations and
keeps progress/results when changing screens. The lazy adaptive grid reserves
icon space and keeps installed cards during refresh. Sorting and searches run
off the UI thread; no source factory runs merely to render a card.

No provider, catalogue endpoint or parser is embedded in this interface or its
tests. See [interoperability](extension-interoperability.md) and
[manual import](rin-manual-import.md).
