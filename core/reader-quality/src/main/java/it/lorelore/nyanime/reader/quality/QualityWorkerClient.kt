package it.lorelore.nyanime.reader.quality

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/** Constructed only when enhancement is on. Off and close release the lazy worker binding. */
class QualityWorkerClient(context: Context) : Closeable {
    private val context = context.applicationContext
    private val serial = Mutex()
    private val ids = AtomicInteger()
    private var pending: Pair<Int, CompletableDeferred<Bitmap>>? = null
    private var connection: ServiceConnection? = null
    private var binding: CompletableDeferred<Messenger>? = null

    @Volatile private var closed = false
    private val reply = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != QualityWorkerService.RESULT) return
            @Suppress("DEPRECATION")
            val bitmap = message.data.getParcelable<Bitmap>("tile")
            val request = pending?.takeIf { it.first == message.arg1 }?.second
            if (request == null || request.isCancelled) {
                bitmap?.recycle()
                return
            }
            if (bitmap != null && !message.data.getBoolean("failed")) {
                if (!request.complete(bitmap)) bitmap.recycle()
            } else {
                bitmap?.recycle()
                request.completeExceptionally(IllegalStateException("Reader image backend unavailable"))
            }
        }
    })

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun process(input: Bitmap, engine: QualityEngineId = QualityEngineId.ANIME4K): Bitmap = serial.withLock {
        check(!closed)
        withContext(Dispatchers.Main.immediate) {
            try {
                withTimeout(12_000) {
                    val channel = connect()
                    val id = ids.incrementAndGet()
                    val request = CompletableDeferred<Bitmap>()
                    pending = id to request
                    try {
                        channel.send(
                            Message.obtain(null, QualityWorkerService.PROCESS, id, 0).apply {
                                replyTo = reply
                                data = Bundle().apply {
                                    putParcelable("tile", input)
                                    putString("engine", engine.name)
                                }
                            },
                        )
                        request.await().also {
                            require(it.width == input.width * 2 && it.height == input.height * 2) {
                                it.recycle()
                                "Invalid reader tile dimensions"
                            }
                        }
                    } finally {
                        pending = null
                        // A completed, not-yet-consumed Bitmap must not survive cancellation.
                        if (request.isCompleted &&
                            !request.isCancelled &&
                            !kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!.isActive
                        ) {
                            runCatching { request.getCompleted().recycle() }
                        }
                        request.cancel()
                    }
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                withContext(NonCancellable) { disconnect() }
                throw IllegalStateException("Reader image worker timed out", error)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // A superseded viewport must not recompile the GPU programs for the next tile.
                // The result handler recycles late replies; close()/Off still unbind the worker.
                throw cancelled
            } catch (error: Exception) {
                withContext(NonCancellable) { disconnect() }
                throw error
            }
        }
    }

    private suspend fun connect(): Messenger {
        binding?.takeIf { !it.isCancelled }?.let { return it.await() }
        val ready = CompletableDeferred<Messenger>()
        binding = ready
        val current = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!closed && binding === ready) ready.complete(Messenger(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = failed()
            override fun onBindingDied(name: ComponentName) = failed()
            override fun onNullBinding(name: ComponentName) = failed()
            private fun failed() {
                if (connection !== this) return
                val error = IllegalStateException("Reader quality worker disconnected")
                ready.completeExceptionally(error)
                pending?.second?.completeExceptionally(error)
                disconnect()
            }
        }
        connection = current
        if (!context.bindService(
                Intent(context, QualityWorkerService::class.java),
                current,
                Context.BIND_AUTO_CREATE,
            )
        ) {
            connection = null
            binding = null
            ready.completeExceptionally(IllegalStateException("Cannot bind reader quality worker"))
        }
        return ready.await()
    }

    private fun disconnect() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        binding?.cancel()
        binding = null
        pending?.second?.cancel()
        pending = null
    }

    override fun close() {
        closed = true
        if (Looper.myLooper() ==
            Looper.getMainLooper()
        ) {
            disconnect()
        } else {
            Handler(Looper.getMainLooper()).post { disconnect() }
        }
    }
}
