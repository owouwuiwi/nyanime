# Store

The catalogue starts with updates, then shows installed extensions and available
extensions grouped by language. Search and the media filters apply to the full
list. The toolbar opens the configured catalogues and imports local files.
Installed packages remain manageable offline or without their catalogue.

The Store presents source layers as **bridges**. Source details show bridges
with matching package, source identity, publisher and version. If the catalogue
provides these details for an available source, users can see its bridges before
installing it. Each bridge supplies its description and features in its signed
manifest. The source details show active bridges and link to the per-feature
settings, including the choice to use the original source. Bridges without a
matching source stay visible in the list. The Bridge filter shows every bridge,
and updates appear at the top. See [source layers](source-layers.md).

## Install or Import

The detail sheet offers two choices for the same publisher-signed APK:

- **Install:** Android installs the package; compatible hosts can discover it.
- **Import:** Nyanime loads an immutable private copy, with optional automatic
  updates that do not launch Android's installer.

The last successful choice supplies the default for new packages. Existing
packages show their active mode, version and fingerprint. Addons declaring
registered Android components require **Install**. Bridges use **Import** and
request separate approval of their signed features. Local import, catalogue
downloads and restore use signed APKs.

When adding a source, the Store includes its compatible bridges in the same
confirmation. It imports the approved bridge APKs once the signed source
installation is complete and rechecks compatibility with the installed source.

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
keeps progress and results when changing screens. The list reserves icon space
and keeps installed extensions visible during refresh. Sorting and searches
run off the UI thread.

Catalogue setup accepts a direct HTTPS index or a shared Google Drive folder
containing a single `index.json`. For catalogue and package compatibility, see
[interoperability](extension-interoperability.md).
