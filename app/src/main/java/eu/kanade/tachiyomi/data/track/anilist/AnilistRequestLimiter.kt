package eu.kanade.tachiyomi.data.track.anilist

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** One budget for discovery and tracker requests, including manga tracking. */
object AnilistRequestLimiter : Interceptor {
    private val lock = Any()
    private var nextRequest = 0L
    private var blockedUntil = 0L
    private var intervalMillis = 2_100L

    override fun intercept(chain: Interceptor.Chain): Response {
        while (true) {
            if (chain.call().isCanceled()) throw IOException("Richiesta annullata")
            val wait = synchronized(lock) {
                val now = System.currentTimeMillis()
                if (blockedUntil > now) {
                    throw IOException("AniList: riprova tra ${(blockedUntil - now) / 1000 + 1} secondi")
                }
                (nextRequest - now).coerceAtLeast(0).also {
                    if (it == 0L) nextRequest = now + intervalMillis
                }
            }
            if (wait == 0L) break
            try {
                Thread.sleep(wait.coerceAtMost(100))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Richiesta annullata", e)
            }
        }
        val response = chain.proceed(chain.request())
        synchronized(lock) {
            response.header("X-RateLimit-Limit")?.toLongOrNull()?.takeIf { it > 0 }?.let {
                intervalMillis = maxOf(2_100L, 60_000L / it + 100)
            }
            if (response.code == 429 || response.header("X-RateLimit-Remaining") == "0") {
                val now = System.currentTimeMillis()
                val retry = response.header("Retry-After")?.toLongOrNull()?.times(1000)?.plus(now)
                val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                blockedUntil = maxOf(now + 1_000L, retry ?: reset ?: (now + 60_000L))
            }
        }
        return response
    }
}
