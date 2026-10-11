package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.transfer.DownloadExecution
import eu.kanade.tachiyomi.data.download.transfer.DownloadKind
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * This worker is used to manage the downloader. The system can decide to stop the worker, in
 * which case the downloader is also stopped. It's also stopped while there's no network available.
 */
class MangaDownloadJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(applicationContext.getString(R.string.download_notifier_downloader_title))
            setSmallIcon(android.R.drawable.stat_sys_download)
        }.build()
        return ForegroundInfo(
            Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        setForegroundSafely()
        DownloadExecution.run(applicationContext, DownloadKind.MANGA)
        return Result.success()
    }

    companion object {
        private const val TAG = "MangaDownloader"

        fun start(context: Context, userInitiated: Boolean = true) {
            DownloadExecution.enqueue(context, DownloadKind.MANGA, userInitiated) {
                val wifi = Injekt.get<DownloadPreferences>().downloadOnlyOverWifi().get()
                val request = OneTimeWorkRequestBuilder<MangaDownloadJob>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(
                            if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED,
                        ).build(),
                    )
                    .addTag(TAG)
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
            }
        }

        fun stop(context: Context) {
            DownloadExecution.stop(context, DownloadKind.MANGA)
            WorkManager.getInstance(context)
                .cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context): Boolean {
            if (DownloadExecution.isUserJob(context, DownloadKind.MANGA)) return true
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(TAG)
                .get()
                .let { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { list -> list.any { it.state == WorkInfo.State.RUNNING } }
                .combine(DownloadExecution.userJobFlow(DownloadKind.MANGA)) { worker, user -> worker || user }
        }
    }
}
