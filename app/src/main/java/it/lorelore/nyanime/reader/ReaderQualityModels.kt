package it.lorelore.nyanime.reader

import android.content.Context
import it.lorelore.nyanime.reader.quality.QualityEngineId
import it.lorelore.nyanime.reader.quality.QualityModelDownloader
import it.lorelore.nyanime.reader.quality.QualityModelStore
import it.lorelore.nyanime.reader.quality.QualityModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Created lazily by the quality panel. No downloads, worker or native load on ordinary reading. */
internal class ReaderQualityModels private constructor(context: Context) {
    data class State(
        val ready: Boolean = false,
        val running: Boolean = false,
        val bytes: Long = 0,
        val failed: Boolean = false,
    )
    private val store = QualityModelStore(File(context.applicationContext.noBackupFilesDir, "reader-quality-models"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<QualityEngineId, Job>()
    private val mutable = MutableStateFlow<Map<QualityEngineId, State>>(emptyMap())
    val states = mutable.asStateFlow()
    private val transport by lazy { ReaderModelTransport() }

    fun refresh() {
        for (model in listOf(QualityModels.cugan, QualityModels.esrgan)) {
            if (jobs[model.engine]?.isActive == true) continue
            jobs[model.engine] = scope.launch {
                val ready = withContext(Dispatchers.IO) { runCatching { store.available(model) }.getOrDefault(false) }
                mutable.update { it + (model.engine to State(ready = ready)) }
            }
        }
    }

    fun download(engine: QualityEngineId) {
        val model = QualityModels.get(engine) ?: return
        if (mutable.value[engine]?.running == true) return
        jobs[engine]?.cancel()
        mutable.update { it + (engine to State(running = true)) }
        jobs[engine] = scope.launch {
            try {
                store.install(model, transport) { done, _ ->
                    mutable.update { it + (engine to State(running = true, bytes = done)) }
                }
                mutable.update { it + (engine to State(ready = true)) }
            } catch (cancelled: CancellationException) {
                mutable.update { it + (engine to State()) }
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it + (engine to State(failed = true)) }
            }
        }
    }

    fun cancel(engine: QualityEngineId) {
        jobs[engine]?.cancel()
    }
    fun remove(engine: QualityEngineId) {
        val model = QualityModels.get(engine) ?: return
        if (jobs[engine]?.isActive == true) return
        jobs[engine] = scope.launch {
            try {
                store.remove(model)
                mutable.update { it + (engine to State()) }
            } catch (_: Exception) {
                mutable.update { it + (engine to State(failed = true)) }
            }
        }
    }

    companion object {
        @Volatile private var instance: ReaderQualityModels? = null
        fun get(context: Context): ReaderQualityModels = instance ?: synchronized(this) {
            instance ?: ReaderQualityModels(context).also { instance = it }
        }
    }
}

/** Dedicated, cookie-free transport for the fixed upstream model assets. */
private class ReaderModelTransport : QualityModelDownloader {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()
    override suspend fun download(
        url: String,
        destination: File,
        maxBytes: Long,
        progress: (Long) -> Unit,
    ) = coroutineScope {
        val call = client.newCall(Request.Builder().url(url).build())
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            val response = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
            response.use {
                check(it.isSuccessful && it.request.url.isHttps) { "Model download unavailable" }
                val body = requireNotNull(it.body)
                check(body.contentLength() <= maxBytes)
                withContext(Dispatchers.IO) {
                    body.byteStream().use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(16384)
                            var done = 0L
                            var lastReport = 0L
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                done += count
                                check(done <= maxBytes) { "Model exceeds declared size" }
                                output.write(buffer, 0, count)
                                if (done - lastReport >= 65536 || done == maxBytes) {
                                    progress(done)
                                    lastReport = done
                                }
                            }
                            output.flush()
                            output.fd.sync()
                        }
                    }
                }
            }
        } finally {
            cancellation.cancel()
            call.cancel()
        }
    }
}
