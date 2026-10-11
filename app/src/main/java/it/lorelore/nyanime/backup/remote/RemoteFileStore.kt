package it.lorelore.nyanime.backup.remote

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RemoteObject(val id: String, val name: String, val bytes: Long)

internal interface RemoteFileStore {
    suspend fun prepare()
    suspend fun list(): List<RemoteObject>
    suspend fun upload(name: String, file: File): RemoteObject
    suspend fun download(id: String, file: File, limit: Long = MAX_REMOTE_BYTES)
    suspend fun delete(id: String)
}

internal class RemoteStorageException(val reason: String, val status: Int = 0) : IOException(reason)

/** Dedicated client: no application interceptors, cookies, URL logging or credential redirects. */
internal fun remoteHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
    .writeTimeout(45, TimeUnit.SECONDS).callTimeout(8, TimeUnit.MINUTES).build()

internal suspend fun OkHttpClient.remoteRequest(request: Request): Response =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(RemoteStorageException("network"))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

internal fun Response.requireSuccess() {
    if (!isSuccessful) {
        throw RemoteStorageException(
            when (code) {
                401, 403 -> "authorization"
                404 -> "missing"
                507 -> "space"
                429 -> "rate"
                else -> "server"
            },
            code,
        )
    }
}

internal suspend fun Response.readRemoteBytes(limit: Int): ByteArray = remoteBody {
    require(body.contentLength() <= limit)
    body.byteStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val size = input.readRemote(buffer, minOf(buffer.size, limit + 1 - output.size()))
            if (size < 0) break
            require(output.size() + size <= limit)
            output.write(buffer, 0, size)
        }
        output.toByteArray()
    }
}

/** Closing/cancelling the owning job also interrupts a blocked response stream. */
internal suspend fun Response.saveRemote(file: File, limit: Long) = remoteBody {
    requireSuccess()
    require(body.contentLength() <= limit)
    body.byteStream().use { input ->
        file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.readRemote(buffer, buffer.size)
                if (count < 0) break
                bytes += count
                require(bytes <= limit)
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
    }
}

/** Classify transport read failures separately from local file write failures. */
private suspend fun InputStream.readRemote(buffer: ByteArray, count: Int): Int = try {
    read(buffer, 0, count)
} catch (_: IOException) {
    currentCoroutineContext().ensureActive()
    throw RemoteStorageException("network")
}

/** A cancel after response headers still closes the socket, including slow metadata responses. */
private suspend fun <T> Response.remoteBody(read: suspend Response.() -> T): T = withContext(Dispatchers.IO) {
    coroutineScope {
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                close()
            }
        }
        try {
            read()
        } catch (failure: Exception) {
            // Closing a blocked socket may throw IOException; preserve the caller's cancellation.
            currentCoroutineContext().ensureActive()
            throw failure
        } finally {
            cancellation.cancel()
        }
    }
}
