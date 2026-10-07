# Store

The Store has two shelves: **My extensions** and **Discover**. The installed shelf
opens by default. RIN packages appear first; other Android extensions and addons
remain in a separate section. Search, video/manga/news/addon categories and status
filters compose. Discover respects the chosen languages and adult-content filter;
installed packages stay manageable regardless of those filters or catalogue
availability. Installed RIN identity, icon, version and languages come from local
signed manifests even while disabled or offline.

## RIN management

Each installed RIN has a package activation switch and an Open action. A disabled
package offers Activate. Disabling removes its source implementations from the
runtime while retaining its module, preferences, library, tracking and progress.
It continues receiving authenticated automatic updates and remains disabled after
an update. Enabling validates its implementation before changing the active state.
Playback, reading and active Cast sessions prevent activation changes or removal.

A details sheet provides source selection, source settings and a separately
confirmed uninstall. Removing a module never removes library or progress. RIN
installation and automatic updates use the existing authenticated repository;
there is no manual update requirement or version-retention switch. Former retained
version flags no longer freeze updates, including when restored from older backups.

The Legacy-to-Ready recommendation/replacement workflow has been retired. Its
staged installers and checkpoint are cleaned up. The mandatory startup migration
from an installed APK to an eligible RIN remains separate and unchanged; see
[the RIN contract](rin.md). Ordinary APK installation, publisher trust and verified
updates remain supported for catalogues which do not offer RIN.

## Layout and performance

Native Material components use the app theme. A lazy adaptive grid reserves icon
space, uses stable package/origin keys and creates only visible cards. Search and
sorting run outside the main thread without loading source implementations or
starting requests. Refresh retains installed cards. Activation and downloads show
pending state; list rearrangement respects reduced-motion settings. Filter sheets
wrap status controls and scroll with large text. Catalogue management and source
migration remain accessible from the header.

Package installation is coordinated independently from the current screen.
Duplicate requests cannot install the same package twice, and stale callbacks
cannot overwrite a newer operation. Authentication, factory validation and download
work retain the existing RIN error boundaries.

No source names, catalogue URLs or source-specific rules are embedded in the Store
or its tests. Catalogues supply all extension identities and capabilities.
