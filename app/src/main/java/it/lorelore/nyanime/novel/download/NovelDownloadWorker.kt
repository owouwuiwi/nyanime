package it.lorelore.nyanime.novel.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import it.lorelore.nyanime.novel.NovelDownloadException
import it.lorelore.nyanime.novel.NovelDownloadProblem
import it.lorelore.nyanime.novel.NovelDownloadProgress
import it.lorelore.nyanime.novel.NovelDownloader
import it.lorelore.nyanime.novel.source.NovelOnlineRepository
import it.lorelore.nyanime.novel.ui.NovelReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

class NovelDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    private var current: NovelDownloadProgress? = null
    private var title: String? = null

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val job =
                inputData.getString(JOB_ID)?.takeIf {
                    runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
                } ?: return@withContext Result.failure()
            // All jobs share one budget; reader/player remain responsive while many books are
            // queued.
            if (!budget.tryAcquire()) return@withContext Result.retry()
            try {
                val downloads = Injekt.get<NovelDownloads>()
                val lease = downloads.store.claim(job) ?: return@withContext Result.success()
                title = lease.plan.book.title
                current = downloads.store.progress(lease.plan.book.id)
                try {
                    setForegroundSafely()
                    var previousCompleted: Int? = null
                    Injekt.get<NovelOnlineRepository>()
                        .openNetwork(lease.plan.book, lease.plan.document)
                        .use { access ->
                            NovelDownloader(downloads.store).run(lease, access) { progress ->
                                current = progress
                                setProgress(
                                    workDataOf(
                                        "chapters" to progress.completedChapters,
                                        "total" to progress.selectedChapters,
                                        "bytes" to progress.savedBytes,
                                    ),
                                )
                                if (previousCompleted != progress.completedChapters) {
                                    downloads.availability(progress.bookId)
                                    previousCompleted = progress.completedChapters
                                }
                                // No separate fake percentage for images whose transfer size is
                                // unknown.
                                try {
                                    applicationContext
                                        .getSystemService(NotificationManager::class.java)
                                        .notify(notificationId, foreground().notification)
                                } catch (_: SecurityException) {
                                    /* Disabled notification permission cannot lose a download. */
                                }
                            }
                        }
                    Result.success()
                } catch (cancel: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        downloads.store.release(
                            lease,
                            paused = stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP,
                        )
                        downloads.availability(lease.plan.book.id)
                    }
                    throw cancel
                } catch (failure: Exception) {
                    val problem =
                        (failure as? NovelDownloadException)?.problem ?: NovelDownloadProblem.SOURCE
                    runCatching { downloads.store.finish(lease, problem) }
                    downloads.availability(lease.plan.book.id)
                    Result.failure(workDataOf("problem" to problem.name))
                } finally {
                    applicationContext
                        .getSystemService(NotificationManager::class.java)
                        .cancel(notificationId)
                }
            } finally {
                budget.release()
            }
        }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground()

    private fun foreground(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                applicationContext.getString(R.string.novel_download_title),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val progress = current
        val builder =
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(
                    title ?: applicationContext.getString(R.string.novel_download_title),
                )
                .setContentText(
                    if (progress == null) {
                        applicationContext.getString(R.string.novel_download_queued)
                    } else {
                        applicationContext.getString(
                            R.string.novel_download_progress,
                            progress.completedChapters,
                            progress.selectedChapters,
                        )
                    },
                )
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(
                    progress?.selectedChapters ?: 0,
                    progress?.completedChapters ?: 0,
                    progress == null,
                )
        val cancel =
            if (progress == null) {
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            } else {
                PendingIntent.getBroadcast(
                    applicationContext,
                    id.hashCode(),
                    Intent(applicationContext, NovelDownloadCancelReceiver::class.java)
                        .putExtra("book", progress.bookId)
                        .putExtra("job", progress.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        builder.addAction(0, applicationContext.getString(R.string.novel_download_pause), cancel)
        progress?.let {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    applicationContext,
                    id.hashCode(),
                    NovelReaderActivity.intent(applicationContext, it.bookId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return ForegroundInfo(
            notificationId,
            builder.build(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private val notificationId
        get() = 0x740000 + (id.hashCode() and 0xffff)

    companion object {
        internal const val JOB_ID = "job"
        private const val CHANNEL = "novel-downloads"
        private val budget = Semaphore(1)
    }
}
