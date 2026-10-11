package it.lorelore.nyanime.tracking

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class TrackingEditWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (PersonalTrackingStore.get(applicationContext).pending().isEmpty()) return Result.success()
        if (Injekt.get<BasePreferences>().incognitoMode().get()) return Result.retry()
        if (runAttemptCount >= 6) return Result.failure()
        return if (TrackingRemoteGateway(applicationContext).drain()) Result.success() else Result.retry()
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Call on IO: wait until WorkManager has durably accepted the request. */
        fun schedule(context: Context, immediately: Boolean = false) {
            val app = context.applicationContext
            app.workManager.enqueueUniqueWork(
                "personal-tracking-edits-v1",
                if (immediately) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<TrackingEditWorker>()
                    .setInitialDelay(if (immediately) 0 else 400, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            ).result.get(30, TimeUnit.SECONDS)
        }

        fun resumePending(context: Context) {
            if (!context.getDatabasePath("personal-tracking.sqlite").exists()) return
            scope.launch {
                runCatching {
                    if (PersonalTrackingStore.get(context).pending().isNotEmpty()) schedule(context)
                }
            }
        }
    }
}
