package it.lorelore.nyanime.tracking

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.workManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Reachable only in an explicitly isolated instrumentation build; ordinary builds ignore it. */
internal class TrackingWorkerProbeRuntime(
    val nonce: String,
    val store: PersonalTrackingStore,
    val now: () -> Long,
    val sender: suspend (PendingTrackingEdit) -> TrackingConfirmation,
) {
    init {
        require(java.util.UUID.fromString(nonce).toString() == nonce)
    }

    val active = AtomicInteger()

    companion object {
        @Volatile var current: TrackingWorkerProbeRuntime? = null

        private fun requireProbe(nonce: String) {
            check(BuildConfig.ISOLATED_CLOUD_PROBE)
            require(UUID.fromString(nonce).toString() == nonce)
        }

        // Called by the separate APK through this kept bridge, not through R8-inlined Android extensions.
        fun unfinished(context: Context, nonce: String): Int {
            requireProbe(nonce)
            return context.workManager.getWorkInfosByTag(nonce).get(20, TimeUnit.SECONDS)
                .count { !it.state.isFinished }
        }

        fun cancel(context: Context, nonce: String) {
            requireProbe(nonce)
            context.workManager.cancelAllWorkByTag(nonce).result.get(20, TimeUnit.SECONDS)
        }

        fun cleanInterruptedFixtures(context: Context) {
            check(BuildConfig.ISOLATED_CLOUD_PROBE && current == null)
            val cache = context.cacheDir.canonicalFile
            cache.listFiles().orEmpty().filter { it.name.startsWith("tracking-queue-probe-") }.forEach { root ->
                val nonce = root.name.removePrefix("tracking-queue-probe-")
                if (runCatching { UUID.fromString(nonce).toString() == nonce }.getOrDefault(false) &&
                    root.isDirectory &&
                    root.canonicalFile.parentFile == cache
                ) {
                    cancel(context, nonce)
                    context.deleteSharedPreferences("$nonce-tracking_queue")
                    check(root.deleteRecursively())
                }
            }
        }
    }
}
