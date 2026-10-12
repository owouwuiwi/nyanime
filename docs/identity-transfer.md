# Local transfer between Nyanime identities

The ordinary build keeps its existing package and certificate. The new identity is
selected explicitly with `-PproductionIdentity=true` and signed with the protected
production certificate. Publishing the new identity is a separate release decision.

After an ordinary update, configured legacy installations receive a one-time optional
offer to download or open the pinned production package. Deferring keeps the old app
usable, and Data settings retain the same action. Clean installs and the production
identity do not receive that offer. Successfully transferred sources do not prompt again.
The download action checks published release metadata and compatibility before opening
an actual production APK asset; unpublished versions do not open guessed download URLs.

The previous app grants access only to the pinned receiving package and certificate,
after confirmation on the device. No relay, web endpoint or extension-specific logic
participates in the transfer.

## Data and failure handling

The previous app briefly stops its own other processes to prepare a coherent snapshot.
The snapshot includes private databases, settings, local extension files, WebView data,
private media files and a portable complete backup. Shared folders retain their files.
After explicit consent, the pinned receiver retains a delegated Android tree grant.
If delegation is unavailable, the user selects the same folder in the Android picker.
Folder access is checked before snapshot preparation and before any receiving files are replaced.
Android may require the receiving app to authorize the same folder again. Device-bound
credentials are decrypted locally and rewrapped under the receiving app's Keystore.
Retired social data, caches and background-work scheduling are excluded.

The preparation lease expires within ten seconds of exporter process death and is
renewed while preparation is active. Once the encrypted snapshot exists, the previous
app is immediately usable. Reading the snapshot does not renew its launch lock.
Cancel or failure revokes the source grant and releases preparation when the exporter
acknowledges cancellation. The previous app is never uninstalled automatically. Its sync pauses during transfer and is disabled after successful verification, so both installations cannot publish as the same copied device by default.

The receiving app stages and hashes files before opening a journaled replacement.
A failure restores its previous files; process death resumes journal recovery before
normal startup. A failed rollback keeps the journal and offers recovery retry rather
than opening partially copied data. Removal of the old app is offered only after
verification and journal cleanup. A transfer can also be retried from Data settings
after initially configuring without data.

Preparation and verification use indeterminate progress, while copying shows a percentage
and copied/total bytes. The completed screen survives rotation and offers opening the app.
On the first normal entry after a verified transfer, the receiving app offers removal of
the previous app. Deferring suppresses repeated prompts and preserves a removal action
in Data settings. Both paths recheck verification, journal cleanup and the pinned old
identity before opening Android's confirmation; they never uninstall automatically.

Database replacement journals the complete SQLite file family, including destination
WAL, SHM and rollback journals absent from the incoming snapshot. A destination journal
must never be replayed against an unrelated copied database. Rollback restores the
original family together; verification-generated sidecars are removed on rollback.

Protocol 2 requires a source bridge of version code 219 or later, with a receiver no
older than the source. This prevents an older database schema being opened silently.
Handled errors record phase, exception class and code locations, without exception
messages, tokens, URLs, credentials or user filenames. A full disk cannot break the
error reporting path.

## Validation

JVM checks cover archive limits and integrity, authenticated encryption, destination
path boundaries, SQLite file-family isolation, removal eligibility, the optional
upgrade offer and lease expiry/reboot/clock corrections.
USB device probes are separately signed and installed only for authorized local tests.
Both USB and wireless devices may be tested only when the user explicitly authorizes them.
Device validation results and APK hashes are recorded locally, outside Git.
