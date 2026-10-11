package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.telegram.TelegramClient
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class CloudSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE) return Result.success()
        val manager = CloudSyncManager.get(applicationContext)
        if (!manager.preferences.enabled().get()) return Result.success()
        val client = TelegramClient.get(applicationContext)
        val lease = client.retain()
        return try {
            val synchronized = withTimeoutOrNull(120_000) { manager.tick() } == true
            if (synchronized) withTimeoutOrNull(60_000) { manager.maintainIdleHistory() }
            if (synchronized) Result.success() else Result.retry()
        } finally {
            client.release(lease)
        }
    }

    companion object {
        private const val NAME = "telegram-personal-sync"
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            if (!CloudSyncPreferences().enabled().get()) {
                work.cancelUniqueWork(NAME)
                return
            }
            work.enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
