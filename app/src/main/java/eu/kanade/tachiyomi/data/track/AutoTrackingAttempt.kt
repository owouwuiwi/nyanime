package eu.kanade.tachiyomi.data.track

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal data class AutoTrackingAttempt(val linked: Boolean = false, val retryable: Boolean = false) {
    companion object {
        val Complete = AutoTrackingAttempt()
        val Linked = AutoTrackingAttempt(linked = true)
        val Retry = AutoTrackingAttempt(retryable = true)

        fun combine(attempts: List<AutoTrackingAttempt>) = AutoTrackingAttempt(
            linked = attempts.any { it.linked },
            retryable = attempts.any { it.retryable },
        )
    }
}

/** Temporary failures get a finite retry window; ambiguity and missing login never loop. */
internal object AutoTrackingRetry {
    fun delayAfter(attempt: Int): Long? = when (attempt) {
        0 -> 15_000L
        1 -> 60_000L
        else -> null
    }

    suspend fun run(
        wait: suspend (Long) -> Unit = { delay(it) },
        action: suspend () -> AutoTrackingAttempt,
    ): AutoTrackingAttempt {
        var linked = false
        var attempt = 0
        while (true) {
            val result = action()
            linked = linked || result.linked
            val pause = if (result.retryable) delayAfter(attempt++) else null
            if (pause == null) return result.copy(linked = linked)
            wait(pause)
        }
    }

    suspend fun safely(action: suspend () -> AutoTrackingAttempt): AutoTrackingAttempt = try {
        action()
    } catch (_: TimeoutCancellationException) {
        // Only a local timeout is retryable; cancellation of the owning scope still stops the loop.
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        AutoTrackingAttempt.Retry
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        logcat(LogPriority.WARN, error) {
            "Automatic tracking temporarily unavailable"
        }
        AutoTrackingAttempt.Retry
    }
}
