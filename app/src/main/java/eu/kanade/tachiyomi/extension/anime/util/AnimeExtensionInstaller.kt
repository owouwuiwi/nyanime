package eu.kanade.tachiyomi.extension.anime.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.ExtensionApkValidator
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.installer.InstallerAnime
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The installer which installs, updates and uninstalls the extensions.
 *
 * @param context The application context.
 */
internal class AnimeExtensionInstaller(private val context: Context) {

    /**
     * The system's download manager
     */
    private val downloadManager = context.getSystemService<DownloadManager>()!!

    /**
     * The broadcast receiver which listens to download completion events.
     */
    private val downloadReceiver = DownloadCompletionReceiver()

    /**
     * The currently requested downloads, with the package name (unique id) as key, and the id
     * returned by the download manager.
     */
    private val activeDownloads = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val verifiedFiles = java.util.concurrent.ConcurrentHashMap<Long, File>()
    private val verifying = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    private val expectedPackages = java.util.concurrent.ConcurrentHashMap<Long, ExtensionApkValidator.Expected>()

    private val downloadsStateFlows = java.util.concurrent.ConcurrentHashMap<Long, MutableStateFlow<InstallStep>>()

    private val extensionInstaller = Injekt.get<BasePreferences>().extensionInstaller()

    /**
     * Adds the given extension to the downloads queue and returns an observable containing its
     * step in the installation process.
     *
     * @param url The url of the apk.
     * @param extension The extension to install.
     */
    fun downloadAndInstall(url: String, extension: AnimeExtension): Flow<InstallStep> {
        val pkgName = extension.pkgName

        val oldDownload = activeDownloads[pkgName]
        if (oldDownload != null) {
            deleteDownload(pkgName)
        }

        // Register the receiver after removing (and unregistering) the previous download
        downloadReceiver.register()

        val downloadUri = url.toUri()
        val request = DownloadManager.Request(downloadUri)
            .setTitle(extension.name)
            .setMimeType(APK_MIME)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                downloadUri.lastPathSegment,
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val id = downloadManager.enqueue(request)
        activeDownloads[pkgName] = id
        val available = extension as? AnimeExtension.Available
        val installed = Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value.firstOrNull {
            it.pkgName ==
                pkgName
        }
        expectedPackages[id] = ExtensionApkValidator.Expected(
            pkgName,
            extension.versionCode,
            available?.expectedSigner ?: available?.store?.signingKey,
            available?.distributionId,
            repository = available?.store?.indexUrl,
            installedVersion = installed?.versionCode,
            anime = true,
            libVersion = extension.libVersion,
            installed = installed?.metadata,
        )

        val downloadStateFlow = MutableStateFlow(InstallStep.Pending)
        downloadsStateFlows[id] = downloadStateFlow

        // Poll download status
        val pollStatusFlow = downloadStatusFlow(id).mapNotNull { downloadStatus ->
            // Map to our model
            when (downloadStatus) {
                DownloadManager.STATUS_PENDING -> InstallStep.Pending
                DownloadManager.STATUS_RUNNING -> InstallStep.Downloading
                DownloadManager.STATUS_FAILED -> InstallStep.Error
                else -> null
            }
        }

        return merge(downloadStateFlow, pollStatusFlow).transformWhile {
            emit(it)
            // Stop when the application is installed or errors
            !it.isCompleted()
        }.onCompletion {
            // Always notify on main thread
            withUIContext {
                // Only remove this request, never a newer retry for the same package.
                deleteDownload(pkgName, id)
            }
        }
    }

    /**
     * Returns a flow that polls the given download id for its status every second, as the
     * manager doesn't have any notification system. It'll stop once the download finishes.
     *
     * @param id The id of the download to poll.
     */
    private fun downloadStatusFlow(id: Long): Flow<Int> = flow {
        val query = DownloadManager.Query().setFilterById(id)

        while (true) {
            // Get the current download status
            val downloadStatus = downloadManager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return@flow
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }

            emit(downloadStatus)

            // Stop polling when the download fails or finishes
            if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL ||
                downloadStatus == DownloadManager.STATUS_FAILED
            ) {
                return@flow
            }

