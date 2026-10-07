# Optional addon protocol v1 and v2

An addon is an independently installed Android APK. The host does not load its
classes, services, native libraries or resources into its application process.
It discovers declarations through PackageManager and offers the same catalogue,
installer, signer and update checks as source extensions. There are no bundled
addon implementations, package names, catalogue URLs or provider-specific rules.

## Declaration

The host explains addons once at app startup, after the appearance and update
choices, whether or not an addon is installed or a catalogue is available.
Only pressing Continue completes the explanation; an interrupted launch leaves
it pending. This acknowledgement belongs to the installation and is excluded
from portable backups. Launch intents are processed afterwards.

Declare the optional feature `nyanime.addon`. Application metadata declares
`nyanime.addon.api=1`, a drawable resource `nyanime.addon.logo`,
`nyanime.addon.activation=hold_logo`, and `nyanime.addon.hold_ms` in 500–3000.
Exactly one enabled, exported activity with no permission must resolve the action
`nyanime.addon.OPEN`. Do not add a launcher intent filter. A distribution manifest
at `assets/nyanime/extension-v1.json` identifies the signer-bound distribution.
Unknown versions or activation rules are incompatible, never executable scripts.

API v2 also accepts `activation=more_action` without a hold duration. It requires
localized string resources in `nyanime.addon.action_label`,
`nyanime.addon.action_summary` and `nyanime.addon.description`. The title, icon and
explanation all belong to the independently installed addon. Only an enabled,
trusted addon adds its declared action to More; it never changes Home's logo
gesture. API v1 logo modes continue to work unchanged. Both versions may supply
the optional description resource, shown in addon settings before enabling it.
The host does not infer functionality from the package name or its description.

Installation does not grant execution trust. Users approve the actual APK signer
and distribution and can disable an addon independently. Package replacement and
removal refresh discovery. Immediately before opening or granting theme access,
the host rechecks current package metadata. Trust survives valid certificate
rotation but not a different distribution or unrelated signer.

## Launch capabilities

An explicit intent to the inspected activity includes `nyanime.addon.protocol=1`,
immutable PendingIntents `nyanime.addon.return` and `nyanime.addon.about`, and an
optional `nyanime.addon.appearance` content URI. The return action preserves the
host navigation stack. Opening uses the foreground activity and its existing task;
addons must keep internal navigation in that task instead of creating a second
recent-app entry. Addons retain capabilities in activity instance state to
survive process recreation and must release their own player when returning.
No host activity names or package names need to be hardcoded in an addon.

`nyanime.addon.appearance=true` opts into shared theme. The URI supports only
`read` and `select` with SYSTEM/LIGHT/DARK. Read also returns the host's BCP-47
locale list in `locale`. Its exported provider authorizes the
calling UID against the live trusted, enabled package and exposes no app files,
preferences, database or credentials. Disabling an addon revokes this access.

## Catalogues and compatibility

Direct addon catalogues declare medium `addon` and API `1.0`. Existing unified
catalogues can expose `addonList: {version: 1, extensions: [...]}` alongside their
unchanged anime/manga/news extension list. This prevents older clients from
rejecting an unknown medium. Every addon entry still requires HTTPS download,
SHA-256 integrity, pinned APK signer, distribution identity and version checks.

API v2 entries declare library `2.0` in the optional
`addonActions: {version: 1, extensions: [...]}` section. Keeping these separate
from the existing v1 `addonList` lets old hosts retain their compatible modes.
The launch protocol integer matches the installed addon's declared API.
Downloads are bounded to 512 MiB. Conflicting publishers are never silently merged.

App updates do not erase historical addon-owned files from an older integrated
installation. The extracted APK has independent storage; this protocol does not
silently export host preferences or credentials to it.

## Optional host version bounds (API 1 and 2)

An addon may declare `nyanime.addon.min_host_version_code` and optionally
`nyanime.addon.max_host_version_code` as positive integer manifest metadata.
Bounds are inclusive and use the host Android `versionCode`, never a commit count
or a lexicographic version-name comparison. Omitting a bound means no bound.
Existing addons without these fields remain compatible with the existing API rules.

`nyanime.addon.min_host_version_name` and `nyanime.addon.max_host_version_name`
are optional human-readable labels (plain strings, at most 64 characters), allowed
only alongside their corresponding code. Without a label the UI shows the build.
Inverted, malformed or zero bounds fail closed. No automatic downgrade is offered.

The signed APK manifest is authoritative. Catalogue entries copy these four fields
as `minHostVersionCode`, `maxHostVersionCode`, `minHostVersionName`,
`maxHostVersionName`. Store cards explain incompatibility before downloading;
APK validation checks the range and equality with catalogue metadata. Installed
addons are checked again before every open and appearance capability operation.
Incompatible addons remain visible in management, with enable/open disabled.

## RIN addon runtime v1

RIN addons use signed schema 2, `medium: "addon"`, `extensionLib: 1.0`, one
`nyanime.addon.api.RinAddon` implementation and an empty sources list. They are
installed in the RIN registry, not Android PackageManager. Nyatube remains an APK.
The optional `rinAddons: {version: 1, extensions: [...]}` catalogue section is
separate from source `rinList`, keeping older hosts unchanged.

The signed `addon` declaration supplies api=1, activation=more_action, localized
`actionLabel`, `actionSummary`, `descriptions`, and optional HTTPS `sourceUrl`.
English fallback is mandatory. The four host bounds above also apply to the signed
RIN manifest. An incompatible RIN remains visible, cannot be enabled or opened,
and cannot replace a working version in an automatic update.

The external addon implements `createView(Activity, Context, RinAddonHost)`,
`startTask(Context, String, Bundle, RinAddonTask)`, `cancelTask(...)`, and
`runPeriodicTask(...)` (0 success, 1 retry, 2 permanent failure). The host provides
appearance, explicit return/reopen actions, notification permission, foreground
notifications/cancellation and named periodic jobs. `RinAddonContext.addonHost()`
is the capability accessor. Background work is Android best-effort and checked
against enablement and active bundle version on each launch.

Private files, preferences, cache and databases are namespaced by package and
publisher; persistent addon data lives under noBackupFilesDir. The provided Context
must be retained, including its applicationContext, instead of substituting the
host base context. Addon Keystore aliases must start with `storageNamespace()`.
This is a trusted-code runtime, not a security sandbox for hostile code.

Compiled resources use package ID 0x80, authenticated alongside every DEX/asset.
The resource context combines host resources with one signed addon version;
individual addon contexts do not combine multiple 0x80 resource tables.
Activity/lifecycle/core interfaces use the preserved parent ABI. Compose code and
its bridges remain addon-local; runtime packages crossing the boundary must be
excluded from the addon DEX. The Java addon-api is compile-only for addons.

This runtime does not register arbitrary Android manifest components or provide
native-library loading yet. Engines needing providers, accounts, deep-link entry
activities or native code need explicit future generic contracts. A RIN must never
silently install an APK to implement those capabilities.
