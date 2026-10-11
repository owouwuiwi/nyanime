package nyanime.panels

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The only process allowed to load LiteRT. It receives a small pixel array, never content URLs. */
class PanelInferenceService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val stopping = AtomicBoolean(false)
    private var interpreter: Interpreter? = null
    private val input = ByteBuffer.allocateDirect(
        PANEL_INPUT_SIZE * PANEL_INPUT_SIZE * 12,
    ).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(
        PANEL_INPUT_SIZE * PANEL_INPUT_SIZE * 12,
    ).order(ByteOrder.nativeOrder())
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != ANALYZE || stopping.get()) return
            val pixels = message.data.getIntArray("pixels") ?: return
            val id = message.arg1
            val reply = message.replyTo ?: return
            if (pixels.size != PANEL_INPUT_SIZE * PANEL_INPUT_SIZE) return
            executor.execute {
                if (stopping.get()) return@execute
                val result = Bundle()
                try {
                    val engine = interpreter ?: load().also { interpreter = it }
                    input.clear()
                    for (pixel in pixels) {
                        input.putFloat((pixel shr 16 and 255) / 255f)
                        input.putFloat((pixel shr 8 and 255) / 255f)
                        input.putFloat((pixel and 255) / 255f)
                    }
                    input.rewind()
                    output.clear()
                    engine.run(input, output)
                    output.rewind()
                    val scores = FloatArray(PANEL_INPUT_SIZE * PANEL_INPUT_SIZE * 3)
                    output.asFloatBuffer().get(scores)
                    result.putFloatArray(
                        "boxes",
                        PanelSegmentation.extract(scores).flatMap {
                            listOf(it.left, it.top, it.right, it.bottom)
                        }.toFloatArray(),
                    )
                } catch (_: Exception) {
                    result.putBoolean("failed", true)
                } catch (_: LinkageError) {
                    result.putBoolean("failed", true)
                }
                if (!stopping.get()) {
                    try {
                        reply.send(Message.obtain(null, RESULT, id, 0).apply { data = result })
                    } catch (
                        _: RemoteException,
                    ) { /* Reader has already left. */ }
                }
            }
        }
    })

    private fun load(): Interpreter {
        val descriptor = assets.openFd("deeppanel/model.tflite")
        val buffer = descriptor.use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        return Interpreter(buffer, Interpreter.Options().setNumThreads(2))
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        stopping.set(true)
        // Never close an interpreter concurrently with run().
        executor.execute {
            interpreter?.close()
            interpreter = null
        }
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        internal const val ANALYZE = 1
        internal const val RESULT = 2
    }
}
