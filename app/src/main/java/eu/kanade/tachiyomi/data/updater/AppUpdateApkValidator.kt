package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

/** Shared final gate for both full downloads and reconstructed APKs. No re-signing. */
internal object AppUpdateApkValidator {
    @Suppress("DEPRECATION")
    fun validate(context: Context, file: File, expectedCode: Long? = null, expectedName: String? = null) {
        val flags = if (Build.VERSION.SDK_INT >=
            28
        ) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archive = requireNotNull(manager.getPackageArchiveInfo(file.absolutePath, flags))
        require(archive.packageName == context.packageName) { "Wrong APK package" }
        val code = PackageInfoCompat.getLongVersionCode(archive)
        require(
            isNewerAppUpdate(
                PackageInfoCompat.getLongVersionCode(installed),
                installed.versionName,
                code,
                archive.versionName,
            ),
        ) {
            "Update is not newer"
        }
        require(expectedCode == null || code == expectedCode) { "Unexpected APK version code" }
        require(expectedName == null || archive.versionName == expectedName) { "Unexpected APK version name" }
        val verification = ApkVerifier.Builder(file)
            .setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .build().verify()
        require(verification.isVerified) { "APK signature verification failed" }
        val installedSigners = if (Build.VERSION.SDK_INT >= 28) {
            installed.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            installed.signatures.orEmpty()
        }
        val expected = installedSigners.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toList()
        }.toSet()
        val actual = verification.signerCertificates.map {
            MessageDigest.getInstance("SHA-256").digest(it.encoded).toList()
        }.toSet()
        // Exact signer sets deliberately fail closed on an unconfigured future key rotation.
        require(expected.isNotEmpty() && expected == actual) { "Update signer differs from installed app" }
    }
}
