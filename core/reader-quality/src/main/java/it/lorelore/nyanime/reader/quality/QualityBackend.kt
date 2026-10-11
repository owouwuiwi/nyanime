package it.lorelore.nyanime.reader.quality

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable

internal interface QualityBackend : Closeable {
    fun process(input: Bitmap): Bitmap
}

/** Native loading and model checks stay behind construction, never ordinary reader startup. */
internal fun createQualityBackend(context: Context, engine: QualityEngineId): QualityBackend = when (engine) {
    QualityEngineId.ANIME4K -> Anime4kTileEngine(context.assets)
    QualityEngineId.REAL_CUGAN, QualityEngineId.REAL_ESRGAN -> NcnnTileEngine(context, engine)
    QualityEngineId.OFF -> error("No backend for original images")
}

data class QualityTilePolicy(val edge: Int, val halo: Int) {
    init {
        require(edge > 0 && halo >= 0 && edge.toLong() + 2L * halo <= 256)
    }
}

val QualityEngineId.tilePolicy: QualityTilePolicy
    get() = if (this == QualityEngineId.REAL_CUGAN) QualityTilePolicy(128, 64) else QualityTilePolicy(192, 24)
