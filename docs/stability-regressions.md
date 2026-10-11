# Player and optimized-network regression checks

The September 9 device investigation on preview r8153 found two distinct failures:

- A `NullPointerException` in `PlayerActivity.createPipParams` during the PiP callback.
  MPV video height did not guarantee that its separately queried aspect was present.
- 26 native abort records between 20:52 and 20:56, all reporting a missing
  `com.squareup.zstd.ZstdCompressor` during Zstd JNI initialization. Stacks included
  HTTP decompression in the background manga library update job.

The fixes do not alter user libraries, progress, sources, Anime4K, application ID or signing.
PiP geometry is optional and validated independently of Android; invalid/missing values
use the platform default. Receiver registration is idempotent and released on destruction.
Zstd classes and fields referenced by native code retain their names in optimized builds.

## Release acceptance

1. Run the unit suite, including `PipVideoGeometryTest` (missing, non-finite, boundary,
   landscape and rotated/portrait aspect ratios).
2. Run `python3 scripts/verify_apk_jni.py <preview APKs>` after R8. This inspects declared
   DEX classes and fields rather than merely checking source keep rules. The old r8153
   APK must fail and the fixed release must pass.
3. Preserve `mapping.txt` and the independent JNI probe in the GitHub Actions
   `release-diagnostics` artifact. Private keys and device logs are not uploaded.
4. On a connected Android device, execute the artifact's probe with the exact installed
   APK path returned by `adb shell pm path xyz.jmir.tachiyomi.mi.anime4k.debug`:

   ```sh
   adb push classes.dex /data/local/tmp/aniyomi-jni-probe.dex
   adb shell CLASSPATH=/data/local/tmp/aniyomi-jni-probe.dex app_process /system/bin ZstdApkProbe /data/app/.../base.apk arm64-v8a
   ```

   Require `APK_ZSTD_NATIVE_ROUNDTRIP_OK`. The probe checks the names before entering
   JNI, compresses and decompresses a fixed in-memory string, and releases its contexts.
   It does not launch the app or read/write preferences, library data or network content.
   This physical native check is separate from the CI symbol check and JVM tests.
5. Install as an update; exercise PiP entry/exit, pause, episode transitions and activity
   recreation, and compare new crash/exit records. Automated geometry tests alone do not
   establish that the whole player lifecycle has been verified on a physical device.

No finite test suite guarantees zero crashes. Report exactly which checks ran for a release.
