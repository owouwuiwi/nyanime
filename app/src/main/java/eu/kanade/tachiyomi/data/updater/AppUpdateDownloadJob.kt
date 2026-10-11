package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.updater.delta.DeltaPatch
import eu.kanade.tachiyomi.data.updater.delta.DeltaReleaseLocation
import eu.kanade.tachiyomi.data.updater.delta.DeltaUpdateClient
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.workManager
import it.lorelore.nyanime.update.ReleaseGraph
import it.lorelore.nyanime.update.ReleaseGraphClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class AppUpdateDownloadJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = AppUpdateNotifier(context)
    private val network: NetworkHelper by injectLazy()
    private var activePhase = UpdateScreenPhase.DOWNLOADING
    private var savedProgress = -1
    private var lastTick = 0L

    override suspend fun doWork(): Result {
        val url = inputData.getString(EXTRA_DOWNLOAD_URL)
        val title = inputData.getString(EXTRA_DOWNLOAD_TITLE) ?: context.stringResource(MR.strings.app_name)

        if (url.isNullOrEmpty()) {
            return Result.failure()
        }
        if (UpdateDownloadPolicy.isAlreadyInstalled(
                url,
                context.packageManager.getPackageInfo(context.packageName, 0).versionName,
            )
        ) {
            notifier.cancel()
            return Result.success()
        }

        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            logcat(LogPriority.ERROR, e) { "Not allowed to run on foreground service" }
        }

        return try {
            val apkFile = withIOContext { downloadApk(title, url) }
            Result.success(workDataOf(OUTPUT_APK_PATH to apkFile.absolutePath))
        } catch (e: CancellationException) {
            notifier.cancel()
            ReleaseGraphClient.discard(File(context.cacheDir, "app-updates"), id.toString())
            throw e
        } catch (e: Exception) {
            notifier.cancel()
            currentCoroutineContext().ensureActive()
            if (UpdateDownloadPolicy.shouldRetry(e, runAttemptCount)) {
                Result.retry()
            } else {
                ReleaseGraphClient.discard(File(context.cacheDir, "app-updates"), id.toString())
                notifier.onDownloadError(url)
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_APP_UPDATER,
            notifier.onDownloadStarted().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    /**
     * Called to start downloading apk of new update
     *
     * @param url url location of file
     */
    private suspend fun downloadApk(title: String, url: String): File {
        // Show notification download starting.
        notifier.onDownloadStarted(title)

        val downloadContext = currentCoroutineContext()
        val progressListener = object : ProgressListener {
            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                downloadContext.ensureActive()
                val progress = UpdateDownloadPolicy.progress(bytesRead, contentLength) ?: return
                reportProgress(progress)
            }
        }

        // Update bytes stay private until exposed through our read-only installer provider.
        val directory = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val temporary = File(directory, "update-$id.tmp")
        val apkFile = File(directory, "update-$id.apk")
        val rebuilt = File(directory, "update-$id.rebuilt")
        try {
            val delta = if (ReleaseGraph.descriptorVersion(url, GITHUB_REPO) != null) {
                // A patch-only release has no same-version APK URL to invent as a fallback.
                ReleaseGraphClient(
                    context,
                    network.client,
                ).prepare(url, directory, id.toString(), ::stage, ::reportProgress)
            } else {
                DeltaUpdateClient(
                    context,
                    network.client,
                ).prepare(url, directory, id.toString(), ::stage, ::reportProgress)
            }
            if (delta != null) {
                if (!delta.renameTo(temporary)) throw IOException("Unable to finalize reconstructed APK")
            } else {
                stage(UpdateScreenPhase.DOWNLOADING_FULL)
                network.client.newCachelessCallWithProgress(
                    GET(url),
                    progressListener,
                ).awaitUpdateResponse().use { response ->
                    if (!response.isSuccessful) throw UpdateHttpException(response.code)
                    val expectedLength = response.body.contentLength()
                    require(expectedLength <= DeltaPatch.MAX_APK_SIZE) { "APK exceeds size limit" }
                    if (expectedLength > 0 && directory.usableSpace < expectedLength + 16 * 1024 * 1024) {
                        throw IOException("Insufficient space for application update")
                    }
                    response.body.byteStream().use { input ->
                        FileOutputStream(temporary).use { destination ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                downloadContext.ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                total += count
                                require(total <= DeltaPatch.MAX_APK_SIZE) { "APK exceeds size limit" }
                                destination.write(buffer, 0, count)
                            }
                            if (total == 0L || (expectedLength >= 0 && total != expectedLength)) {
                                throw IOException("Incomplete APK download")
                            }
                            destination.fd.sync()
                        }
                    }
                }
                stage(UpdateScreenPhase.VERIFYING)
                val expectedName = DeltaReleaseLocation.from(url)?.versionName
                AppUpdateApkValidator.validate(context, temporary, expectedName = expectedName)
                downloadContext.ensureActive()
            }
            downloadContext.ensureActive()
            if (!temporary.renameTo(apkFile)) throw IOException("Unable to finalize APK download")
            notifier.cancel()
            notifier.promptInstall(apkFile.getUriCompat(context))
            return apkFile
        } finally {
            temporary.delete()
            rebuilt.delete()
            File(directory, "update-$id.nyadelta").delete()
        }
    }

    private suspend fun stage(phase: UpdateScreenPhase) {
        currentCoroutineContext().ensureActive()
        activePhase = phase
        savedProgress = -1
        lastTick = 0L
        setProgress(workDataOf(STAGE to phase.name, PROGRESS to -1))
        notifier.onPhaseChange(phase)
    }

    private fun reportProgress(progress: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (progress > savedProgress && (now - lastTick >= 200 || progress == 100)) {
            savedProgress = progress
            lastTick = now
            notifier.onProgressChange(progress)
            setProgressAsync(workDataOf(STAGE to activePhase.name, PROGRESS to progress))
        }
    }

    companion object {
        private const val TAG = "AppUpdateDownload"

        const val EXTRA_DOWNLOAD_URL = "DOWNLOAD_URL"
        const val EXTRA_DOWNLOAD_TITLE = "DOWNLOAD_TITLE"
        const val PROGRESS = "PROGRESS"
        const val STAGE = "STAGE"
        const val OUTPUT_APK_PATH = "APK_PATH"

        fun start(context: Context, url: String, title: String? = null): java.util.UUID {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<AppUpdateDownloadJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(TAG)
                .addTag(updateTag(url))
                .setInputData(
                    workDataOf(
                        EXTRA_DOWNLOAD_URL to url,
                        EXTRA_DOWNLOAD_TITLE to title,
                    ),
                )
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        fun observe(context: Context) = context.workManager.getWorkInfosForUniqueWorkFlow(TAG)

        fun updateTag(url: String) = "app-update-${url.hashCode()}"

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}
