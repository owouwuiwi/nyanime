package it.lorelore.nyanime.reader.quality

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Private image worker. Only tiles cross IPC; there are no source URLs or credentials. */
class QualityWorkerService : Service() {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "reader-quality") }
    private val stopping = AtomicBoolean(false)
    private var backend: QualityBackend? = null
    private var backendId: QualityEngineId? = null
    private val latest = QualityRequestGate<IBinder>()
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != PROCESS || stopping.get()) return
            val reply = message.replyTo ?: return

            @Suppress("DEPRECATION")
            val input = message.data.getParcelable<Bitmap>("tile") ?: return
            val id = message.arg1
            val selected = message.data.getString("engine")
            if (input.width !in 1..256 || input.height !in 1..256) {
                input.recycle()
                return
            }
            latest.submit(reply.binder, id)
            executor.execute {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                if (stopping.get() || !latest.isCurrent(reply.binder, id)) {
                    input.recycle()
                    latest.retire(reply.binder, id)
                    return@execute
                }
                val result = Bundle()
                var output: Bitmap? = null
                try {
                    val engineId = requireNotNull(QualityEngineId.entries.firstOrNull { it.name == selected })
                    if (backendId != engineId) closeBackend()
                    val worker = backend ?: createQualityBackend(applicationContext, engineId).also {
                        backend = it
                        backendId = engineId
                    }
                    output = worker.process(input)
                    result.putParcelable("tile", output)
                } catch (error: Exception) {
                    android.util.Log.w("ReaderQuality", "Image worker unavailable", error)
                    result.putBoolean("failed", true)
                    closeBackend()
                } catch (_: LinkageError) {
                    result.putBoolean("failed", true)
                    closeBackend()
                } catch (_: OutOfMemoryError) {
                    result.putBoolean("failed", true)
                    closeBackend()
                } finally {
                    input.recycle()
                }
                try {
                    if (!stopping.get() && latest.isCurrent(reply.binder, id)) {
                        reply.send(Message.obtain(null, RESULT, id, 0).apply { data = result })
                    }
                } catch (_: RemoteException) {
                    // The requesting page or reader has gone away.
                } finally {
                    output?.recycle()
                    latest.retire(reply.binder, id)
                }
            }
        }
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder
    private fun closeBackend() {
        runCatching { backend?.close() }
        backend = null
        backendId = null
    }
    override fun onDestroy() {
        stopping.set(true)
        executor.execute { closeBackend() }
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        internal const val PROCESS = 1
        internal const val RESULT = 2
    }
}
