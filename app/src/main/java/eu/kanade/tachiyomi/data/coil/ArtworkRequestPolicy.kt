package eu.kanade.tachiyomi.data.coil

import okhttp3.Call
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Opt-in limits for visible Home artwork; source clients, cookies and headers stay intact. */
object ArtworkRequestPolicy {
    const val HOME_TIMEOUT_MILLIS = 15_000L
    const val RETRY_DELAY_MILLIS = 1_200L

    fun shouldRetry(error: Throwable, attempt: Int): Boolean = attempt == 0 &&
        when (error) {
            is ArtworkHttpException -> error.code == 408 || error.code in 500..599
            is IOException -> true
            else -> false
        }

    fun limit(call: Call, timeoutMillis: Long): Call = call.apply {
        if (timeoutMillis <= 0) return@apply
        val limit = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val existing = timeout().timeoutNanos()
        if (existing == 0L || existing > limit) timeout().timeout(limit, TimeUnit.NANOSECONDS)
    }
}

class ArtworkHttpException(val code: Int) : IOException("Image request failed: HTTP $code")
