# Production identity and local migration

The production application ID is `it.owouwuiwi.nyanime`. Its RSA-4096 certificate is pinned in
`app-identity.properties`; private signing material never belongs in Git or release bundles.
The original application remains a distinct identity. Its transition APK must retain its original
package and certificate so Android can update it without deleting its data.

## User flow

1. Update the previous installation to the transition build, then install the production application.
2. The new application detects only the verified previous identity. Choose **Bring my data**,
   grant Android access to the same shared storage folder when required, and explicitly authorize
   the transfer in the previous app.
3. Preparation, transfer and verification stay visible. The previous app remains installed.
   Check the library and downloads, then choose **Remove the old app** to request Android's
   uninstall confirmation. This action is available only after verification, without a pending
   transaction, and checks the previous application's pinned identity again before opening Android.

The settings page also exposes migration when the previous identity is installed.
Starting without migration remains possible.

## Transfer boundary

The provider checks the caller UID, exact destination package and pinned production signer.
Authorization expires, cannot be supplied through ordinary links, and is independent of addon trust.
Only the application's own processes can be paused. During a snapshot or destination commit, normal
startup cannot open the databases or launch background work.

Private databases, preferences, source documents, custom covers and private downloads are copied.
Database IDs remain stable, preserving ID-based local settings. Shared files stay in their original
tree; its identity and metadata inventory are checked after Android grants the new UID access.
Caches, WorkManager's device-bound state, recovery grants and retired community data are excluded.

Sensitive values owned by Keystore adapters are unwrapped by the source and rewrapped by the
destination. Android Keystore private keys themselves are not exported. Server-side revocation,
expired sessions and third-party device binding can still require signing in again.

Large payloads use authenticated 256 KiB encryption frames and encrypted persistent transfer blocks.
Every archive file has a checksum, extraction is bounded, and unexpected roots, traversal, mismatched
manifests, truncation or altered transport cause failure. A destination journal restores original
files after a failed commit or interrupted process. Independent verification opens the copied
databases in a fresh process and compares complete backup contents.

Execution consent is not granted by a signature alone. Existing explicit local approvals may remain;
fresh RIN packages restored from ordinary backups start disabled until enabled by the user.

## OTA and publication

Production assets use `Nyanime-production-<version>-<abi>.apk`. Legacy assets retain their historic
name patterns and certificate. Updaters select only their own identity and downloaded APKs must match
the installed application's package and signer.

The two APK sets are built from the same source with `-PlegacyMigration=true` for the transition set.
The production key is restored from encrypted repository secrets, never from Android's debug key.
Local passwords are protected using Windows DPAPI; keep an independently secured recovery copy of
the signing identity and the Windows credentials required to recover the local protection.

Publication is enabled with `NYANIME_PRODUCTION_READY` after technical checks and explicit release
authorization. The initial release is authorized for an OTA installation test. Changing signer or
package is not a Play Protect fix by itself: a normal installation and scan with Play Protect enabled
are still unverified. If detection remains, investigate the reported classification and use Google's
official false-positive procedure instead of recommending that users disable protection.

## Validation

Generic automated tests cover explicit extension consent, archive bounds and opaque references,
manifest disagreement, frame authentication, truncation and separation of OTA identities.
Same-device migration on Android 16 verified 805 files (139,828,402 bytes), complete backup equality,
shared-folder access and startup with the restored Home and ongoing titles. Interrupted commit,
all credential providers, private downloaded-media playback, addons and the normal Play Protect scan
remain additional acceptance checks; a successful ADB installation does not prove Play Protect
acceptance.
