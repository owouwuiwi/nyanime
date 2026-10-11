package it.lorelore.nyanime.tracking

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Urgent writes and the next retry wake the same durable queue; a long cooldown cannot block a new edit. */
class TrackingEditWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val isolated = eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE
        val requestedNonce = inputData.getString("probeNonce").orEmpty()
        if (!isolated && requestedNonce.isNotEmpty()) return@withContext Result.success()
        val probe = if (isolated) {
            TrackingWorkerProbeRuntime.current?.takeIf {
                it.nonce == requestedNonce
            }
        } else {
            null
        }
        if (isolated && probe == null) {
            return@withContext if (requestedNonce.isEmpty()) Result.retry() else Result.success()
        }
        val timer = inputData.getBoolean("retryTimer", false)
        synchronized(scheduler) {
            if (timer) {
                if (expectedTimer != null && expectedTimer != id) return@withContext Result.success()
                activeTimers += id
                nextTimerAt = Long.MAX_VALUE
            }
        }
        probe?.active?.incrementAndGet()
        try {
            if (isStopped) return@withContext Result.success()
            drainMutex.withLock {
                if (isStopped) return@withLock
                if (probe == null) TrackingLegacyMigration.migrate(applicationContext)
                val store = probe?.store ?: PersonalTrackingStore.get(applicationContext)
                store.recoverInterrupted()
                if (probe == null && Injekt.get<BasePreferences>().incognitoMode().get()) return@withLock
                val gateway = TrackingRemoteGateway(applicationContext, store, probe?.now ?: System::currentTimeMillis)
                if (probe == null) gateway.drain() else gateway.drain(probe.sender)
            }
            Result.success()
        } finally {
            synchronized(scheduler) { activeTimers -= id }
            try {
                if (!isStopped && (probe != null || !Injekt.get<BasePreferences>().incognitoMode().get())) {
                    scheduleNext(applicationContext)
                }
            } finally {
                probe?.active?.decrementAndGet()
            }
        }
    }

    companion object {
        private const val URGENT = "personal-tracking-edits-v1"
        private const val TIMER = "personal-tracking-retry-v2"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val drainMutex = Mutex()
        private val scheduler = Any()
        private val activeTimers = mutableSetOf<UUID>()
        private var expectedTimer: UUID? = null
        private var nextTimerAt = Long.MAX_VALUE

        /** IO only. At most one running urgent pass and one durable successor, even under rapid taps. */
        fun schedule(context: Context, immediately: Boolean = false) = synchronized(scheduler) {
            val probe = if (BuildConfig.ISOLATED_CLOUD_PROBE) TrackingWorkerProbeRuntime.current else null
            if (eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE && probe == null) return@synchronized
            val app = context.applicationContext
            val name = if (probe == null) URGENT else "$URGENT:probe:${probe.nonce}"
            val existing = app.workManager.getWorkInfosForUniqueWork(name).get(30, TimeUnit.SECONDS)
            if (existing.any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                }
            ) {
                return@synchronized
            }
            app.workManager.enqueueUniqueWork(
                name,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<TrackingEditWorker>()
                    .setInputData(workDataOf("probeNonce" to probe?.nonce.orEmpty()))
                    .addTag(probe?.nonce ?: "personal-tracking-edits")
                    .setInitialDelay(if (immediately) 0 else 400, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            ).result.get(30, TimeUnit.SECONDS)
        }

        private fun scheduleNext(context: Context) = synchronized(scheduler) {
            val probe = if (BuildConfig.ISOLATED_CLOUD_PROBE) TrackingWorkerProbeRuntime.current else null
            if (eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE && probe == null) return@synchronized
            if (activeTimers.isNotEmpty()) return@synchronized
            val store = probe?.store ?: PersonalTrackingStore.get(context)
            val pending = store.pending().filter { it.delivery != TrackingDelivery.BLOCKED }
            val at = pending.minOfOrNull { maxOf(it.notBefore, store.cooldown(it.trackerId)) } ?: return@synchronized
            val name = if (probe == null) TIMER else "$TIMER:probe:${probe.nonce}"
            val existing = context.workManager.getWorkInfosForUniqueWork(name).get(30, TimeUnit.SECONDS)
            if (nextTimerAt <= at && existing.any { it.state == WorkInfo.State.ENQUEUED }) return@synchronized
            val clock = probe?.now?.invoke() ?: System.currentTimeMillis()
            val request = OneTimeWorkRequestBuilder<TrackingEditWorker>()
                .setInputData(workDataOf("retryTimer" to true, "probeNonce" to probe?.nonce.orEmpty()))
                .addTag(probe?.nonce ?: "personal-tracking-edits")
                .setInitialDelay((at - clock).coerceAtLeast(500), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            // Startup and replacement share this handshake. Only waiting/finished timer work is
            // superseded; a timer inside the drain is never cancelled to expedite another edit.
            expectedTimer = request.id
            nextTimerAt = at
            context.workManager.enqueueUniqueWork(
                name,
                ExistingWorkPolicy.REPLACE,
                request,
            ).result.get(30, TimeUnit.SECONDS)
        }

        fun resumePending(context: Context) {
            val probe = if (BuildConfig.ISOLATED_CLOUD_PROBE) TrackingWorkerProbeRuntime.current else null
            if (eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE && probe == null) return
            scope.launch {
                runCatching {
                    if (probe == null) {
                        TrackingLegacyMigration.migrate(context)
                        if (!context.getDatabasePath("personal-tracking.sqlite").exists()) return@runCatching
                    }
                    val store = probe?.store ?: PersonalTrackingStore.get(context)
                    drainMutex.withLock { store.recoverInterrupted() }
                    if (store.pending().any { it.delivery != TrackingDelivery.BLOCKED }) schedule(context)
                }
            }
        }
    }
}
