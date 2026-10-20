package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.work.WorkInfo
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.storage.getUriCompat
import tachiyomi.domain.release.model.ReleaseVersion
import java.io.File

data class ReadyAppUpdate(val id: String, val apk: File)

fun WorkInfo.readyAppUpdate(context: Context): ReadyAppUpdate? {
    if (state != WorkInfo.State.SUCCEEDED) return null
    val apk = outputData.getString(AppUpdateDownloadJob.OUTPUT_APK_PATH)?.let(::File)
        ?.takeIf { it.isFile && it.length() > 0L } ?: return null
    val valid = runCatching {
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return@runCatching false
        val installed = manager.getPackageInfo(context.packageName, 0)
        val eligible = archive.packageName == context.packageName &&
            isNewerAppUpdate(
                PackageInfoCompat.getLongVersionCode(installed),
                installed.versionName,
                PackageInfoCompat.getLongVersionCode(archive),
                archive.versionName,
            )
        if (!eligible) return@runCatching false
        AppUpdateApkValidator.validate(context, apk)
        true
    }.getOrDefault(false)
    if (!valid) return null
    return ReadyAppUpdate(id.toString(), apk)
}

/** Numeric publications increase versionCode; suffix comparison remains only for old downloads. */
internal fun isNewerAppUpdate(
    installedCode: Long,
    installedName: String?,
    archiveCode: Long,
    archiveName: String?,
): Boolean {
    if (archiveCode < installedCode) return false
    val installed = installedName.orEmpty()
    val archive = archiveName.orEmpty()
    val numericCurrent = ReleaseVersion.installed(installed)
    val numericCandidate = ReleaseVersion.parse(archive)
    if (numericCurrent != null && numericCandidate != null) return numericCandidate > numericCurrent
    if (archiveCode > installedCode) return true
    val current = installed.substringAfterLast('-').toLongOrNull() ?: return false
    val candidate = archive.substringAfterLast('-').toLongOrNull() ?: return false
    return archive.substringBeforeLast('-') == installed.substringBeforeLast('-') && candidate > current
}

internal fun Context.canInstallAppUpdates() =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

internal fun Context.appUpdatePermissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
    data = "package:$packageName".toUri()
}

/** The caller owns the permission round trip; installation retains the existing recovery backup. */
internal fun installReadyAppUpdate(context: Context, apk: File): String? = try {
    if (!context.canInstallAppUpdates()) {
        context.getString(R.string.app_update_install_permission)
    } else {
        context.startActivity(
            Intent(context, eu.kanade.tachiyomi.data.recovery.ProtectedAppUpdateActivity::class.java).apply {
                data = apk.getUriCompat(context)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        null
    }
} catch (e: Exception) {
    context.getString(R.string.app_update_install_error)
}