            delay(1.seconds)
        }
    }
        // Ignore duplicate results
        .distinctUntilChanged()

    /**
     * Starts an intent to install the extension at the given uri.
     *
     * @param uri The uri of the extension to install.
     */
    fun installApk(downloadId: Long, uri: Uri) {
        val manager = Injekt.get<AnimeExtensionManager>()
        if (!verifying.add(downloadId)) return
        manager.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val expected = expectedPackages[downloadId] ?: run {
                verifying.remove(downloadId)
                return@launch
            }
            val staged = File(context.cacheDir, "verified_extension_$downloadId.apk")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Downloaded APK unavailable")
                val live = manager.installedExtensionsFlow.value.firstOrNull { it.pkgName == expected.packageName }
                val current = expected.copy(
                    installedVersion = live?.versionCode ?: expected.installedVersion,
                    installed = live?.metadata ?: expected.installed,
                    approvedManualRepository = live?.let(manager::approvedManualRepository),
                )
                if (!ExtensionApkValidator.validate(context, staged, current)) {
                    manager.updateInstallStep(downloadId, InstallStep.Error)
                    return@launch
                }
                staged.setReadOnly()
                verifiedFiles[downloadId] = staged
                withUIContext {
                    if (downloadId in activeDownloads.values &&
                        manager.isInstallationAllowed(expected.packageName, expected.versionCode, expected.repository)
                    ) {
                        installVerifiedApk(downloadId, staged.getUriCompat(context))
                    } else {
                        verifiedFiles.remove(downloadId)?.delete()
                        manager.updateInstallStep(downloadId, InstallStep.Error)
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Extension APK validation failed" }
                manager.updateInstallStep(downloadId, InstallStep.Error)
            } finally {
                verifying.remove(downloadId)
                if (verifiedFiles[downloadId] !== staged) staged.delete()
            }
        }
    }

    private fun installVerifiedApk(downloadId: Long, uri: Uri) {
        when (val installer = extensionInstaller.get()) {
            BasePreferences.ExtensionInstaller.LEGACY -> {
                val intent = Intent(context, AnimeExtensionInstallActivity::class.java)
                    .setDataAndType(uri, APK_MIME)
                    .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                    .setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )

                context.startActivity(intent)
            }
            BasePreferences.ExtensionInstaller.PRIVATE -> {
                val extensionManager = Injekt.get<AnimeExtensionManager>()
                val tempFile = File(context.cacheDir, "temp_$downloadId")

                if (tempFile.exists() && !tempFile.delete()) {
                    // Unlikely but just in case
                    extensionManager.updateInstallStep(downloadId, InstallStep.Error)
                    return
                }

                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (AnimeExtensionLoader.installPrivateExtensionFile(context, tempFile)) {
                        extensionManager.updateInstallStep(downloadId, InstallStep.Installed)
                    } else {
                        extensionManager.updateInstallStep(downloadId, InstallStep.Error)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to read downloaded extension file." }
                    extensionManager.updateInstallStep(downloadId, InstallStep.Error)
                }

                tempFile.delete()
            }
            else -> {
                val intent =
                    AnimeExtensionInstallService.getIntent(context, downloadId, uri, installer)
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    /**
     * Cancels extension install and remove from download manager and installer.
     */
    fun cancelInstall(pkgName: String) {
        val downloadId = activeDownloads.remove(pkgName) ?: return
        downloadsStateFlows[downloadId]?.value = InstallStep.Idle
        expectedPackages.remove(downloadId)
        verifiedFiles.remove(downloadId)?.delete()
        downloadManager.remove(downloadId)
        InstallerAnime.cancelInstallQueue(context, downloadId)
    }

    /**
     * Starts an intent to uninstall the extension by the given package name.
     *
     * @param pkgName The package name of the extension to uninstall
     */
    fun uninstallApk(pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            AnimeExtensionLoader.uninstallPrivateExtension(context, pkgName)
            AnimeExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Sets the step of the installation of an extension.
     *
     * @param downloadId The id of the download.
     * @param step New install step.
     */
    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        downloadsStateFlows[downloadId]?.let { it.value = step }
    }

    /**
     * Deletes the download for the given package name.
     *
     * @param pkgName The package name of the download to delete.
     */
    private fun deleteDownload(pkgName: String, expectedId: Long? = null) {
        if (expectedId != null && activeDownloads[pkgName] != expectedId) {
            downloadsStateFlows.remove(expectedId)
            expectedPackages.remove(expectedId)
            verifiedFiles.remove(expectedId)?.delete()
            return
        }
        val downloadId = activeDownloads.remove(pkgName)
        if (downloadId != null) {
            expectedPackages.remove(downloadId)
            verifiedFiles.remove(downloadId)?.delete()
            downloadManager.remove(downloadId)
            downloadsStateFlows.remove(downloadId)?.value = InstallStep.Idle
        }
        if (activeDownloads.isEmpty()) {
            downloadReceiver.unregister()
        }
    }

    /**
     * Receiver that listens to download status events.
     */
    private inner class DownloadCompletionReceiver : BroadcastReceiver() {

        /**
         * Whether this receiver is currently registered.
         */
        private var isRegistered = false

        /**
         * Registers this receiver if it's not already.
         */
        fun register() {
            if (isRegistered) return
            isRegistered = true

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_EXPORTED)
        }

        /**
         * Unregisters this receiver if it's not already.
         */
        fun unregister() {
            if (!isRegistered) return
            isRegistered = false

            context.unregisterReceiver(this)
        }

        /**
         * Called when a download event is received. It looks for the download in the current active
         * downloads and notifies its installation step.
         */
        override fun onReceive(context: Context, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0) ?: return

            // Avoid events for downloads we didn't request
            if (id !in activeDownloads.values) return

            val uri = downloadManager.getUriForDownloadedFile(id)

            // Set next installation step
            if (uri == null) {
                logcat(LogPriority.ERROR) { "Couldn't locate downloaded APK" }
                updateInstallStep(id, InstallStep.Error)
                return
            }

            val query = DownloadManager.Query().setFilterById(id)
            downloadManager.query(query).use { cursor ->
                if (cursor.moveToFirst()) {
                    val localUri = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
                    ).removePrefix(FILE_SCHEME)

                    installApk(id, File(localUri).getUriCompat(context))
                }
            }
        }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_DOWNLOAD_ID = "AnimeExtensionInstaller.extra.DOWNLOAD_ID"
        const val FILE_SCHEME = "file://"
    }
}
