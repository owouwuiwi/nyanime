package it.lorelore.nyanime.tracking

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Never persists credentials: a short-lived fingerprint is checked after provider authentication. */
object TrackingRequestGuard {
    private class Lease(val captured: String, val current: () -> String) {
        @Volatile var failure: TrackingQueueHttpFailure? = null
    }
    private val leases = ConcurrentHashMap<Long, Lease>()

    fun fingerprint(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("") { "${it.length}:$it" }.toByteArray())
        .joinToString("") { "%02x".format(it) }

    suspend fun <T> leased(tracker: Long, fingerprint: () -> String, block: suspend () -> T): T {
        val captured = fingerprint()
        val lease = Lease(captured, fingerprint)
        check(leases.putIfAbsent(tracker, lease) == null) { "Tracking account already leased" }
        return try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is TrackingCredentialsChanged) throw error
            val http = lease.failure
            if (http != null) {
                http.initCause(error)
                throw http
            }
            throw error
        } finally {
            leases.remove(tracker, lease)
        }
    }

    fun client(original: OkHttpClient, tracker: Long): OkHttpClient = original.newBuilder()
        .apply {
            if (eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE) {
                // Instrumentation uses a private fake gateway; no real tracker/auth request may escape.
                interceptors().add(0, Interceptor { throw java.net.ProtocolException("Isolated tracking probe") })
            }
        }
        .addNetworkInterceptor(
            Interceptor { chain ->
                val expected = leases[tracker]
                if (expected != null && expected.captured != expected.current()) throw TrackingCredentialsChanged()
                val response = chain.proceed(chain.request())
                if (expected != null && response.code >= 400) {
                    val delay = response.header("Retry-After")?.let { value ->
                        value.toLongOrNull()?.coerceIn(0, TrackingQueuePolicy.MAX_RETRY_AFTER / 1000)?.times(1000)
                            ?: runCatching {
                                java.time.ZonedDateTime.parse(
                                    value,
                                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
                                )
                                    .toInstant().toEpochMilli().minus(System.currentTimeMillis())
                                    .coerceIn(0, TrackingQueuePolicy.MAX_RETRY_AFTER)
                            }.getOrNull()
                    }
                    expected.failure = TrackingQueueHttpFailure(response.code, delay)
                } else if (expected != null) {
                    expected.failure = null
                }
                response
            },
        )
        .build()
}

class TrackingCredentialsChanged : java.net.ProtocolException("Tracking credentials changed during request")

class TrackingQueueException(val problem: TrackingQueueProblem) : IOException("Tracking request needs review: $problem")

class TrackingQueueHttpFailure(val code: Int, val retryAfter: Long?) : IOException("Tracking HTTP $code")
