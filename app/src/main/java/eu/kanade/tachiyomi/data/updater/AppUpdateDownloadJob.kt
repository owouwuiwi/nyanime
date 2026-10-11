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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.storage.saveTo
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class AppUpdateDownloadJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = AppUpdateNotifier(context)
    private val network: NetworkHelper by injectLazy()

    override suspend fun doWork(): Result {
        val url = inputData.getString(EXTRA_DOWNLOAD_URL)
        val title = inputData.getString(EXTRA_DOWNLOAD_TITLE) ?: context.stringResource(MR.strings.app_name)

        if (url.isNullOrEmpty()) {
            return Result.failure()
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
            throw e
        } catch (e: Exception) {
            notifier.cancel()
            if (UpdateDownloadPolicy.shouldRetry(e, runAttemptCount)) {
                Result.retry()
            } else {
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
            // Progress of the download
            var savedProgress = 0

            // Keep track of the last notification sent to avoid posting too many.
            var lastTick = 0L

            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                downloadContext.ensureActive()
                val progress = UpdateDownloadPolicy.progress(bytesRead, contentLength) ?: return
                val currentTime = System.currentTimeMillis()
                if (progress > savedProgress && currentTime - 200 > lastTick) {
                    savedProgress = progress
                    lastTick = currentTime
                    notifier.onProgressChange(progress)
                    setProgressAsync(workDataOf(PROGRESS to progress))
                }
            }
        }

        val directory = context.externalCacheDir ?: context.cacheDir
        val temporary = File(directory, "update-$id.tmp")
        val apkFile = File(directory, "update-$id.apk")
        try {
            network.client.newCachelessCallWithProgress(GET(url), progressListener).await().use { response ->
                if (!response.isSuccessful) throw UpdateHttpException(response.code)
                val expectedLength = response.body.contentLength()
                response.body.source().saveTo(temporary)
                downloadContext.ensureActive()
                if (temporary.length() == 0L || (expectedLength >= 0 && temporary.length() != expectedLength)) {
                    throw IOException("Incomplete APK download")
                }
            }
            val info = context.packageManager.getPackageArchiveInfo(temporary.absolutePath, 0)
            require(info?.packageName == context.packageName) { "Invalid application update" }
            downloadContext.ensureActive()
            if (!temporary.renameTo(apkFile)) throw IOException("Unable to finalize APK download")
            notifier.cancel()
            notifier.promptInstall(apkFile.getUriCompat(context))
            return apkFile
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private const val TAG = "AppUpdateDownload"

        const val EXTRA_DOWNLOAD_URL = "DOWNLOAD_URL"
        const val EXTRA_DOWNLOAD_TITLE = "DOWNLOAD_TITLE"
        const val PROGRESS = "PROGRESS"
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
