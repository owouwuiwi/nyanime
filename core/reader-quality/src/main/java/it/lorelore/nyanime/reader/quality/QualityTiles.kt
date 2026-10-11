package it.lorelore.nyanime.reader.quality

import kotlin.math.max
import kotlin.math.min

/** Coordinates always refer to the original decoded image, never to an output bitmap. */
data class QualityRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width.toLong() * height
    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top)
    }
}

data class QualityTile(val content: QualityRegion, val input: QualityRegion) {
    fun sampledCrop(sample: Int, scale: Int, bitmapWidth: Int, bitmapHeight: Int): QualityRegion {
        require(sample > 0 && scale > 0 && bitmapWidth > 0 && bitmapHeight > 0)
        val factor = scale.toDouble() / sample
        val left = ((content.left - input.left) * factor).toInt().coerceIn(0, bitmapWidth - 1)
        val top = ((content.top - input.top) * factor).toInt().coerceIn(0, bitmapHeight - 1)
        val right = kotlin.math.ceil((content.right - input.left) * factor).toInt()
            .coerceIn(left + 1, bitmapWidth)
        val bottom = kotlin.math.ceil((content.bottom - input.top) * factor).toInt()
            .coerceIn(top + 1, bitmapHeight)
        return QualityRegion(left, top, right, bottom)
    }

    fun outputCrop(scale: Int) = QualityRegion(
        (content.left - input.left) * scale,
        (content.top - input.top) * scale,
        (content.right - input.left) * scale,
        (content.bottom - input.top) * scale,
    )
}

/** Visible tiles first; at most one ring of bounded prefetch, no whole-strip allocation. */
object QualityTiles {
    const val EDGE = 192
    const val HALO = 24
    const val MAX_VISIBLE = 128
    const val PIPELINE_VERSION = 3

    fun visible(
        width: Int,
        height: Int,
        viewport: QualityRegion,
        sample: Int = 1,
        prefetch: Int = 2,
        engine: QualityEngineId = QualityEngineId.ANIME4K,
    ): List<QualityTile> {
        require(width > 0 && height > 0 && sample in listOf(1, 2, 4, 8, 16, 32))
        require(viewport.right <= width && viewport.bottom <= height)
        require(prefetch in 0..4)
        val policy = engine.tilePolicy
        val edge = policy.edge * sample
        val halo = policy.halo * sample
        val columns = (width.toLong() + edge - 1) / edge
        val rows = (height.toLong() + edge - 1) / edge
        val left = viewport.left / edge
        val right = (viewport.right - 1) / edge
        val top = viewport.top / edge
        val bottom = (viewport.bottom - 1) / edge
        require((right - left + 3).toLong() * (bottom - top + 3) <= 4096) {
            "Viewport exceeds the bounded tile window"
        }
        val centerX = (viewport.left.toLong() + viewport.right) / 2.0
        val centerY = (viewport.top.toLong() + viewport.bottom) / 2.0
        val candidates = buildList {
            for (y in max(0, top - 1)..min(rows.toInt() - 1, bottom + 1)) {
                for (x in max(0, left - 1)..min(columns.toInt() - 1, right + 1)) {
                    val content = QualityRegion(
                        x * edge,
                        y * edge,
                        min(width.toLong(), (x + 1L) * edge).toInt(),
                        min(
                            height.toLong(),
                            (y + 1L) * edge,
                        ).toInt(),
                    )
                    val visible = x in left..right && y in top..bottom
                    val distance = kotlin.math.abs((content.left.toLong() + content.right) / 2.0 - centerX) +
                        kotlin.math.abs((content.top.toLong() + content.bottom) / 2.0 - centerY)
                    add(Triple(content, visible, distance))
                }
            }
        }
        val visible = candidates.filter { it.second }.sortedBy { it.third }.take(MAX_VISIBLE)
        val extras = candidates.filterNot { it.second }.sortedBy { it.third }.take(prefetch)
        return (visible + extras).map { (content) ->
            QualityTile(
                content,
                QualityRegion(
                    max(0, content.left - halo),
                    max(0, content.top - halo),
                    min(width.toLong(), content.right.toLong() + halo).toInt(),
                    min(height.toLong(), content.bottom.toLong() + halo).toInt(),
                ),
            )
        }
    }

    /** Keep near-screen sampling and the number of visible jobs bounded on very tall pages. */
    fun sample(viewScale: Float, width: Int, height: Int): Int {
        require(viewScale.isFinite() && viewScale > 0f && width > 0 && height > 0)
        var result = 1
        while (result < 32 && result * viewScale < .5f) result *= 2
        return result
    }
}

data class QualityTileKey(
    val imageSha256: String,
    val region: QualityRegion,
    val sample: Int,
    val engine: QualityEngineId,
    val modelSha256: String,
    val pipelineVersion: Int = QualityTiles.PIPELINE_VERSION,
    val outputScale: Int = 2,
)

enum class QualityEngineId { OFF, ANIME4K, REAL_CUGAN, REAL_ESRGAN }
