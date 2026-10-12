package eu.kanade.tachiyomi.data.workinfo

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Public, unauthenticated catalogue transport, with per-host quota backoff and bounded responses. */
class WorkInfoHttp(client: OkHttpClient, private val json: Json) {
    private val client = client.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null)
        .callTimeout(18, TimeUnit.SECONDS).build()
    private val gates = HOSTS.associateWith { Mutex() }
    private val retryAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun get(url: HttpUrl): JsonElement {
        require(url.isHttps && url.host in HOSTS)
        return gates.getValue(url.host).withLock {
            val now = System.currentTimeMillis()
            if ((retryAt[url.host] ?: 0L) > now) throw IOException("Catalogue cooling down")
            client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "Nyanime/WorkInformation (https://github.com/owouwuiwi/nyanime)")
                    .header("Accept", "application/json").build(),
            ).await().use { response ->
                if (response.code == 429 || response.code in 500..599) {
                    val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 3600) ?: 60
                    retryAt[url.host] = now + seconds * 1000
                }
                if (!response.isSuccessful) throw WorkCatalogueHttpException(response.code)
                val body = response.body
                if (body.contentLength() > MAX_BYTES) throw IOException("Catalogue response too large")
                val source = body.source()
                source.request(MAX_BYTES + 1)
                val bytes = source.buffer.readByteArray()
                if (bytes.size > MAX_BYTES) throw IOException("Catalogue response too large")
                json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024L
        private val HOSTS = setOf("api.tvmaze.com", "www.wikidata.org", "query.wikidata.org")
    }
}

class WorkCatalogueHttpException(val status: Int) : IOException("Catalogue HTTP $status")
