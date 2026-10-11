package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.security.MessageDigest
import java.util.UUID

class BackupRestoreJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = BackupNotifier(context)

    override suspend fun doWork(): Result {
        val uri = inputData.getString(LOCATION_URI_KEY)?.toUri()
        val options = inputData.getBooleanArray(OPTIONS_KEY)?.let { RestoreOptions.fromBooleanArray(it) }

        if (uri == null || options == null) {
            return Result.failure()
        }

        val isSync = inputData.getBoolean(SYNC_KEY, false)

        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            logcat(LogPriority.ERROR, e) { "Not allowed to run on foreground service" }
        }

        return try {
            var lastPercent = -1
            val report = BackupRestorer(context, notifier, isSync, onProgress = { completed, total ->
                val percent = if (total > 0) completed * 100 / total else 0
                if (percent != lastPercent) {
                    lastPercent = percent
                    setProgress(workDataOf("completed" to completed, "total" to total))
                }
            }).restore(uri, options)
            eu.kanade.tachiyomi.data.cloud.sync.CloudSyncManager.backupRestored()
            Result.success(
                workDataOf(
                    "error_count" to report.errorCount,
                    "duration_ms" to report.durationMillis,
                    "error_log" to report.errorLogPath,
                ),
            )
        } catch (e: Exception) {
            if (e is CancellationException) {
                notifier.showRestoreError(context.stringResource(MR.strings.restoring_backup_canceled))
                Result.success()
            } else {
                logcat(LogPriority.ERROR, e)
                notifier.showRestoreError(e.message)
                Result.failure()
            }
        } finally {
            context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_RESTORE_PROGRESS,
            notifier.showRestoreProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        fun observe(context: Context) = context.workManager.getWorkInfosForUniqueWorkFlow(TAG)

        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG)
        }

        fun start(
            context: Context,
            uri: Uri,
            options: RestoreOptions,
            sync: Boolean = false,
        ): UUID {
            val inputData = workDataOf(
                LOCATION_URI_KEY to uri.toString(),
                SYNC_KEY to sync,
                OPTIONS_KEY to options.asBooleanArray(),
            )
            val request = OneTimeWorkRequestBuilder<BackupRestoreJob>()
                .addTag(TAG)
                .addTag(sourceTag(uri))
                .setInputData(inputData)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
            return request.id
        }

        /** Observe the actual kept job, including a restore already running in the background. */
        suspend fun activeFor(context: Context, uri: Uri): UUID? = withContext(Dispatchers.IO) {
            context.workManager.getWorkInfosForUniqueWork(TAG).get()
                .firstOrNull { !it.state.isFinished && sourceTag(uri) in it.tags }?.id
        }

        suspend fun startTracked(context: Context, uri: Uri, options: RestoreOptions): UUID =
            withContext(Dispatchers.IO) {
                val active = context.workManager.getWorkInfosForUniqueWork(TAG).get().firstOrNull {
                    !it.state.isFinished
                }
                if (active != null) {
                    if (sourceTag(uri) !in active.tags) throw RestoreAlreadyRunningException()
                    return@withContext active.id
                }
                val id = start(context, uri, options)
                // Queries share WorkManager's serial task executor with enqueueUniqueWork.
                context.workManager.getWorkInfoById(id).get()?.id
                    ?: context.workManager.getWorkInfosForUniqueWork(TAG).get().first { !it.state.isFinished }.id
            }

        private fun sourceTag(uri: Uri): String = "restore-source:" +
            MessageDigest.getInstance("SHA-256")
                .digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}

class RestoreAlreadyRunningException : IllegalStateException("Another backup restore is running")

private const val TAG = "BackupRestore"

private const val LOCATION_URI_KEY = "location_uri" // String

private const val SYNC_KEY = "sync" // Boolean
private const val OPTIONS_KEY = "options" // BooleanArray
