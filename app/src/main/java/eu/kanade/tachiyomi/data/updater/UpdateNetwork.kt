package eu.kanade.tachiyomi.data.updater

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** Keep cancellation connected to the socket after headers, until the body is closed. */
internal suspend fun Call.awaitUpdateResponse(): Response {
    val call = this
    val downloadContext = currentCoroutineContext()
    val cancellation = CoroutineScope(downloadContext).launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        val response = await()
        val original = response.body
        val body = object : ResponseBody() {
            private val forwarding = object : ForwardingSource(original.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = try {
                    downloadContext.ensureActive()
                    super.read(sink, byteCount)
                } catch (error: IOException) {
                    downloadContext.ensureActive()
                    throw error
                }
                override fun close() {
                    try {
                        super.close()
                    } catch (error: IOException) {
                        downloadContext.ensureActive()
                        throw error
                    } finally {
                        cancellation.cancel()
                    }
                }
            }.buffer()
            override fun contentType() = original.contentType()
            override fun contentLength() = original.contentLength()
            override fun source() = forwarding
        }
        return response.newBuilder().body(body).build()
    } catch (error: Throwable) {
        cancellation.cancel()
        throw error
    }
}
