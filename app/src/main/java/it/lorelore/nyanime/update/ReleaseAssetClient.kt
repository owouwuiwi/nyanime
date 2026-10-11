package it.lorelore.nyanime.update

import eu.kanade.tachiyomi.data.updater.UpdateHttpException
import eu.kanade.tachiyomi.data.updater.awaitUpdateResponse
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Only used after the declaration has pinned an immutable GitHub asset URL. No account cookies. */
internal class ReleaseAssetClient(client: OkHttpClient) {
    private val transport = client.newBuilder().cache(null).cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES).build()

    suspend fun text(url: String): String = response(url, declaration = true).use { response ->
        val output = ByteArrayOutputStream()
        response.body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                require(output.size() + count <= ReleaseGraph.MAX_BYTES)
                output.write(buffer, 0, count)
            }
        }
        output.toString(Charsets.UTF_8.name())
    }

    suspend fun download(url: String, file: File, size: Long, progress: (Int) -> Unit) {
        try {
            response(url).use { response ->
                val length = response.body.contentLength()
                require(length == -1L || length == size) { "Unexpected update length" }
                response.body.byteStream().use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            require(total <= size) { "Update exceeds declared size" }
                            output.write(buffer, 0, count)
                            progress((total * 100 / size).toInt())
                        }
                        require(total == size) { "Incomplete update download" }
                        output.fd.sync()
                    }
                }
            }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private suspend fun response(url: String, declaration: Boolean = false): Response {
        val client = if (declaration) transport.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() else transport
        var next = url
        repeat(5) {
            currentCoroutineContext().ensureActive()
            val response = client.newCall(GET(next)).awaitUpdateResponse()
            if (response.code !in setOf(301, 302, 303, 307, 308)) {
                if (!response.isSuccessful) {
                    val status = response.code
                    response.close()
                    throw UpdateHttpException(status)
                }
                return response
            }
            val redirect = response.use { it.header("Location") } ?: throw IOException("Missing update redirect")
            val resolved = URI(next).resolve(redirect)
            require(resolved.scheme == "https" && resolved.rawUserInfo == null && resolved.port == -1)
            require(resolved.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            next = resolved.toASCIIString()
        }
        throw IOException("Too many update redirects")
    }
}
