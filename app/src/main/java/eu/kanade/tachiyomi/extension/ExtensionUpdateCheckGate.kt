package eu.kanade.tachiyomi.extension

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

internal enum class ExtensionUpdateKind(val key: String) {
    ANIME("anime"),
    MANGA("manga"),
}

/** Automatic checks survive Activity/process recreation; manual catalogue refreshes do not use this gate. */
internal class ExtensionUpdateCheckGate(
    private val preferences: PreferenceStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun <T> run(
        kind: ExtensionUpdateKind,
        complete: () -> Boolean = { true },
        check: suspend () -> T,
    ): T? = locks.getValue(kind).withLock {
        val success = preferences.getLong(Preference.appStateKey("extension_update_" + kind.key + "_success"), 0)
        val attempt = preferences.getLong(Preference.appStateKey("extension_update_" + kind.key + "_attempt"), 0)
        fun recent(timestamp: Long, interval: Long): Boolean =
            timestamp > 0 && now() - timestamp in 0 until interval
        if (recent(success.get(), 1.days.inWholeMilliseconds) ||
            recent(attempt.get(), 15.minutes.inWholeMilliseconds)
        ) {
            return@withLock null
        }
        attempt.set(now())
        try {
            val result = check()
            currentCoroutineContext().ensureActive()
            if (complete()) {
                success.set(now())
                attempt.delete()
            }
            result
        } catch (cancelled: CancellationException) {
            attempt.delete()
            throw cancelled
        }
    }

    companion object {
        // API objects are recreated with MainActivity: ownership must outlive those instances.
        private val locks = ExtensionUpdateKind.entries.associateWith { Mutex() }
    }
}
