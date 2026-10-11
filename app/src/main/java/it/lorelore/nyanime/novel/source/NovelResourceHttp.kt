package it.lorelore.nyanime.novel.source

import it.lorelore.nyanime.source.novel.NovelResourceRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Source-declared images only. Sessions are explicit; unrelated host cookies are never attached.
 */
internal class NovelResourceHttp(base: OkHttpClient) {
    private val client =
        base
            .newBuilder()
            .apply {
                // Preserve transport/DNS/TLS settings, never an unrelated session interceptor or
                // header log.
                interceptors().clear()
                networkInterceptors().clear()
            }
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

    suspend fun read(request: NovelResourceRequest, maxBytes: Int = 8 * 1024 * 1024): ByteArray =
        withTimeout(30_000) {
            require(maxBytes in 1..8 * 1024 * 1024)
            var url = checkedUrl(request.url)
            var headers = request.headers.filterKeys { it.lowercase() !in TRANSPORT_HEADERS }
            repeat(6) {
                val response = step(url, headers, maxBytes)
                if (response.code in 200..299) return@withTimeout response.bytes
                if (response.code !in REDIRECTS) {
                    throw IOException("Chapter image request failed (${response.code})")
                }
                val next =
                    response.location?.let(url::resolve)
                        ?: throw IOException("Invalid image redirect")
                checkedUrl(next.toString())
                require(!url.isHttps || next.isHttps) { "Image redirect cannot downgrade HTTPS" }
                if (url.scheme != next.scheme || url.host != next.host || url.port != next.port) {
                    headers = emptyMap()
                }
                url = next
            }
            throw IOException("Too many image redirects")
        }

    private fun checkedUrl(value: String): HttpUrl {
        val url =
            requireNotNull(value.toHttpUrlOrNull()) { "Image resource must use HTTP or HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Image credentials belong to source session headers"
        }
        return url
    }

    private data class Step(val code: Int, val location: String?, val bytes: ByteArray)

    private suspend fun step(url: HttpUrl, headers: Map<String, String>, maxBytes: Int): Step =
        suspendCancellableCoroutine { continuation ->
            val request =
                Request.Builder()
                    .url(url)
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                val bytes =
                                    if (it.isSuccessful) {
                                        val body = it.body
                                        if (body.contentLength() > maxBytes) {
                                            throw IOException(
                                                "Chapter image exceeds its byte budget",
                                            )
                                        }
                                        body.byteStream().use { input ->
                                            ByteArrayOutputStream(minOf(maxBytes, 16 * 1024)).use { output ->
                                                val buffer = ByteArray(16 * 1024)
                                                while (true) {
                                                    if (!continuation.isActive) {
                                                        throw IOException("Image request cancelled")
                                                    }
                                                    val count = input.read(buffer)
                                                    if (count < 0) break
                                                    if (output.size() + count > maxBytes) {
                                                        throw IOException(
                                                            "Chapter image exceeds its byte budget",
                                                        )
                                                    }
                                                    output.write(buffer, 0, count)
                                                }
                                                output.toByteArray()
                                            }
                                        }
                                    } else {
                                        byteArrayOf()
                                    }
                                Step(it.code, it.header("Location"), bytes)
                            }
                            continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                },
            )
        }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val TRANSPORT_HEADERS =
            setOf(
                "host",
                "connection",
                "content-length",
                "transfer-encoding",
                "keep-alive",
                "upgrade",
                "accept-encoding",
                "proxy-authorization",
            )
    }
}
