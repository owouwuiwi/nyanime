package it.lorelore.nyanime.reader.quality

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.Keep
import java.io.File

/** Constructed on the dedicated worker thread only, after model integrity has been checked. */
internal class NcnnTileEngine(context: Context, engine: QualityEngineId) : QualityBackend {
    private var handle: Long
    init {
        val model = requireNotNull(QualityModels.get(engine))
        val store = QualityModelStore(File(context.noBackupFilesDir, "reader-quality-models"))
        check(store.available(model)) { "Model unavailable or damaged" }
        val directory = store.directory(model)
        handle = NcnnNative.create(
            File(directory, "model.param").absolutePath,
            File(directory, "model.bin").absolutePath,
            engine == QualityEngineId.REAL_CUGAN,
        )
        check(handle != 0L)
    }
    override fun process(input: Bitmap): Bitmap {
        check(handle != 0L)
        check(input.colorSpace?.isSrgb != false) { "Original retained for wide-gamut input" }
        val output = Bitmap.createBitmap(input.width * 2, input.height * 2, Bitmap.Config.ARGB_8888)
        try {
            NcnnNative.process(handle, input, output)
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }
    override fun close() {
        if (handle != 0L) NcnnNative.destroy(handle)
        handle = 0L
    }
}

@Keep
internal object NcnnNative {
    init {
        System.loadLibrary("nyanime_reader_quality")
    }
    external fun create(param: String, weights: String, cugan: Boolean): Long
    external fun process(handle: Long, input: Bitmap, output: Bitmap)
    external fun destroy(handle: Long)
}
