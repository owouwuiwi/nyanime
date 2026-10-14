# Manual extension import

The Store has **Load RIN** and **Add catalogue** beside its shelves. Load RIN uses
the Android document picker. The same action recognises a new package or an update
and displays its name, installed/new version and publisher fingerprint before the
user confirms. The original file may have any document MIME type; contents must
be a signed, compatible RIN. No destination or Android APK installation is needed.

Local import does not require a catalogue. After signature and compatibility
verification, the user explicitly approves the selected package and its publisher;
this does not grant blanket trust to other extensions. A removed catalogue does
not prevent a compatible manually approved update. The Store accepts unified HTTPS
catalogue JSON links; the video and manga catalogue managers also accept their
existing native catalogue formats through the restored address field. Website and
deep-link import remain available and keep their review/confirmation flow.

Local archives are bounded to the same 32 MiB limit, signatures/hashes are verified
through `RinArchive`, and versions use the same immutable staged storage. Preparing
a preview does not instantiate executable classes. Confirmed imports share the
Store install coordinator and remain active if the user leaves the screen. Busy
reader/player/addon sessions reject replacement. A changed registry is checked
again before atomic activation, preserving enabled state, publisher, medium and
source IDs. Downgrades and changed bytes with the same version are refused. An
already-installed identical file is reported as available without another install.

APK-to-RIN migration remains the existing migration flow. Failed/invalid/cancelled
imports never overwrite the active version; temporary input copies are removed.
Staged immutable versions follow existing cold-start pruning. No catalogue endpoint,
source name or source parsing rule is built into this interface or its tests.
