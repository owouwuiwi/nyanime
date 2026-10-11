package it.lorelore.nyanime.studio

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class StudioNetwork(
    client: OkHttpClient,
    private val secret: (StudioSource, String) -> String? = { _, _ -> null },
) : StudioHttp {
    private val budget = AtomicInteger(16)
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).build()

    override suspend fun request(
        source: StudioSource,
        node: StudioNode,
        url: String,
        variables: JsonObject,
    ): StudioHttpResponse = requests.withPermit {
        val allowed = (source.origins + source.baseUrl).map { origin(it.toHttpUrl()) }.toSet()
        val configured = StudioDocuments.json.parseToJsonElement(
            node.parameters["headers"].orEmpty().ifBlank {
                "{}"
            },
        ).jsonObject
        var method = node.parameters["method"].orEmpty().ifBlank { "GET" }.uppercase()
        require(method in setOf("GET", "POST")) { "Unsupported request method" }
        var target = url.toHttpUrl()
        repeat(6) {
            require(budget.decrementAndGet() >= 0) { "Network request budget exhausted" }
            require(target.username.isEmpty() && target.password.isEmpty() && origin(target) in allowed) {
                "Authorize this origin in the source settings"
            }
            val builder = Request.Builder().url(target)
            configured.forEach { (name, value) ->
                require(!name.equals("Host", true) && !name.equals("Content-Length", true)) { "Reserved HTTP header" }
                val text = Regex("\\{\\{secret\\.([A-Za-z0-9_-]+)\\}\\}").replace(value.studioText()) { match ->
                    secret(source, match.groupValues[1]) ?: error("Configure the local secret ${match.groupValues[1]}")
                }
                try {
                    builder.header(
                        name,
                        StudioTemplates.expand(
                            text,
                            variables + ("baseUrl" to JsonPrimitive(source.baseUrl)),
                        ),
                    )
                } catch (_: IllegalArgumentException) {
                    // OkHttp's default exception includes the rejected value, which may be a secret.
                    throw IllegalArgumentException("Invalid HTTP header")
                }
            }
            if (method == "POST") {
                builder.post(
                    StudioTemplates.expand(node.parameters["body"].orEmpty(), variables).toRequestBody(
                        (node.parameters["contentType"] ?: "application/json").toMediaType(),
                    ),
                )
            }
            // Keep the cancellable continuation alive while reading the body, not only
            // until headers arrive. Cancelling a flow must close a stalled download too.
            val loaded = client.newCall(builder.build()).awaitStudio { it ->
                if (it.code in setOf(301, 302, 303, 307, 308)) {
                    val next = target.resolve(it.header("Location").orEmpty()) ?: error("Invalid redirect")
                    require(next.scheme == target.scheme || next.isHttps) { "Insecure redirect" }
                    // Revalidate before a second request. Never forward secrets to another origin.
                    require(origin(next) == origin(target) || configured.isEmpty()) {
                        "Cross-origin redirect requires an explicit request block"
                    }
                    if (it.code == 303 || (it.code in setOf(301, 302) && method == "POST")) method = "GET"
                    target = next
                    null
                } else {
                    check(it.isSuccessful) { "HTTP ${it.code}" }
                    val body = it.body ?: error("Empty HTTP response")
                    require(body.contentLength() <= 2_097_152) { "HTTP response too large" }
                    val bytes = body.byteStream().use { stream ->
                        val buffer = ByteArray(8192)
                        val collected = ByteArrayOutputStream()
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(collected.size() + count <= 2_097_152) { "HTTP response too large" }
                            collected.write(buffer, 0, count)
                        }
                        collected.toByteArray()
                    }
                    require(bytes.size <= 2_097_152) { "HTTP response too large" }
                    StudioHttpResponse(
                        bytes.toString(body.contentType()?.charset() ?: Charsets.UTF_8),
                        target.toString(),
                        it.code,
                    )
                }
            }
            if (loaded != null) return@withPermit loaded
        }
        error("Too many redirects")
    }

    companion object {
        private val requests = Semaphore(5)
        private fun origin(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"
    }
}

private suspend fun <T> Call.awaitStudio(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(
            call: Call,
            e: IOException,
        ) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use(read)
                if (continuation.isActive) continuation.resume(value)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    })
}
