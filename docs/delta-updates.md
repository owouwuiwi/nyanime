# Differential application updates

Introduced in Nyanime **0.50.0.0**. This is an optional optimization inside the
existing in-app OTA downloader, not another installation backend. Android still
asks the user to confirm installation. Local data, recovery backups and extension
updates keep their existing paths.

## User experience and compatibility

The updater checks whether a smaller download is available, downloads it,
reconstructs the update and verifies the APK. The same page and notification
describe these stages in English and Italian. Reconstruction runs on the IO
dispatcher; buffers are bounded and progress is throttled. Cancelling propagates
through downloads, hashing and copying and does not start the full download.

The first upgrade to an updater with delta support is a **full APK**. An older
updater cannot apply a feature it does not have. Subsequent releases can supply
patches from up to three previous signed releases. This v1 publication path
includes all five full APKs and the unchanged APK-only `SHA256SUMS`. Existing
clients ignore the new assets and continue updating normally. The separately
gated [v2 release graph](ota-release-graph.md) adds bounded chains and allows
future patch-only publications after a complete capable-client transition.

For the v1 path, no compatible patch, split installation, unknown base bytes, unsupported metadata,
insufficient reconstruction space, rejected redirect, corrupt patch, reconstruction
or verification error returns control to the original full-APK downloader. A full
download must also pass cryptographic signature, package and version checks.
Patches are selected only when they save **at least 20% and at least 1 MiB**.
Actual savings depend on which ZIP entries changed; there is no promised patch size.

Remote backups, downloaded media, source definitions and library data are unrelated
to this feature. No new permission, user upload, telemetry service, native library
or third-party codec is introduced.

## Responsibilities and trust

- `scripts/delta_updates.py`: deterministic, ZIP-aware patch generation and an
  independent Python reconstruction check before material preparation.
- `DeltaManifest`: validates versioned bounded metadata and selects an exact
  base/target pair. The installed APK hash identifies the actual ABI/build bytes;
  version names and the phone's preferred ABI alone are not proof of compatibility.
- `DeltaUpdateClient`: acquires optional metadata/patches, checks available space,
  reports phases and falls back. `DeltaUpdateEnvironment` isolates Android identity,
  storage and final APK verification from the transport and reconstruction logic.
- `DeltaPatch`: bounded streaming COPY/INSERT parser; no network, Android UI, code
  execution, ZIP extraction, re-packing or re-signing.
- `AppUpdateApkValidator`: uses the existing Google `apksig` dependency for real
  cryptographic APK verification, exact installed signer-set comparison, package
  identity and monotonic version checks. Delta output must additionally match its
  exact declared version name/code. Future signing-key rotation needs an explicit
  policy; it is not silently accepted.
- `AppUpdateDownloadJob`: the existing unique WorkManager operation owns download,
  reconstruction and private temporary files. Repository, reminders, installer
  permission handling and protected installation remain shared with full updates.
- `awaitUpdateResponse`: retains the cancellation connection to the active socket
  until its response body is closed, including a transfer stalled after headers.
  Its child coroutine is released on success, failure and cancellation.

Metadata and patches are fetched only beside an immutable, canonical ordinary
GitHub release URL. The optional transport sends no cookies and follows HTTPS
redirects only to GitHub's release asset hosts, with bounded redirects/timeouts.
The manifest uses the same HTTPS release authority as the existing full download;
it is **not a separately signed update feed**. Patch hashes are not a substitute
for APK signature verification.

## Format v1

The release asset `Nyanime-Delta-v1.json` is limited to 256 KiB, nesting depth 8
and 15 patch records. It declares schema, application ID, target version name/code,
and for every pair:

- base SHA-256 and size;
- target APK filename, SHA-256 and size;
- patch filename, SHA-256 and compressed size.

The patch is a GZIP stream containing big-endian integers:

