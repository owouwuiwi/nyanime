# Manual extension import

**Import extension** in the Store opens Android's document picker. Choose a
publisher-signed APK or an existing `.rin` archive. No catalogue or destination
is required. The screen shows the verified name, version, installed version and
publisher fingerprint before the user approves execution. Preparing this preview
does not instantiate extension classes.

Component-free APKs load privately. An addon requiring registered Android
components explains that Android installation is required. The file's extension
and document MIME type are not proof of its contents; APKs are not converted to
legacy archives by renaming them.

Standalone APK imports have manual updates until a compatible origin is
explicitly associated. Catalogue-added imports retain their known origin;
removing the catalogue does not prevent a compatible manually approved update.
Catalogue address entry, website links and deep links retain their review flow.

Both formats use immutable storage and the same Store operation coordinator.
APK signatures authenticate all bytes; old archives retain their original signed
manifest and hash verification. Limits are 512 MiB for APKs and 32 MiB for legacy
archives. Failed input copies are removed; active files are never overwritten.

Reading, playback, Cast or active addon work defers replacement. The registry is
checked again before atomic activation. Publisher, medium, source identities and
disabled state are preserved. Downgrades and changed bytes at the same version
are refused. Identical APK mode changes verify the same artifact hash.

Backups preserve private mode, automatic-update preference and addon storage
identity. Newly received executable packages stay dormant until approved locally.
No catalogue endpoint, source name or parser belongs in the core or its tests.
