package eu.kanade.tachiyomi.ui.tv

import android.content.Context
import android.os.SystemClock
import eu.kanade.tachiyomi.ui.player.Anime4KMediaInfo
import eu.kanade.tachiyomi.ui.player.Anime4KMode
import eu.kanade.tachiyomi.ui.player.Anime4KPerformanceSample
import eu.kanade.tachiyomi.ui.player.Anime4KShaderPipeline
import eu.kanade.tachiyomi.ui.player.Anime4KSmartController
import eu.kanade.tachiyomi.ui.player.AniyomiMPVView
import eu.kanade.tachiyomi.ui.player.session.MpvPlaybackEngine
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Reuses the existing Smart governor. Never persists a channel as an episode. */
internal class TvAnime4K(
    context: Context,
    private val dimensions: () -> Triple<Int, Int, Double?>,
    private val scope: CoroutineScope,
    private val alive: () -> Boolean,
) {
    constructor(context: Context, view: AniyomiMPVView, scope: CoroutineScope, alive: () -> Boolean) :
        this(context, { Triple(view.width, view.height, view.display?.refreshRate?.toDouble()) }, scope, alive)

    constructor(context: Context, engine: MpvPlaybackEngine, scope: CoroutineScope, alive: () -> Boolean) :
        this(context, { Triple(engine.width, engine.height, null) }, scope, alive)

    private val pipeline = Anime4KShaderPipeline(context)
    private var enabled = false
    private var controller: Anime4KSmartController? = null
    private var sampling: Job? = null

    fun prepare() = pipeline.copyAssets()

    fun enabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        sampling?.cancel()
        sampling = null
        controller = null
        if (!alive()) return
        if (!value) pipeline.apply(Anime4KMode.Off) else loaded()
    }

    fun loaded() {
        sampling?.cancel()
        controller = null
        if (!enabled || !alive()) return
        sampling = scope.launch {
            while (enabled && alive()) {
                val now = SystemClock.elapsedRealtime()
                val (surfaceWidth, surfaceHeight, refreshRate) = dimensions()
                val width = int("video-params/w")
                val height = int("video-params/h")
                if (width != null &&
                    height != null &&
                    width > 0 &&
                    height > 0 &&
                    surfaceWidth > 0 &&
                    surfaceHeight > 0
                ) {
                    val fps =
                        double("estimated-vf-fps")?.takeIf { it > 0 } ?: double("container-fps")?.takeIf { it > 0 }
                    val media = Anime4KMediaInfo(
                        width,
                        height,
                        surfaceWidth,
                        surfaceHeight,
                        fps ?: 24.0,
                        refreshRate,
                        fps != null,
                        double("speed") ?: 1.0,
                    )
                    val previous = controller
                    val current = previous ?: Anime4KSmartController(media, now).also { controller = it }
                    val mode = if (previous == null) current.currentMode else current.updateMediaInfo(media, now)?.mode
                    mode?.let { apply(current, it, now) }
                    current.onSample(
                        Anime4KPerformanceSample(
                            now,
                            int("frame-drop-count")?.toLong(),
                            int("decoder-frame-drop-count")?.toLong(),
                            int("vo-delayed-frame-count")?.toLong(),
                            null,
                            mistimedFrames = int("mistimed-frame-count")?.toLong(),
                            playing = runCatching {
                                MPVLib.getPropertyBoolean("pause") == false &&
                                    MPVLib.getPropertyBoolean("paused-for-cache") == false
                            }.getOrDefault(false),
                        ),
                    )?.let { apply(current, it.mode, now) }
                }
                delay(1000)
            }
        }
    }

    private fun apply(controller: Anime4KSmartController, requested: Anime4KMode, now: Long) {
        var mode = requested
        repeat(8) {
            if (!alive() || !enabled) return
            if (pipeline.apply(mode)) return
            mode = controller.onShaderError(now)?.mode ?: Anime4KMode.Off
        }
        if (alive()) pipeline.apply(Anime4KMode.Off)
    }

    fun close() {
        enabled = false
        sampling?.cancel()
        sampling = null
        controller = null
    }
    private fun int(property: String) = runCatching { MPVLib.getPropertyInt(property) }.getOrNull()
    private fun double(
        property: String,
    ) = runCatching { MPVLib.getPropertyDouble(property) }.getOrNull()?.takeIf { it.isFinite() }
}
