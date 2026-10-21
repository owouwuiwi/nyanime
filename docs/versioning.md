# Nyanime versions and update channels

Public versions use **X.Y.Z.W**, without an `r` revision or a commit suffix.
The leading component stays **0** until a stable milestone is explicitly approved.
The first release of this system is **0.19.0.0**.

## Numbering

`release.properties` is the single source of truth for the Android version name,
version code and publication channel. Every published APK has an immutable version.

| Component | Change | Example |
| --- | --- | --- |
| X | Explicitly approved major milestone | 1.0.0.0 |
| Y | Significant new functionality | 0.20.0.0 |
| Z | Substantial improvements to existing functionality | 0.19.1.0 |
| W | Fixes and smaller refinements | 0.19.0.1 |

Increasing a component resets every component to its right. Comparisons are numeric
and stop at the first difference: 0.20.0.0 is newer than 0.19.99.999.
Android `versionCode` increases separately for every publication, including previews.
Changing channels never proposes a downgrade.

A changed application APK delivered to an existing user installation, including a
local update through ADB, must also have a new public version and a higher Android
`versionCode`. A new filename or commit does not change the installed version.
Run the publication checks before pushing and build the APK from the versioned
commit; do not reuse the preceding release's number for a modified application.

Before a new publication, run one of these commands and include the changed file in
the implementation commit. The helper defaults to the preview channel:

```sh
python3 scripts/release_version.py --bump fix
python3 scripts/release_version.py --bump improvement
python3 scripts/release_version.py --bump feature
```

Use `--channel recommended` for reviewed everyday releases. CI refuses to replace
an existing version with different code or to publish a version/code that did not
increase. Rerunning the same commit keeps already published APK bytes unchanged.

## The one-time choice

On first launch of a version with this system, new and existing installations choose:

- **Recommended:** reviewed features and fixes for everyday use; selected initially.
- **Include previews:** recommended releases plus more frequent features in testing.

After confirmation the screen never appears again in that installation, including
after later app updates. Closing the app without confirming leaves the choice pending.
The channel can be changed in **Settings → Updates**. Selecting Recommended after a
newer preview waits for a newer recommended release, preserving the current version.

The choice works offline. Its channel preference is backed up, while the installation's
confirmation state is excluded from portable backups. Launch links remain pending
until the initial setup is complete.

## Publication and permanent compatibility

From 0.50.0.0, the in-app downloader can use optional
[byte-exact differential updates](delta-updates.md). Until the v2 client transition
has been published and verified, full APKs remain mandatory. The first capable
version is itself available as a complete package. Later patch-only releases must
retain a reachable complete checkpoint, keep `/releases/latest` on that checkpoint
for older clients, and satisfy the [release graph gates](ota-release-graph.md).
Existing asset names and published bytes remain immutable.

The main Android workflow builds the production package and verifies its release certificate.
Canonical releases use `vX.Y.Z.W` tags. From 0.42.0.0, they contain only
`Nyanime-Production-X.Y.Z.W-<abi>.apk` assets for the production package and
certificate. Previously published old-identity assets and `rNNNN` aliases remain
unchanged as migration bridges; new releases do not recreate these aliases.
Recommended releases are not GitHub prereleases; previews are. Both have a numeric
title, per-release notes, checksums and compatible ABI/universal targets. A verified
patch-only release reconstructs those exact signed targets from retained materials.

The last existing compatibility alias remains first for the earliest clients,
including those that inspect only the first suitable release. It still points to
the unchanged 0.41.0.6 bridge, which can install the current production APK and
offer the verified local transfer. New documentation snapshots stay below this
frozen bridge in the legacy feed; the numeric Recommended release is explicitly
selected as GitHub's latest release. No old-signed APK is rebuilt or replaced.

Numeric clients select assets for their own package identity. The new production
APK cannot update the old package in place: Android installs it alongside the old
app, and removal is offered only after the transfer is verified. See the
[identity transfer guide](identity-transfer.md).

Source builds run in the private `owouwuiwi/nyanime-source` repository with complete
Git history. The original public `owouwuiwi/nyanime` repository retains its identity,
historical redirects, APK download URLs and releases. Only documentation, signed APKs
and verified checksums are transferred to its bot publisher. Public documentation
snapshot dates may be normalized to preserve GitHub feed ordering; source history
and release publication dates remain unchanged.

The private build transfers its verified APK/document bundle through a temporary
draft Release in the private source repository. This transfer does not consume
Actions artifact storage; deleting artifacts cannot erase storage usage already
accrued during the billing month. The public bot checks the originating build,
draft, exact asset, archive hash, allowed document paths and every APK checksum
before publishing. Only the approved public documents and signed APKs are exposed.
The temporary draft is removed after successful publication and confirmation of
the private source tags. A failed publication retains it for investigation/retry.
Already-prepared older Actions artifacts remain supported for historical retries.

## The update screen

The update page has a compact back/title/menu header. The available version, installed
version and complete Markdown release notes share one scroll, without separate cards
for each paragraph. A fixed **Update** action becomes download/verification progress;
**Not now** becomes **Back to the app** during the download. Cancellation and the
external release page are available in the overflow menu.

The install action only appears after checking that the saved APK still exists, belongs
to this application and is newer than the installed version. These checks run off the
UI thread, shared with the ready-update reminder, and repeat on foreground return.
Progress ticks never trigger package/file inspections. Missing or obsolete files can
be downloaded again.

**Update** always downloads, checks the APK and opens the
existing protected installation flow once while this page is resumed. Android still
requires the user's confirmation; launching its installer is not proof of installation.
If install permission is missing, granting it in Android settings continues the same
operation. Denial or installer cancellation leaves a manual action, without reopening
Android automatically. Rotation preserves the screen's one-shot intent.

Closing the page abandons automatic installation but does not stop the WorkManager
download. Its notification and the in-app **Install** reminder remain available.
Reopening the page or restarting the process never restores an abandoned automatic
intent. In-app updates are always enabled, including installations that previously
disabled them; there is no installation-mode switch in Info or Update settings. The page
respects theme/reduced motion, with Italian and English labels. Channel selection,
version comparison, package identity, signing and OTA aliases are unchanged.

The opt-in `-PupdateProbe=true` physical-device runner opens this same page with
generic release notes and a supplied download URL. Its small target-side UI bridge
is excluded from ordinary builds; Compose calls are optimized together with the
application rather than calling removed runtime APIs from the test APK. A newer
signed disposable fixture can verify download, permission return, protected backup
and installer cancellation. Never confirm installation of a fixture; a successful
installer launch is separate from a completed OTA update. The runner's `cleanup`
option removes only its own cached download before removing the test APK.

To promote a tested preview without rebuilding it, manually run **Nyanime releases and
OTA** on the same commit with the Recommended channel. Existing public APK assets are
not overwritten. Unfinished uploads stay drafts until all assets have been uploaded.

The recommended checker uses GitHub's latest non-prerelease endpoint so that its last
recommended release remains discoverable even after thousands of previews. Numeric
clients validate the channel, four-part version, compatible assets and platform before
comparing versions. Invalid tags, drafts and Android TV releases are excluded.
