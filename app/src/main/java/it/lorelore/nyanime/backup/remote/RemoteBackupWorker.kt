package it.lorelore.nyanime.backup.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class RemoteBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val provider =
            inputData.getString("provider")?.let { runCatching { RemoteBackupProvider.valueOf(it) }.getOrNull() }
                ?: return@withContext Result.failure()
        val repository = RemoteBackupRepository(applicationContext)
        val config = try {
            repository.vault.get(provider)
        } catch (_: Exception) {
            null
        }
            ?: return@withContext failed("configuration")
        if (config.generation != inputData.getString("generation")) return@withContext Result.failure()
        if (BackupRestoreJob.isRunning(applicationContext)) return@withContext Result.retry()
        val journals = File(applicationContext.noBackupFilesDir, "remote-backup-jobs").apply { mkdirs() }
        val journal = android.util.AtomicFile(File(journals, id.toString()))
        val runId = if (journal.baseFile.exists()) {
            journal.openRead().use { it.readBytes().toString(Charsets.US_ASCII) }
        } else {
            UUID.randomUUID().toString().also { value ->
                val output = journal.startWrite()
                try {
                    output.write(value.toByteArray())
                    journal.finishWrite(output)
                } catch (
                    failure: Exception,
                ) {
                    journal.failWrite(output)
                    throw failure
                }
            }
        }
        try {
            setForegroundSafely()
            repository.create(config, runId) { setProgress(workDataOf("phase" to it)) }
            journal.delete()
            Result.success(workDataOf("completed" to System.currentTimeMillis()))
        } catch (cancel: CancellationException) {
            if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.clearRun(runId)
                    journal.delete()
                }
            }
            throw cancel
        } catch (failure: Exception) {
            val reason = (failure as? RemoteStorageException)?.reason ?: "backup"
            val retry = reason in setOf("network", "server", "rate") && runAttemptCount < 2
            if (retry) {
                Result.retry()
            } else {
                repository.clearRun(runId)
                journal.delete()
                failed(reason)
            }
        }
    }

    private fun failed(reason: String) = Result.failure(
        workDataOf("error" to reason, "error_at" to System.currentTimeMillis()),
    )

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                applicationContext.getString(R.string.remote_backup_title),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(applicationContext.getString(R.string.remote_backup_title))
            .setContentText(applicationContext.getString(R.string.remote_phase_preparing))
            .setOngoing(true).setProgress(0, 0, true)
            .addAction(
                0,
                applicationContext.getString(R.string.remote_cancel),
                PendingIntent.getBroadcast(
                    applicationContext,
                    id.hashCode(),
                    Intent(applicationContext, RemoteBackupCancelReceiver::class.java)
                        .putExtra("provider", inputData.getString("provider"))
                        .putExtra("generation", inputData.getString("generation"))
                        .putExtra("work", id.toString()),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        return ForegroundInfo(
            0x710000 + (id.hashCode() and 0xffff),
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    companion object {
        private const val CHANNEL = "remote-backup"
        internal fun name(provider: RemoteBackupProvider) = "remote-backup-${provider.name}"
        internal fun start(context: Context, config: RemoteBackupConfiguration) {
            val request = OneTimeWorkRequestBuilder<RemoteBackupWorker>()
                .addTag("remote-generation-${config.generation}")
                .setInputData(workDataOf("provider" to config.provider.name, "generation" to config.generation))
                .setConstraints(constraints(config, periodic = false)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(config.provider), ExistingWorkPolicy.KEEP, request)
        }
        internal fun schedule(context: Context, config: RemoteBackupConfiguration, restart: Boolean = false) {
            val work = WorkManager.getInstance(context)
            val name = name(config.provider) + "-periodic"
            if (config.intervalHours == 0) {
                work.cancelUniqueWork(name)
                return
            }
            val request = PeriodicWorkRequestBuilder<RemoteBackupWorker>(config.intervalHours.toLong(), TimeUnit.HOURS)
                .addTag("remote-generation-${config.generation}")
                .setInitialDelay(config.intervalHours.toLong(), TimeUnit.HOURS)
                .setInputData(workDataOf("provider" to config.provider.name, "generation" to config.generation))
                .setConstraints(constraints(config)).build()
            work.enqueueUniquePeriodicWork(
                name,
                if (restart) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
        internal fun cancel(context: Context, provider: RemoteBackupProvider) {
            WorkManager.getInstance(context).cancelUniqueWork(name(provider))
            WorkManager.getInstance(context).cancelUniqueWork(name(provider) + "-periodic")
        }
        internal suspend fun cancelAttempt(
            context: Context,
            provider: RemoteBackupProvider,
            generation: String,
            workId: String? = null,
        ) {
            val vault = RemoteBackupVault(context)
            if (vault.get(provider)?.generation != generation) return
            val work = WorkManager.getInstance(context)
            if (workId != null &&
                work.getWorkInfoById(UUID.fromString(workId)).await()?.state?.isFinished != false
            ) {
                return
            }
            work.cancelUniqueWork(name(provider)).result.await()
            // A disconnect or destination change during cancellation must not resurrect old credentials.
            // WorkManager replaces the periodic attempt atomically; no gap can lose its future schedule.
            vault.get(provider)?.takeIf { it.generation == generation }?.let { schedule(context, it, restart = true) }
        }
        private fun constraints(config: RemoteBackupConfiguration, periodic: Boolean = true) = Constraints.Builder()
            .setRequiredNetworkType(if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(periodic).build()
    }
}