| Field | Encoding |
| --- | --- |
| Magic | 8 bytes, `NYADLT01` |
| Base and target lengths | Two unsigned-compatible 64-bit positive lengths |
| Base and target SHA-256 | Two 32-byte digests |
| Operation count | 32-bit count, 1–100,000 |
| COPY | Byte 0, 64-bit base offset, 64-bit length |
| INSERT | Byte 1, 64-bit length, exact literal bytes |

APK lengths are limited to 1 GiB. Each operation is checked against remaining
base/output bytes before copying, avoiding overflow and decompression-driven
growth. Unknown opcodes, nonpositive lengths, truncated streams, wrong final
sizes/hashes and trailing decoded data are rejected. Interrupted output is removed.
Temporary and completed new downloads stay in private `cacheDir/app-updates`.
The installed APK is read-only and is never modified in place.

The producer reuses identical **compressed ZIP entry payloads**, independent of
their changed offsets. Changed entries can reuse unchanged 64 KiB blocks. Headers,
central directory and APK signing block come from the exact signed target. The
result must be byte-for-byte identical to the full published APK, including its
original signature. This deliberately avoids decompression/recompression variance.

## Preparing and publishing

Use immutable, already verified signed APK directories as explicit inputs:

```sh
python scripts/public_distribution.py \
  --output publication --apk-directory signed-apks \
  --version 0.50.0.0 --code 281 --channel recommended \
  --notes notes.md --revision BUILD_COMMIT \
  --native-materials native-materials \
  --delta-base-directory previous-signed-apks
```

The optional argument can be repeated at most three times. Without explicit bases
or prepared materials, the CLI tries the latest public release, verifies asset
sizes/SHA-256 and caches its complete APKs locally in `.local/ota-delta-bases`.
Offline, missing hashes or failed retrieval skip the affected bases; full release
preparation still works. `--no-auto-deltas` disables this best-effort retrieval.
A previously prepared `Nyanime-Delta-v1.json` and its matching patches may instead
be supplied in the APK directory, or the release may contain no delta materials at all. Use a fresh
output directory; previous delta files are never silently reused or overwritten.

Preparation verifies every round trip, omits uneconomical patches, validates the
manifest against full target hashes, and records all optional materials in
`publication.json` under `deltaSha256`. The existing publication schema and APK
checksums remain compatible. Both the regular publisher and reviewed manual
publisher validate and transfer the exact optional material set with the bot.
Do not replace bytes under an already distributed version or upload private keys.

## Verification boundary

Tests cover independent Python/Kotlin round trips, moved ZIP payloads, changed
blocks, deterministic output, wrong base/hash, malformed commands and ranges,
truncation, cancellation, bounded metadata, release URL selection, space checks,
missing/corrupt material and final-verification rejection. Transport tests use
fictitious responses and do not prove a public OTA publication.

For a release, additionally compare a patch between real signed APKs, verify the
reconstructed target with `apksigner`, and exercise the Android parser, signature
gate and normal installation flow on a physical device. Record hashes, sizes,
timings, build revision and actual device results separately from simulated tests.

### 0.50.0.0 validation snapshot

The signed release candidates were built from `d678fe38b38cbac0afbacb3c956a80841e17fbc8`
with version code 281. All five full APKs retain the production package and signing
certificate. Patches from 0.49.0.0 independently reconstruct their exact signed
targets. For ARM64, the patch is 16,315,369 bytes versus a 175,649,166-byte full APK
(90.71% saved); the universal patch saves 97.20%. These are measurements for this
specific release pair, not forecasts for future releases.

On the USB test device, reconstruction took 544 ms. The actual optimized APK's
verification gate accepted the correct production update and rejected both
tampering and a valid same-package/version APK signed with another key. Wrong-base
and cancellation cleanup checks passed. The probe isolates the candidate's classes
from its own libraries and is removed after execution. The normal app then updated
in place to 0.50.0.0 and opened successfully, preserving its original installation.

The 46 focused updater tests passed. The complete app suite finished with 1,722
tests, zero failures/errors and seven pre-existing optional skips; all 202 Python
tooling tests passed. This validates the candidate and publication tooling locally;
it does not represent a published public OTA or prove all network/device conditions.
