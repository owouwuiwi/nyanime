package nyanime.panels

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PanelInferenceClient(context: Context) : Closeable {
    private val context = context.applicationContext
    private val serial = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<List<PanelRect>>>()
    private val ids = AtomicInteger()
    private var connection: ServiceConnection? = null
    private var binding: CompletableDeferred<Messenger>? = null

    @Volatile private var closed = false
    private val reply = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != PanelInferenceService.RESULT) return
            val request = pending.remove(message.arg1) ?: return
            if (message.data.getBoolean("failed")) {
                request.completeExceptionally(IllegalStateException("Panel inference unavailable"))
            } else {
                val values = message.data.getFloatArray("boxes") ?: floatArrayOf()
                if (values.size % 4 != 0 ||
                    values.size > 1024
                ) {
                    request.completeExceptionally(IllegalStateException("Invalid panel result"))
                } else {
                    request.complete(
                        values.toList().chunked(4).map {
                            PanelRect(it[0], it[1], it[2], it[3])
                        }.filter { it.valid() },
                    )
                }
            }
        }
    })

    suspend fun analyze(bitmap: Bitmap): List<PanelRect> = serial.withLock {
        check(!closed)
        try {
            withTimeout(6000) {
                val channel = connect()
                val pixels = withContext(Dispatchers.Default) { pixels(bitmap) }
                val id = ids.incrementAndGet()
                val request = CompletableDeferred<List<PanelRect>>()
                pending[id] = request
                try {
                    channel.send(
                        Message.obtain(null, PanelInferenceService.ANALYZE, id, 0).apply {
                            replyTo = reply
                            data = Bundle().apply { putIntArray("pixels", pixels) }
                        },
                    )
                    val results = request.await()
                    val scale = minOf(
                        PANEL_INPUT_SIZE.toFloat() / bitmap.width,
                        PANEL_INPUT_SIZE.toFloat() / bitmap.height,
                    )
                    val width = bitmap.width * scale / PANEL_INPUT_SIZE
                    val height = bitmap.height * scale / PANEL_INPUT_SIZE
                    val x = (1 - width) / 2
                    val y = (1 - height) / 2
                    results.mapNotNull {
                        val mapped = PanelRect(
                            ((it.left - x) / width).coerceIn(0f, 1f),
                            ((it.top - y) / height).coerceIn(0f, 1f),
                            ((it.right - x) / width).coerceIn(0f, 1f),
                            ((it.bottom - y) / height).coerceIn(0f, 1f),
                        )
                        mapped.takeIf { value -> value.valid() }
                    }
                } finally {
                    pending.remove(id)
                    request.cancel()
                }
            }
        } catch (error: Exception) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) { disconnect() }
            throw error
        }
    }

    private fun pixels(bitmap: Bitmap): IntArray {
        val small = Bitmap.createBitmap(PANEL_INPUT_SIZE, PANEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        try {
            val matrix = Matrix().apply {
                setRectToRect(
                    RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                    RectF(0f, 0f, PANEL_INPUT_SIZE.toFloat(), PANEL_INPUT_SIZE.toFloat()),
                    Matrix.ScaleToFit.CENTER,
                )
            }
            Canvas(small).apply {
                drawColor(android.graphics.Color.BLACK)
                drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            return IntArray(PANEL_INPUT_SIZE * PANEL_INPUT_SIZE).also {
                small.getPixels(it, 0, PANEL_INPUT_SIZE, 0, 0, PANEL_INPUT_SIZE, PANEL_INPUT_SIZE)
            }
        } finally {
            small.recycle()
        }
    }

    private suspend fun connect(): Messenger = withContext(Dispatchers.Main.immediate) {
        binding?.takeIf { !it.isCancelled }?.let { return@withContext it.await() }
        val ready = CompletableDeferred<Messenger>()
        binding = ready
        val current = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (closed || binding !== ready) return
                ready.complete(Messenger(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = failed()
            override fun onBindingDied(name: ComponentName) = failed()
            override fun onNullBinding(name: ComponentName) = failed()
            private fun failed() {
                if (connection !== this || binding !== ready) return
                val error = IllegalStateException("Panel worker disconnected")
                ready.completeExceptionally(error)
                pending.values.forEach { it.completeExceptionally(error) }
                if (connection === this) {
                    runCatching { context.unbindService(this) }
                    connection = null
                }
                binding = null
            }
        }
        connection = current
        if (!context.bindService(
                Intent(context, PanelInferenceService::class.java),
                current,
                Context.BIND_AUTO_CREATE,
            )
        ) {
            connection = null
            binding = null
            ready.completeExceptionally(IllegalStateException("Cannot bind panel worker"))
        }
        ready.await()
    }

    private fun disconnect() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        binding?.cancel()
        binding = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    override fun close() {
        closed = true
        if (Looper.myLooper() == Looper.getMainLooper()) {
            disconnect()
        } else {
            Handler(Looper.getMainLooper()).post { disconnect() }
        }
    }
}
