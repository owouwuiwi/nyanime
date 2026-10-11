# Telegram IPC regression verification

This standalone fixture compiles the production `TelegramIpcPayload.kt` into a small test APK. It exercises Android Binder between two real processes, without a Telegram account, networking permission, launcher activity, native engine or access to Nyanime data. It does not build or install another Nyanime variant.

It checks 26 round trips, including 538304-byte and 2 MiB replies, Unicode, consecutive large replies and a final small reply. It verifies bounds, descriptor closure and removal of backing files. All inputs are synthetic.

Use Python 3, a JDK17+ installation, Android SDK36/build-tools, ADB, and the Kotlin compiler/dependencies already downloaded by the normal project build. No downloads or dependency changes are performed by this script.

```powershell
python tools/verification/telegram-ipc/run.py --java-home "$env:JAVA_HOME" --sdk "$env:ANDROID_SDK_ROOT" --serial DEVICE_SERIAL
```

`--adb PATH` selects a specific ADB binary. Without `--serial` the script only builds the fixture. Output and the temporary fixture signing key stay under ignored `.local/telegram-ipc-probe`; the key is never used for production. With `--serial`, the script installs the synthetic package, runs instrumentation and removes only that fixture package in `finally`.

Expected result: `ipc_payload=passed`, 26 round trips and 24 descriptor round trips. This test does not establish end-to-end Cloud delivery: complete a real backup and confirm a sync change on another device separately in the final signed application.
