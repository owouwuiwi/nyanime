package eu.kanade.tachiyomi.data.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.rin.RinSessions
import eu.kanade.tachiyomi.ui.cloud.CloudActivity
import kotlinx.coroutines.CancellationException
import nyanime.telegram.CloudPhase
import nyanime.telegram.CloudProgress
import nyanime.telegram.TelegramErrors
import nyanime.telegram.TelegramException
import nyanime.telegram.TelegramFailure
import java.util.concurrent.TimeUnit

class CloudBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE) return Result.success()
        val repository = CloudRepository.get(applicationContext)
        repository.awaitInitialization()
        val automatic = inputData.getBoolean("automatic", true)
        val maintenanceOnly = inputData.getBoolean("maintenanceOnly", false)
        if (!repository.configured || automatic && !maintenanceOnly && !repository.preferences.enabled().get()) {
            return Result.success()
        }
        if (automatic && repository.preferences.wifiOnly().get()) {
            val network = applicationContext.getSystemService(ConnectivityManager::class.java)
            val capabilities = network.activeNetwork?.let(network::getNetworkCapabilities)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                repository.progress.value = CloudProgress(CloudPhase.WAITING)
                return Result.retry()
            }
        }
        if (automatic && RinSessions.busy.value) {
            repository.progress.value = CloudProgress(CloudPhase.WAITING)
            return Result.retry()
        }
        if (WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWork("BackupRestore").get().any {
                !it.state.isFinished
            }
        ) {
            return Result.retry()
        }
        val token = repository.client.retain()
        try {
            setForeground(notification())
            if (maintenanceOnly) {
                repository.maintainPendingBackups()
                return if (repository.backupMaintenancePending) Result.retry() else Result.success()
            }
            repository.backup(automatic)
            if (repository.backupMaintenancePending) maintainLater(applicationContext)
            return Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: TelegramException) {
            repository.progress.value = CloudProgress(CloudPhase.WAITING)
            if (error.failure == TelegramFailure.RATE_LIMIT) {
                retryAfter(applicationContext, error.retryAfterSeconds.coerceAtLeast(60), automatic)
                return Result.success()
            }
            if (!TelegramErrors.retryable(error)) {
                repository.progress.value = CloudProgress(CloudPhase.FAILED)
                return Result.failure(workDataOf("failure" to error.failure.name))
            }
            return Result.retry()
        } catch (_: Exception) {
            repository.progress.value = CloudProgress(CloudPhase.FAILED)
            return Result.failure()
        } finally {
            if (repository.progress.value.phase in
                listOf(CloudPhase.PREPARING, CloudPhase.UPLOADING, CloudPhase.CHECKING)
            ) {
                repository.progress.value = CloudProgress(CloudPhase.WAITING)
            }
            repository.client.release(token)
        }
    }

    private fun notification(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                applicationContext.getString(R.string.cloud_title),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val intent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, CloudActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(applicationContext.getString(R.string.cloud_title))
            .setContentText(applicationContext.getString(R.string.cloud_transfer_notification))
            .setContentIntent(intent).setOngoing(true).setOnlyAlertOnce(true).build()
        return if (Build.VERSION.SDK_INT >=
            29
        ) {
            ForegroundInfo(21801, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(21801, notification)
        }
    }

    companion object {
        private const val CHANNEL = "cloud-backups"
        const val AUTOMATIC = "nyanime-cloud-automatic"
        const val MANUAL = "nyanime-cloud-manual"
        const val RETRY_MANUAL = "nyanime-cloud-retry-manual"
        const val RETRY_AUTOMATIC = "nyanime-cloud-retry-automatic"
        const val MAINTENANCE = "nyanime-cloud-backup-maintenance"
        fun schedule(context: Context) {
            val preferences = CloudPreferences()
            val manager = WorkManager.getInstance(context)
            if (!preferences.enabled().get()) {
                manager.cancelUniqueWork(AUTOMATIC)
                manager.cancelUniqueWork(RETRY_AUTOMATIC)
                // A retention pass already requested by a manual backup must still finish.
                return
            }
            val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(
                preferences.intervalHours().get().coerceIn(6, 168).toLong(),
                TimeUnit.HOURS,
            )
                .setConstraints(
                    constraints(),
                ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
            manager.enqueueUniquePeriodicWork(AUTOMATIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        fun saveNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL,
                // A manual retry must bypass an old backoff. The durable outbox and
                // server reconciliation resume the same upload if cancellation raced its start.
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CloudBackupWorker>().setInputData(workDataOf("automatic" to false))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build(),
            )
        }
        private fun maintainLater(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                MAINTENANCE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CloudBackupWorker>().setInitialDelay(15, TimeUnit.MINUTES)
                    .setInputData(workDataOf("automatic" to true, "maintenanceOnly" to true))
                    .setConstraints(constraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build(),
            )
        }
        private fun retryAfter(context: Context, seconds: Long, automatic: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (automatic) RETRY_AUTOMATIC else RETRY_MANUAL,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CloudBackupWorker>().setInitialDelay(seconds, TimeUnit.SECONDS)
                    .setInputData(
                        workDataOf("automatic" to automatic),
                    ).setConstraints(constraints()).build(),
            )
        }
        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true).build()
    }
}
