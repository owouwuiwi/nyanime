# First installation

The welcome is displayed before the main navigator consumes launch intents. An
installation that already completed setup keeps its existing completion flag.
The installation-local step resumes after process death and is excluded from
portable backups, together with permission and introduction acknowledgements.

The five steps cover storage, appearance, optional permissions, update channel,
and restoration. The final acknowledgement completes the theme, channel, addon,
and optional Cloud introductions so they are not repeated as separate screens.
Existing upgrade gates continue to handle explanations not previously acknowledged.
The search navigation upgrade notice remains upgrade-only.

Settings → Advanced can open a separate review of the welcome. This review starts
at the beginning, keeps its step in the screen model instead of installation
preferences, and returns to Settings on completion or Back from the first step.
First installation still exits through its completion gate, independently of
manual navigation.

New installations use a verified Nyanime directory under the app's external files
directory, falling back to internal files when external storage is unavailable.
No shared-storage permission or folder chooser is needed. These files survive
updates but are removed on uninstall. Settings still allow a user-selected folder;
an existing selection is validated and never silently replaced. Failure offers a
retry and an explicit folder repair. Manual backups keep their existing
Download/Nyanime publication path.

Storage observes and reconciles its first preference emission, including a choice
made before subscription. It never drops a newly prepared directory. Migration
release notices remain upgrade-only, including when a fresh install restores data.

Permission actions are separate and optional. Notification status is checked both
after the permission result and on resume; a denied request can open Android
settings rather than repeatedly showing a blocked runtime prompt. App installation
and battery permissions retain system confirmation.

Local and Cloud restoration reuse the same validator, restore options, tracked
WorkManager job and progress/result UI. Selecting a local file does not initialize
Telegram. Cloud login begins only after the explicit Cloud action. Leaving a
restore screen keeps its job visible in the welcome; a running restore prevents
duplicate restoration or final setup confirmation. No downloaded media is restored.

## Validation

Use the isolated benchmark application for fresh-install tests, never clear the
user's main application. Check setup interruption/resumption, no second welcome
after finishing or upgrading, permission denial/return, local invalid and valid
backups, explicit Cloud entry, themes, narrow screens and large text. Verify the
production APK keeps its package ID and signer and excludes fixture activities.

For 0.40.0.0, 44 targeted JVM tests and four on-device tests passed on a Galaxy
Z Flip6 (Android 16). The device tests cover resumable setup, stable header bounds,
non-repeated introductions, optional notification denial, valid/invalid local
restoration, and storage initialization before preference subscription. Additional
device checks cover notification grant/revocation, light/dark themes, narrow layout
with larger text and reduced motion, and Cloud entry/return. Cloud authentication
and remote account restoration were not repeated by these welcome tests.

For 0.40.0.1, the six welcome JVM tests and two targeted device tests passed on
the same Galaxy Z Flip6. The device regression opens the guide through
Settings → Advanced, completes it twice, returns with Back, and reopens an
interrupted review from the beginning. The other device test retains resumable
first installation and verifies the welcome does not repeat after completion.
