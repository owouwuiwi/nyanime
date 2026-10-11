package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.data.reading.ReadingPageLayout
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import nyanime.panels.GuidedLocation
import nyanime.panels.GuidedViewport
import nyanime.panels.PanelRect
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.entries.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private val alwaysDecodeLongStripWithSSIV by lazy {
        Injekt.get<BasePreferences>().alwaysDecodeLongStripWithSSIV().get()
    }

    private var pageView: View? = null
    private val readingInk = ReadingInkLayer(this) { pageView }
    var readingLayout: ReadingPageLayout
        get() = readingInk.layout
        set(value) {
            readingInk.layout = value
        }

    fun bindReadingPage(page: ReaderPage?, manga: Manga?) {
        readingInk.bind(page, manga)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        readingInk.attach()
    }

    override fun onDetachedFromWindow() {
        readingInk.detach()
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        readingInk.draw(canvas)
    }

    var onTouchObserved: ((MotionEvent) -> Unit)? = null
    private var guidedTarget: GuidedLocation? = null
    private var guidedEnabled = false
    private var guidedFocus: GuidedViewport? = null
    private var guidedRect = PanelRect.PAGE
    private var guidedUserZoomed = false
    private var guidedZoomGesture = false
    private var guidedSuppressTouch = false
    private var guidedNativeTouch = false
    private var guidedTouchDown: MotionEvent? = null
    private var guidedResizeLocation: GuidedLocation? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (guidedEnabled && guidedFocus != null && oldw > 0 && oldh > 0) {
            // Capture before the native child resizes; its old source center is still valid here.
            guidedResizeLocation = guidedLocation(0, false)
        }
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        guidedResizeLocation?.let { location ->
            guidedResizeLocation = null
            // System bars, rotation and editor controls can all change the available viewport.
            // Refitting in this layout prevents the next gesture from correcting a stale focus.
            if (guidedEnabled) focusPanel(guidedRect, false, location)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = readingInk.touch(event) {
        val image = (pageView as? SubsamplingScaleImageView).takeIf { guidedEnabled }
        val handled = if (image == null) {
            super.dispatchTouchEvent(it)
        } else {
            if (it.actionMasked == MotionEvent.ACTION_DOWN) {
                guidedZoomGesture = false
                guidedNativeTouch = guidedUserZoomed && !guidedSuppressTouch
                guidedTouchDown?.recycle()
                guidedTouchDown = if (guidedNativeTouch) null else MotionEvent.obtain(it)
            }
            if (it.pointerCount > 1 && !guidedSuppressTouch) {
                guidedZoomGesture = true
                if (!guidedNativeTouch) {
                    guidedNativeTouch = true
                    image.setZoomEnabled(true)
                    // The renderer must receive a balanced stream when a locked swipe becomes a pinch.
                    guidedTouchDown?.let { down ->
                        super.dispatchTouchEvent(down)
                        down.recycle()
                    }
                    guidedTouchDown = null
                }
            }
            image.setZoomEnabled(guidedZoomGesture)
            val consumed = if (guidedNativeTouch && !guidedSuppressTouch) super.dispatchTouchEvent(it) else true
            if (it.actionMasked == MotionEvent.ACTION_UP || it.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (!guidedSuppressTouch) settleGuidedGesture(image)
                guidedNativeTouch = false
                guidedSuppressTouch = false
                guidedTouchDown?.recycle()
                guidedTouchDown = null
            }
            consumed
        }
        // GuidedViewer owns single-finger navigation and double tap; SSIV owns pinch and enlarged pan.
        // Navigation runs last because forwarding UP afterwards cancels its new animation.
        onTouchObserved?.invoke(it)
        handled
    }

    private var config: Config? = null

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: (() -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null
    var onSourceTransformChanged: (() -> Unit)? = null

    fun setGuidedImage(file: java.io.File) {
        guidedEnabled = true
        guidedFocus = null
        guidedUserZoomed = false
        guidedSuppressTouch = false
        config = Config(zoomDuration = eu.kanade.presentation.motion.ModernMotion.PAGE_MILLIS)
        prepareNonAnimatedImageView()
        (pageView as? SubsamplingScaleImageView)?.apply {
            setPanEnabled(true)
            setQuickScaleEnabled(false)
        }
        setNonAnimatedImage(file, config!!)
    }

    fun setGuidedAnimatedImage(file: java.io.File) {
        config = Config(zoomDuration = eu.kanade.presentation.motion.ModernMotion.PAGE_MILLIS)
        prepareAnimatedImageView()
        setAnimatedImage(file, config!!)
    }

    fun guidedSourcePoint(x: Float, y: Float): PointF? =
        (pageView as? SubsamplingScaleImageView)?.takeIf { it.isReady }?.let { view ->
            view.viewToSourceCoord(x, y)?.let { PointF(it.x / view.sWidth, it.y / view.sHeight) }
        }

    fun guidedViewPoint(x: Float, y: Float): PointF? =
        (pageView as? SubsamplingScaleImageView)?.takeIf { it.isReady }?.let {
            it.sourceToViewCoord(x * it.sWidth, y * it.sHeight)
        }

    fun guidedLocation(panel: Int, overview: Boolean): nyanime.panels.GuidedLocation? =
        (pageView as? SubsamplingScaleImageView)?.takeIf { it.isReady }?.let { view ->
            val target = guidedTarget
            if (target != null) return@let target.copy(panel = panel, overview = overview)
            val focus = guidedFocus
            if (focus != null && !guidedUserZoomed && !guidedZoomGesture) {
                return@let GuidedLocation(
                    panel,
                    focus.centerX,
                    focus.centerY,
                    (focus.scale / view.minScale).coerceIn(.1f, 100f),
                    overview,
                    1f,
                )
            }
            val center = view.center ?: return@let null
            nyanime.panels.GuidedLocation(
                panel,
                (center.x / view.sWidth).coerceIn(0f, 1f),
                (center.y / view.sHeight).coerceIn(0f, 1f),
                (view.scale / view.minScale).coerceIn(0.1f, 100f),
                overview,
                focus?.let { (view.scale / it.scale).coerceIn(.1f, 100f) },
            )
        }

    fun focusPanel(rect: PanelRect, animate: Boolean = true, restore: GuidedLocation? = null) {
        val view = (pageView as? SubsamplingScaleImageView)?.takeIf { it.isReady } ?: return
        if (view.width == 0 || view.height == 0) return
        val focus = GuidedViewport.fit(rect, view.sWidth, view.sHeight, view.width, view.height, view.minScale)
        guidedFocus = focus
        guidedRect = rect
        view.setPanLimit(
            // Keep the edge panel visible throughout the transition back to the whole page.
            if (rect == PanelRect.PAGE && (!animate || context.animatorDurationScale == 0f)) {
                SubsamplingScaleImageView.PAN_LIMIT_INSIDE
            } else {
                SubsamplingScaleImageView.PAN_LIMIT_CENTER
            },
        )
        view.maxScale = maxOf(view.minScale * 20, focus.scale * 3)
        val target = focus.restore(restore, view.minScale).let { it.copy(scale = it.scale.coerceAtMost(view.maxScale)) }
        guidedUserZoomed = focus.isEnlarged(target.scale)
        animateGuided(view, target, animate)
    }

    private fun animateGuided(view: SubsamplingScaleImageView, destination: GuidedViewport, animate: Boolean) {
        val scale = destination.scale
        val center = PointF(destination.centerX * view.sWidth, destination.centerY * view.sHeight)
        val duration = (eu.kanade.presentation.motion.ModernMotion.PAGE_MILLIS * context.animatorDurationScale).toLong()
        if (!animate || duration == 0L) {
            guidedTarget = null
            view.setScaleAndCenter(scale, center)
        } else {
            val target = GuidedLocation(
                centerX = center.x / view.sWidth,
                centerY = center.y / view.sHeight,
                zoom = (scale / view.minScale).coerceIn(.1f, 100f),
                focusZoom = guidedFocus?.let { (scale / it.scale).coerceIn(.1f, 100f) },
            )
            guidedTarget = target
            view.animateScaleAndCenter(
                scale,
                center,
            )?.withDuration(duration)?.withEasing(EASE_IN_OUT_QUAD)?.withInterruptible(true)
                ?.withOnAnimationEventListener(object : SubsamplingScaleImageView.DefaultOnAnimationEventListener() {
                    override fun onComplete() {
                        if (guidedTarget !== target) return
                        guidedTarget = null
                        if (guidedRect == PanelRect.PAGE) view.setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
                        // This also updates the native pinch anchor without moving the visible image.
                        view.setScaleAndCenter(scale, center)
                    }
                    override fun onInterruptedByUser() {
                        if (guidedTarget === target) guidedTarget = null
                    }
                    override fun onInterruptedByNewAnim() {
                        if (guidedTarget === target) guidedTarget = null
                    }
                })?.start()
        }
    }

    private fun settleGuidedGesture(view: SubsamplingScaleImageView) {
        val focus = guidedFocus ?: return
        if (guidedTarget != null) return
        guidedUserZoomed = guidedZoomGesture &&
            focus.isEnlarged(view.scale) ||
            guidedUserZoomed &&
            focus.isEnlarged(view.scale)
        if (!guidedUserZoomed) {
            val center = view.center ?: return
            if (kotlin.math.abs(view.scale - focus.scale) > focus.scale * .001f ||
                kotlin.math.abs(center.x / view.sWidth - focus.centerX) > .001f ||
                kotlin.math.abs(center.y / view.sHeight - focus.centerY) > .001f
            ) {
                animateGuided(view, focus, true)
            }
        }
    }

    fun guidedDoubleTap(event: MotionEvent): Boolean {
        val view = (pageView as? SubsamplingScaleImageView)?.takeIf { it.isReady } ?: return false
        val focus = guidedFocus ?: return false
        val point = guidedSourcePoint(event.x, event.y) ?: return false
        // End the native DOWN before animating, and do not let the second UP interrupt the zoom.
        MotionEvent.obtain(event).let { cancel ->
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        guidedSuppressTouch = true
        guidedUserZoomed = !guidedUserZoomed
        animateGuided(
            view,
            if (guidedUserZoomed) {
                GuidedViewport(
                    (focus.scale * 2).coerceAtMost(view.maxScale),
                    point.x.coerceIn(0f, 1f),
                    point.y.coerceIn(0f, 1f),
                )
            } else {
                focus
            },
            true,
        )
        return true
    }

    fun guidedAtPanelScale(rect: PanelRect): Boolean = guidedFocus != null && guidedRect == rect && !guidedUserZoomed

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        if (guidedEnabled && pageView is SubsamplingScaleImageView) focusPanel(PanelRect.PAGE, false)
        onImageLoaded?.invoke()
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError() {
        onImageLoadError?.invoke()
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError()
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        if (config != null &&
            config!!.landscapeZoom &&
            config!!.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config!!.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) {
                        PointF(0F, 0F)
                    } else {
                        PointF(
                            sWidth.toFloat(),
                            0F,
                        )
                    }
                    ZoomStartPosition.RIGHT -> if (forward) {
                        PointF(sWidth.toFloat(), 0F)
                    } else {
                        PointF(
                            0F,
                            0F,
                        )
                    }
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                animateScaleAndCenter(targetScale, point)!!
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    fun setImage(file: java.io.File, isAnimated: Boolean, config: Config) {
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(file, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(file, config)
        }
    }

    fun recycle() = pageView?.let {
        guidedTarget = null
        guidedFocus = null
        guidedResizeLocation = null
        guidedEnabled = false
        guidedSuppressTouch = false
        guidedNativeTouch = false
        guidedTouchDown?.recycle()
        guidedTouchDown = null
        readingInk.bind(null, null)
        when (it) {
            is SubsamplingScaleImageView -> {
                it.recycle()
                it.setPanEnabled(true)
                it.setZoomEnabled(true)
                it.setQuickScaleEnabled(true)
                it.setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            }
            is AppCompatImageView -> it.dispose()
        }
        it.isVisible = false
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                        this@ReaderPageImageView.invalidate()
                        onSourceTransformChanged?.invoke()
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        this@ReaderPageImageView.invalidate()
                        onSourceTransformChanged?.invoke()
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError()
                }
            },
        )

        when (data) {
            is java.io.File -> {
                setImage(ImageSource.uri(context, android.net.Uri.fromFile(data)))
                isVisible = true
            }
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            is BufferedSource -> {
                if (!isWebtoon || alwaysDecodeLongStripWithSSIV) {
                    setHardwareConfig(ImageUtil.canUseHardwareBitmap(data))
                    setImage(ImageSource.inputStream(data.inputStream()))
                    isVisible = true
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            isVisible = true
                        },
                        onError = {
                            onImageLoadError()
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
                setOnMatrixChangeListener { this@ReaderPageImageView.invalidate() }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
                onError = {
                    this@ReaderPageImageView.onImageLoadError()
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F
