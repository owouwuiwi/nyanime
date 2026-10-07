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

The main Android workflow builds the existing installable package and certificate.
Canonical releases use `vX.Y.Z.W` tags and `Nyanime-X.Y.Z.W-<abi>.apk` assets.
Recommended releases are not GitHub prereleases; previews are. Both have a numeric
title, per-release notes, checksums and compatible ABI/universal packages.

**Every release also publishes a compatibility alias** using the previous `r<commit-count>`
tag and fixed `app-<abi>-preview.apk` asset names. This is permanent, not a one-time
migration window. The compatibility alias is kept first in the release feed for
the earliest clients, including those that inspect only the first suitable release.
Clients with stricter asset filters also find it within their existing query. Thus an
old r-style installation can jump directly to the latest APK after hundreds of releases;
intermediate APKs do not need to be installed.

Numeric clients ignore these aliases. The app and APK filenames show only X.Y.Z.W;
legacy tag identifiers are retained solely for compatibility with already shipped clients.
The old application ID and signing identity are unchanged, preserving app data.

Source builds run in the private `owouwuiwi/nyanime-source` repository with complete
Git history. The original public `owouwuiwi/nyanime` repository retains its identity,
historical redirects, APK download URLs and releases. Only documentation, signed APKs
and verified checksums are transferred to its bot publisher. Public documentation
snapshot dates may be normalized to preserve GitHub feed ordering; source history
and release publication dates remain unchanged.

## The update screen

The update page shows the installed and available versions, complete release notes in
separate cards, and a fixed action area. Only release notes scroll; the compact version
header and download status remain visible. Download progress, waiting, cancellation and
retry states stay visible without blocking navigation. Closing the page does not stop
an active download; its notification and the existing ready-update cards remain available.

The install action only appears after checking that the saved APK still exists, belongs
to this application and is newer than the installed version. These checks run off the
UI thread and repeat when returning from Android's installer. Missing or obsolete
files can be downloaded again. The page respects the in-app installation preference,
the chosen theme and reduced motion, with Italian and English labels.

To promote a tested preview without rebuilding it, manually run **Nyanime releases and
OTA** on the same commit with the Recommended channel. Existing public APK assets are
not overwritten. Unfinished uploads stay drafts until all assets have been uploaded.

The recommended checker uses GitHub's latest non-prerelease endpoint so that its last
recommended release remains discoverable even after thousands of previews. Numeric
clients validate the channel, four-part version, compatible assets and platform before
comparing versions. Invalid tags, drafts and Android TV releases are excluded.
