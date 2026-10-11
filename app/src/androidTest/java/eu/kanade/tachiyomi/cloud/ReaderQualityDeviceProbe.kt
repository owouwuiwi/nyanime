package eu.kanade.tachiyomi.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import eu.kanade.tachiyomi.BuildConfig
import it.lorelore.nyanime.reader.quality.QualityRegion
import it.lorelore.nyanime.reader.quality.QualityTiles
import it.lorelore.nyanime.reader.quality.QualityWorkerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/** Original generated artwork only; no app books, sources, preferences or tracking are touched. */
object ReaderQualityDeviceProbe {
    suspend fun run(context: Context): String = coroutineScope {
        check(BuildConfig.ISOLATED_CLOUD_PROBE)
        val original = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        Canvas(original).apply {
            drawColor(Color.WHITE)
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                strokeWidth = 1f
            }
            for (line in 0..24) drawLine(0f, line * 9f + .2f, 240f, line * 9f + 9f, pen)
            pen.textSize = 14f
            drawText("Nyanime 0123", 60f, 110f, pen)
            pen.color = Color.RED
            drawRect(5f, 5f, 45f, 45f, pen)
            pen.color = Color.BLUE
            drawRect(195f, 5f, 235f, 45f, pen)
            pen.color = Color.GREEN
            drawRect(5f, 195f, 45f, 235f, pen)
        }
        var reference: Bitmap? = null
        val mosaic = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        val worker = QualityWorkerClient(context)
        try {
            val began = SystemClock.elapsedRealtime()
            reference = worker.process(original)
            val firstMillis = SystemClock.elapsedRealtime() - began
            check(reference.width == 480 && reference.height == 480)
            // Detect Y flips, swapped colour channels and transparent output.
            val red = reference.getPixel(40, 40)
            val blue = reference.getPixel(430, 40)
            val green = reference.getPixel(40, 430)
            check(Color.red(red) > 220 && Color.blue(red) < 30 && Color.alpha(red) == 255)
            check(Color.blue(blue) > 220 && Color.red(blue) < 30)
            check(Color.green(green) > 220 && Color.red(green) < 30)
            val tiles = QualityTiles.visible(240, 240, QualityRegion(0, 0, 240, 240), prefetch = 0)
            val canvas = Canvas(mosaic)
            val tileTimes = mutableListOf<Long>()
            for (tile in tiles) {
                val input = Bitmap.createBitmap(
                    original,
                    tile.input.left,
                    tile.input.top,
                    tile.input.width,
                    tile.input.height,
                )
                var output: Bitmap? = null
                try {
                    val start = SystemClock.elapsedRealtime()
                    output = worker.process(input)
                    tileTimes += SystemClock.elapsedRealtime() - start
                    val crop = tile.outputCrop(2)
                    canvas.drawBitmap(
                        output,
                        Rect(crop.left, crop.top, crop.right, crop.bottom),
                        Rect(
                            tile.content.left * 2,
                            tile.content.top * 2,
                            tile.content.right * 2,
                            tile.content.bottom * 2,
                        ),
                        null,
                    )
                } finally {
                    output?.recycle()
                    if (input !== original) input.recycle()
                }
            }
            var error = 0L
            var seamError = 0L
            var seamCount = 0L
            var maximum = 0
            for (y in 0 until 480) {
                for (x in 0 until 480) {
                    val a = reference.getPixel(x, y)
                    val b = mosaic.getPixel(x, y)
                    val difference = abs(Color.red(a) - Color.red(b)) +
                        abs(Color.green(a) - Color.green(b)) +
                        abs(Color.blue(a) - Color.blue(b))
                    error += difference
                    maximum = maxOf(maximum, difference)
                    if (x in 380..388 || y in 380..388) {
                        seamError += difference
                        seamCount++
                    }
                }
            }
            val mean = error.toDouble() / (480 * 480 * 3)
            val seams = seamError.toDouble() / (seamCount * 3)
            check(mean < 1.0 && seams < 1.0) { "Tile mismatch mean=$mean seam=$seams max=$maximum" }
            val cancelled = async(Dispatchers.Main) { worker.process(original).also { it.recycle() } }
            delay(1)
            cancelled.cancel()
            cancelled.join()
            // A cancellation must not poison the next request or leak a stale result into it.
            worker.process(original).recycle()
            JSONObject().apply {
                put("colorsAndOrientation", true)
                put("tiledVsWholeMeanError", mean)
                put("seamMeanError", seams)
                put("maximumChannelSumError", maximum)
                put("firstRequestMillis", firstMillis)
                put("tileMillis", org.json.JSONArray(tileTimes))
                put("cancelAndResume", true)
            }.toString()
        } finally {
            worker.close()
            reference?.recycle()
            mosaic.recycle()
            original.recycle()
        }
    }
}
