package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.preference.PreferenceManager
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import it.lorelore.nyanime.reader.quality.QualityEngineId
import it.lorelore.nyanime.reader.quality.QualityImageInput
import it.lorelore.nyanime.reader.quality.QualityModels
import it.lorelore.nyanime.reader.quality.QualityReaderState
import it.lorelore.nyanime.reader.quality.QualityRegion
import it.lorelore.nyanime.reader.quality.QualityTile
import it.lorelore.nyanime.reader.quality.QualityTileKey
import it.lorelore.nyanime.reader.quality.QualityTiles
import it.lorelore.nyanime.reader.quality.QualityTitle
import it.lorelore.nyanime.reader.quality.QualityWorkerClient
import it.lorelore.nyanime.reader.quality.ReaderQualityPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.decoder.ImageDecoder
import tachiyomi.domain.entries.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.floor

/** Draws enhanced regions in the existing renderer's coordinates. It owns no gestures or page state. */
internal class ReaderQualityLayer(private val host: ReaderPageImageView, private val image: () -> View?) {
    private data class Result(
        val tile: QualityTile,
        val bitmap: Bitmap,
        val sample: Int,
        val outputScale: Int,
        val readyAt: Long = android.os.SystemClock.uptimeMillis(),
    )
    private data class Viewport(val region: QualityRegion, val sample: Int, val outputScale: Int)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val preferences = ReaderQualityPreferences(PreferenceManager.getDefaultSharedPreferences(host.context))
    private val results = LinkedHashMap<QualityTileKey, Result>(32, .75f, true)
    private var drawing: List<Result> = emptyList()
    private val budget = (
        (
            host.context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            ).memoryClass.toLong() *
            1024 *
            1024 /
            16
        )
        .coerceIn(8L * 1024 * 1024, 24L * 1024 * 1024)
    private var bytes = 0L
    private var scope: CoroutineScope? = null
    private var observing: Job? = null
    private var work: Job? = null
    private var requests: Channel<Viewport>? = null
    private var client: QualityWorkerClient? = null
    private var page: ReaderPage? = null
    private var mangaId: Long? = null
    private var title: QualityTitle? = null
    private var input: (() -> InputStream)? = null
    private var crop = false
    private var engine = QualityEngineId.OFF
    private var lastViewport: Viewport? = null
    private var attached = false
    private var showOriginal = false
    private var generation = 0L
    private var retryRevision = 0L
    private var fadeMillis = 0L
    private var lifecycle: Lifecycle? = null
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (attached) {
                observe()
                host.invalidate()
            }
        }
        override fun onStop(owner: LifecycleOwner) {
            stop()
        }
    }
    private val visible = Rect()

    fun bind(page: ReaderPage?, manga: Manga?) {
        if (this.page === page && mangaId == manga?.id) return
        stop()
        this.page = page
        mangaId = manga?.id
        title = manga?.takeIf { it.url.isNotBlank() && it.url.length <= 16384 }?.let { QualityTitle(it.source, it.url) }
        input = null
        if (attached) observe()
    }

    fun ready(input: (() -> InputStream)?, crop: Boolean) {
        if (this.input === input && this.crop == crop) return
        stopProcessing()
        this.input = input
        this.crop = crop
        host.invalidate()
    }

    fun attach() {
        attached = true
        lifecycle = host.findViewTreeLifecycleOwner()?.lifecycle
        lifecycle?.addObserver(lifecycleObserver)
        observe()
    }
    fun detach() {
        attached = false
        lifecycle?.removeObserver(lifecycleObserver)
        lifecycle = null
        stop()
    }

    private fun observe() {
        if (scope != null || page == null) return
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == false) return
        fadeMillis = if (Injekt.get<PlayerPreferences>().reduceMotion().get()) {
            0L
        } else {
            (160 * host.context.animatorDurationScale).toLong().coerceIn(0L, 320L)
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        observing = scope?.launch {
            combine(
                preferences.observe(title),
                QualityReaderState.original,
                QualityReaderState.retries,
            ) { selected, original, retries ->
                Triple(selected, mangaId in original, retries[mangaId] ?: 0L)
            }.collect { (selected, original, retry) ->
                showOriginal = original
                if (selected != engine || retry != retryRevision) {
                    stopProcessing()
                    engine = selected
                    retryRevision = retry
                    if (selected ==
                        QualityEngineId.OFF
                    ) {
                        QualityReaderState.report(mangaId, QualityReaderState.Status.OFF)
                    }
                }
                host.invalidate()
            }
        }
    }

    private fun startProcessing() {
        val open = input ?: run {
            QualityReaderState.report(mangaId, QualityReaderState.Status.ORIGINAL_FALLBACK)
            return
        }
        val selected = engine
        if (!attached || selected == QualityEngineId.OFF || work != null) return
        val target = image() as? SubsamplingScaleImageView ?: return
        if (!target.isReady) return
        val width = target.sWidth
        val height = target.sHeight
        val viewportRequests = Channel<Viewport>(Channel.CONFLATED)
        requests = viewportRequests
        val requestGeneration = generation
        val requestedManga = mangaId
        work = scope?.launch {
            var decoder: ImageDecoder? = null
            var worker: QualityWorkerClient? = null
            var imageHash = ""
            try {
                QualityReaderState.report(requestedManga, QualityReaderState.Status.WORKING)
                withContext(Dispatchers.IO) {
                    QualityImageInput(open()).use { input ->
                        val decoded = ImageDecoder.newInstance(
                            input.decoderStream,
                            crop,
                            TachiyomiImageDecoder.displayProfile,
                        )
                        try {
                            imageHash = input.fingerprint()
                            check(decoded != null && decoded.width == width && decoded.height == height) {
                                "Page geometry differs from quality input"
                            }
                            decoder = decoded
                        } catch (error: Throwable) {
                            decoded?.recycle()
                            throw error
                        }
                    }
                }
                val activeWorker = QualityWorkerClient(host.context).also {
                    client = it
                    worker = it
                }
                // Keep a moving view responsive. A stationary view never schedules a timer loop.
                viewportRequests.receiveAsFlow().debounce(100).collectLatest { viewport ->
                    QualityReaderState.report(requestedManga, QualityReaderState.Status.WORKING)
                    val tiles = QualityTiles.visible(width, height, viewport.region, viewport.sample, engine = selected)
                    for (tile in tiles) {
                        ensureActive()
                        val key = QualityTileKey(
                            imageHash,
                            tile.content,
                            viewport.sample,
                            selected,
                            QualityModels.get(selected)?.fingerprint ?: (
                                "97c24dc370ab300c108bfaa09db7f175aeff343674842c299cf3940a3d330427:" +
                                    "4c53ec2e287908f7ee7bcb266b0170421626d663576468b7d7dafc62962649a4"
                                ),
                            outputScale = viewport.outputScale,
                        )
                        if (results[key] != null) continue
                        var bitmap: Bitmap? = null
                        var result: Bitmap? = null
                        try {
                            withContext(Dispatchers.IO) {
                                bitmap = decoder!!.decode(
                                    Rect(tile.input.left, tile.input.top, tile.input.right, tile.input.bottom),
                                    viewport.sample,
                                ) ?: error("Quality tile decoding unavailable")
                            }
                            ensureActive()
                            result = activeWorker.process(bitmap!!, selected)
                            if (viewport.outputScale == 1) {
                                withContext(Dispatchers.Default) {
                                    val scaled = Bitmap.createScaledBitmap(
                                        result!!,
                                        bitmap!!.width,
                                        bitmap!!.height,
                                        true,
                                    )
                                    if (scaled !== result) {
                                        result?.recycle()
                                        result = scaled
                                    }
                                }
                            }
                            withContext(Dispatchers.Default) {
                                val crop = tile.sampledCrop(
                                    viewport.sample,
                                    viewport.outputScale,
                                    result!!.width,
                                    result!!.height,
                                )
                                val content = Bitmap.createBitmap(
                                    result!!,
                                    crop.left,
                                    crop.top,
                                    crop.width,
                                    crop.height,
                                )
                                if (content !== result) {
                                    result?.recycle()
                                    result = content
                                }
                            }
                            ensureActive()
                            check(requestGeneration == generation)
                            val value = Result(tile, result!!, viewport.sample, viewport.outputScale)
                            results[key] = value
                            bytes += result!!.allocationByteCount
                            result = null
                            trim(viewport)
                            drawing = results.values.sortedWith(
                                compareByDescending<Result> { it.sample }.thenBy { it.outputScale },
                            )
                            host.invalidate()
                        } finally {
                            bitmap?.recycle()
                            result?.recycle()
                        }
                    }
                    QualityReaderState.report(requestedManga, QualityReaderState.Status.READY)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The original remains the renderer's base and is never removed or hidden.
                android.util.Log.w("ReaderQuality", "Original retained: ${error.javaClass.simpleName}")
                if (generation == requestGeneration) {
                    clear()
                    host.invalidate()
                    QualityReaderState.report(requestedManga, QualityReaderState.Status.ORIGINAL_FALLBACK)
                }
            } catch (_: OutOfMemoryError) {
                if (generation == requestGeneration) {
                    clear()
                    host.invalidate()
                    QualityReaderState.report(requestedManga, QualityReaderState.Status.MEMORY_FALLBACK)
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { decoder?.recycle() }
                worker?.close()
                if (client === worker) client = null
            }
        }
        host.invalidate()
    }

    fun draw(canvas: Canvas) {
        if (engine == QualityEngineId.OFF || !attached) return
        val target = image() as? SubsamplingScaleImageView ?: run {
            QualityReaderState.report(mangaId, QualityReaderState.Status.ORIGINAL_FALLBACK)
            return
        }
        if (!target.isReady || !host.getLocalVisibleRect(visible)) return
        startProcessing()
        if (showOriginal) return
        val start = target.viewToSourceCoord(visible.left.toFloat(), visible.top.toFloat()) ?: return
        val end = target.viewToSourceCoord(visible.right.toFloat(), visible.bottom.toFloat()) ?: return
        val left = floor(start.x).toInt().coerceIn(0, target.sWidth)
        val top = floor(start.y).toInt().coerceIn(0, target.sHeight)
        val right = ceil(end.x).toInt().coerceIn(0, target.sWidth)
        val bottom = ceil(end.y).toInt().coerceIn(0, target.sHeight)
        if (right <= left || bottom <= top) return
        val sample = QualityTiles.sample(target.scale, target.sWidth, target.sHeight)
        val viewport =
            Viewport(
                QualityRegion(left, top, right, bottom),
                sample,
                if (target.scale * sample > 1f) 2 else 1,
            )
        if (viewport != lastViewport) {
            lastViewport = viewport
            requests?.trySend(viewport)
        }
        canvas.save()
        canvas.clipRect(visible)
        val now = android.os.SystemClock.uptimeMillis()
        var fading = false
        // Lowest resolution first. A more detailed tile covers it without moving the page.
        drawing.forEach { result ->
            val region = result.tile.content
            if (region.right <= left ||
                region.left >= right ||
                region.bottom <= top ||
                region.top >= bottom
            ) {
                return@forEach
            }
            val a = target.sourceToViewCoord(region.left.toFloat(), region.top.toFloat()) ?: return@forEach
            val b = target.sourceToViewCoord(region.right.toFloat(), region.bottom.toFloat()) ?: return@forEach
            val source = Rect(0, 0, result.bitmap.width, result.bitmap.height)
            val alpha = if (fadeMillis == 0L) 1f else ((now - result.readyAt).toFloat() / fadeMillis).coerceIn(0f, 1f)
            paint.alpha = (alpha * 255).toInt()
            fading = fading || alpha < 1f
            canvas.drawBitmap(result.bitmap, source, RectF(a.x, a.y, b.x, b.y), paint)
        }
        canvas.restore()
        if (fading) host.postInvalidateOnAnimation()
    }

    private fun trim(viewport: Viewport) {
        while (bytes > budget && results.isNotEmpty()) {
            val entry = results.entries.maxBy { (_, result) ->
                val a = result.tile.content
                val b = viewport.region
                val outside = a.right <= b.left || a.left >= b.right || a.bottom <= b.top || a.top >= b.bottom
                val mismatch = result.sample != viewport.sample || result.outputScale != viewport.outputScale
                (if (outside) 4e12 else 0.0) +
                    (if (mismatch) 2e12 else 0.0) +
                    kotlin.math.abs(a.left.toDouble() + a.right - b.left - b.right) +
                    kotlin.math.abs(a.top.toDouble() + a.bottom - b.top - b.bottom)
            }
            bytes -= entry.value.bitmap.allocationByteCount
            entry.value.bitmap.recycle()
            results.remove(entry.key)
        }
    }

    private fun clear() {
        drawing = emptyList()
        results.values.forEach { it.bitmap.recycle() }
        results.clear()
        bytes = 0L
    }
    private fun stopProcessing() {
        generation++
        work?.cancel()
        work = null
        requests?.close()
        requests = null
        client?.close()
        client = null
        lastViewport = null
        clear()
        host.invalidate()
    }
    private fun stop() {
        stopProcessing()
        observing?.cancel()
        observing = null
        scope?.cancel()
        scope = null
        engine = QualityEngineId.OFF
    }
}
