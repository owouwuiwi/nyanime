package eu.kanade.tachiyomi.ui.player.session

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.player.PlayerSession
import eu.kanade.tachiyomi.ui.player.VideoAspect
import kotlin.math.max
import kotlin.math.min

/**
 * The child surface keeps its layout size during motion. Only its RenderNode transform changes;
 * MPV keeps the same buffer through page/mini transitions. Only window or video geometry changes it.
 */
internal class MpvRenderView(
    context: Context,
    private val session: PlayerSession,
    activity: BaseActivity,
) : FrameLayout(context) {
    private val video = MpvSurfaceView(context, session, activity)
    private var renderWidth = 1
    private var renderHeight = 1
    private var mode = VideoAspect.Fit
    private var radius: () -> Float = { 0f }

    init {
        clipChildren = true
        clipToPadding = true
        video.pivotX = 0f
        video.pivotY = 0f
        video.clipToOutline = true
        video.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val scale = min(video.scaleX, video.scaleY).coerceAtLeast(0.001f)
                outline.setRoundRect(0, 0, renderWidth, renderHeight, radius() / scale)
            }
        }
        addView(video, LayoutParams(1, 1))
    }

    fun configure(width: Int, height: Int, mode: VideoAspect, windowCovered: Boolean, radius: () -> Float) {
        if (this.mode != mode) session.finishAnime4KReveal()
        this.mode = mode
        this.radius = radius
        // SurfaceView has a separate compositor layer. While the app window owns its
        // captured rotation frame, keep that layer attached but invisible so its own
        // rotation transaction cannot appear outside the cover for one frame.
        video.alpha = if (windowCovered) 0f else 1f
        if (renderWidth != width || renderHeight != height) {
            session.finishAnime4KReveal()
            renderWidth = width.coerceAtLeast(1)
            renderHeight = height.coerceAtLeast(1)
            video.layoutParams = LayoutParams(renderWidth, renderHeight)
            video.holder.setFixedSize(renderWidth, renderHeight)
        }
        transform()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        video.layout(0, 0, renderWidth, renderHeight)
        transform()
    }

    private fun transform() {
        if (width <= 0 || height <= 0) return
        val scaleX = width.toFloat() / renderWidth
        val scaleY = height.toFloat() / renderHeight
        val uniform = if (mode == VideoAspect.Crop) max(scaleX, scaleY) else min(scaleX, scaleY)
        video.scaleX = if (mode == VideoAspect.Stretch) scaleX else uniform
        video.scaleY = if (mode == VideoAspect.Stretch) scaleY else uniform
        video.translationX = (width - renderWidth * video.scaleX) / 2f
        video.translationY = (height - renderHeight * video.scaleY) / 2f
        video.invalidateOutline()
    }

    fun release() = video.release()
}
