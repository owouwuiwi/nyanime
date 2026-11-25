# Manual APK import

**Import APK from file** in the Store opens Android's document picker. Choose a
publisher-signed APK directly. The screen shows the verified name, version, installed version and
publisher fingerprint before the user approves execution. Preparing this preview
does not instantiate extension classes.

Component-free APKs load privately. An addon requiring registered Android
components explains that Android installation is required. Nyanime validates the
Android package and signature before it displays the preview.

Standalone APK imports have manual updates until a compatible origin is
explicitly associated. Catalogue-added imports retain their known origin;
removing the catalogue does not prevent a compatible manually approved update.
Catalogue address entry, website links and deep links retain their review flow.

Imports use immutable storage and the Store operation coordinator. APKs may be
up to 512 MiB. Failed input copies are removed and active versions are retained.

Reading, playback, Cast or active addon work defers replacement. The registry is
checked again before atomic activation. Publisher, medium, source identities and
disabled state are preserved. Downgrades and changed bytes at the same version
are refused. Identical APK mode changes verify the same artifact hash.

Backups preserve private mode, automatic-update preference and addon storage
identity. Newly received executable packages stay dormant until approved locally.
No catalogue endpoint, source name or parser belongs in the core or its tests.
