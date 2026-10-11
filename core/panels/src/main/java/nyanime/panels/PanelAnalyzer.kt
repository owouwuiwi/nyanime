package nyanime.panels

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import kotlin.coroutines.coroutineContext

/** Original image never crosses Binder; only a bounded 224 px input is sent to the worker. */
class PanelAnalyzer(context: Context) : Closeable {
    private val client = PanelInferenceClient(context)
    private val cache = File(context.cacheDir, "guided-geometries").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyze(file: File, hash: String, direction: PanelDirection): PanelAnalysis = withContext(
        Dispatchers.IO,
    ) {
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, dimensions)
        require(dimensions.outWidth > 0 && dimensions.outHeight > 0)
        val entry = File(cache, "$PANEL_ANALYZER_VERSION-$hash.json")
        val cached = runCatching {
            json.decodeFromString<List<PanelRect>>(entry.readText()).takeIf {
                it.size <= 256 && it.all(PanelRect::valid)
            }
        }.getOrNull()
        val boxes = cached ?: withTimeout(30_000) {
            val decoder = BitmapRegionDecoder.newInstance(file.path, false)
            try {
                val tiles = PanelGeometry.tiles(decoder.width, decoder.height)
                val results = mutableListOf<Pair<PanelTile, List<PanelRect>>>()
                for (tile in tiles) {
                    coroutineContext.ensureActive()
                    var sample = 1
                    while (maxOf(tile.width, tile.height) / sample > 1024) sample *= 2
                    val bitmap = decoder.decodeRegion(
                        Rect(tile.x, tile.y, tile.x + tile.width, tile.y + tile.height),
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig =
                                android.graphics.Bitmap.Config.ARGB_8888
                        },
                    ) ?: error("Cannot decode panel tile")
                    try {
                        results += tile to client.analyze(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                PanelGeometry.stitch(results, decoder.width, decoder.height).ifEmpty { listOf(PanelRect.PAGE) }
            } finally {
                decoder.recycle()
            }
        }.also { detected ->
            runCatching {
                entry.writeText(json.encodeToString(detected))
                cache.listFiles()?.sortedByDescending { it.lastModified() }?.drop(128)?.forEach { it.delete() }
            }
        }
        PanelAnalysis(hash, PanelGeometry.order(boxes, direction), dimensions.outWidth, dimensions.outHeight)
    }

    override fun close() = client.close()
}
