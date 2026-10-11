package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.backup.BackupExportStore
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class BackupCreateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = BackupNotifier(context)
    private val backupPreferences: BackupPreferences = Injekt.get()

    override suspend fun doWork(): Result {
        val isAutoBackup = inputData.getBoolean(IS_AUTO_BACKUP_KEY, true)
        val isPublicManualBackup = !isAutoBackup && inputData.getBoolean(PUBLIC_DOWNLOADS_KEY, false)
        var pendingUri: Uri? = null

        if (isAutoBackup && BackupRestoreJob.isRunning(context)) return Result.retry()

        setForegroundSafely()

        val options = inputData.getBooleanArray(OPTIONS_KEY)?.let { BackupOptions.fromBooleanArray(it) }
            ?: BackupOptions()

        return try {
            setProgress(workDataOf(PROGRESS_PHASE to "preparing"))
            val uri =
                (if (isPublicManualBackup) BackupExportStore.createPending(context).also { pendingUri = it } else null)
                    ?: inputData.getString(LOCATION_URI_KEY)?.toUri()
                    ?: getAutomaticBackupLocation()
                    ?: throw IllegalStateException("Cartella backup non accessibile. Controlla Dati e archiviazione.")
            val location = BackupCreator(context, isAutoBackup).backup(uri, options) { phase ->
                setProgress(workDataOf(PROGRESS_PHASE to phase))
            }
            pendingUri?.let { BackupExportStore.publish(context, it) }
            if (isAutoBackup) {
                backupPreferences.autoBackupFailures().set(0)
                backupPreferences.autoBackupError().set("")
            }
            if (!isAutoBackup) {
                notifier.showBackupComplete(location.toUri(), if (isPublicManualBackup) "Download/Nyanime" else null)
            }
            Result.success(workDataOf(RESULT_URI to location))
        } catch (e: Exception) {
            pendingUri?.let { runCatching { BackupExportStore.delete(context, it) } }
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            if (isAutoBackup) {
                val failures = backupPreferences.autoBackupFailures().get().coerceIn(0, 1000) + 1
                backupPreferences.autoBackupFailures().set(failures)
                val message = "Backup automatico non riuscito. " +
                    "Controlla lo spazio e l’accesso alla cartella in Dati e archiviazione."
                backupPreferences.autoBackupError().set(message)
                if (BackupRetention.shouldNotify(failures)) notifier.showBackupError(message)
            } else {
                notifier.showBackupError(e.message)
            }
            Result.failure(workDataOf(RESULT_ERROR to (e.message ?: "Backup non riuscito.")))
        } finally {
            context.cancelNotification(Notifications.ID_BACKUP_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_BACKUP_PROGRESS,
            notifier.showBackupProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun getAutomaticBackupLocation(): Uri? {
        val storageManager = Injekt.get<StorageManager>()
        return storageManager.getAutomaticBackupsDirectory()?.uri
    }

    companion object {
        const val PROGRESS_PHASE = "progress_phase"
        const val RESULT_URI = "result_uri"
        const val RESULT_ERROR = "result_error"

        fun observeManual(context: Context) = context.workManager.getWorkInfosForUniqueWorkFlow(TAG_MANUAL)

        fun isManualJobRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG_MANUAL)
        }

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val backupPreferences = Injekt.get<BackupPreferences>()
            val interval = prefInterval ?: backupPreferences.backupInterval().get()
            if (interval > 0) {
                val constraints = Constraints(
                    requiresBatteryNotLow = true,
                )

                val request = PeriodicWorkRequestBuilder<BackupCreateJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                    .addTag(TAG_AUTO)
                    .setConstraints(constraints)
                    .setInputData(workDataOf(IS_AUTO_BACKUP_KEY to true))
                    .build()

                context.workManager.enqueueUniquePeriodicWork(TAG_AUTO, ExistingPeriodicWorkPolicy.UPDATE, request)
            } else {
                context.workManager.cancelUniqueWork(TAG_AUTO)
            }
        }

        fun startNow(context: Context, uri: Uri, options: BackupOptions) {
            val inputData = workDataOf(
                IS_AUTO_BACKUP_KEY to false,
                LOCATION_URI_KEY to uri.toString(),
                OPTIONS_KEY to options.asBooleanArray(),
            )
            val request = OneTimeWorkRequestBuilder<BackupCreateJob>()
                .addTag(TAG_MANUAL)
                .setInputData(inputData)
                .build()
            context.workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.KEEP, request)
        }

        fun startNow(context: Context, options: BackupOptions = BackupOptions.complete()) {
            val request = OneTimeWorkRequestBuilder<BackupCreateJob>()
                .addTag(TAG_MANUAL)
                .setInputData(
                    workDataOf(
                        IS_AUTO_BACKUP_KEY to false,
                        PUBLIC_DOWNLOADS_KEY to true,
                        OPTIONS_KEY to options.asBooleanArray(),
                    ),
                )
                .build()
            context.workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.KEEP, request)
        }
    }
}

private const val TAG_AUTO = "BackupCreator"
private const val TAG_MANUAL = "$TAG_AUTO:manual"

private const val IS_AUTO_BACKUP_KEY = "is_auto_backup" // Boolean
private const val LOCATION_URI_KEY = "location_uri" // String
private const val PUBLIC_DOWNLOADS_KEY = "public_downloads" // Boolean
private const val OPTIONS_KEY = "options" // BooleanArray
