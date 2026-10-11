package eu.kanade.tachiyomi.ui.reader.spread

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.button.MaterialButton
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadManager
import eu.kanade.tachiyomi.data.reading.ReadingTogetherManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderTransitionView
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** A bounded, original-page renderer. Pairing never changes loader, ink or room identities. */
class SpreadViewer(private val activity: ReaderActivity, private val settings: SpreadRecord) : Viewer {
    private val preferences: ReaderPreferences = Injekt.get()
    private val downloads: MangaDownloadManager = Injekt.get()
    private val continuous = settings.mode == SpreadMode.VERTICAL
    val isContinuous get() = continuous
    private val rtl = ReadingMode.fromPreference(activity.viewModel.getMangaReadingMode()) == ReadingMode.RIGHT_TO_LEFT
    val isRightToLeft get() = rtl
    private val verticalNavigation = continuous ||
        ReadingMode.fromPreference(activity.viewModel.getMangaReadingMode()).direction == ReadingMode.Direction.Vertical
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var disposed = false
    private var chapters: ViewerChapters? = null
    private var active: ReaderPage? = null
    private var pending: ReaderPage? = null
    private var currentSlot = 0

    // File geometry classifies original pages; decoded geometry sizes the visible spread.
    // Cropping must not turn a panorama into two separate pages or change their identities.
    private val dimensions = mutableMapOf<ReaderPage, Pair<Int, Int>>()
    private val renderedDimensions = mutableMapOf<ReaderPage, Pair<Int, Int>>()
    private val temporaryFiles = ConcurrentHashMap.newKeySet<File>()
    private var regroupPending = false
    private val layout = LinearLayoutManager(
        activity,
        if (verticalNavigation) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL,
        !verticalNavigation && rtl,
    ).apply { isItemPrefetchEnabled = false }
    private val adapter = Adapter()
    private val recycler = RecyclerView(activity).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        layoutManager = layout
        this.adapter = this@SpreadViewer.adapter
        itemAnimator = null
        setItemViewCacheSize(1)
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val frame = ZoomFrame().apply { addView(recycler, FrameLayout.LayoutParams(-1, -1)) }
    var range by mutableStateOf<IntRange?>(null)
        private set

    init {
        frame.setBackgroundColor(
            when (preferences.readerTheme().get()) {
                0 -> Color.WHITE
                2 -> 0xff202125.toInt()
                else -> Color.BLACK
            },
        )
        if (!continuous) PagerSnapHelper().attachToRecyclerView(recycler)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) = publishVisible()
            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING &&
                    !activity.isScrollingThroughPages
                ) {
                    activity.hideMenu()
                }
                if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    if (regroupPending) regroup() else publishVisible()
                }
            }
        })
        recycler.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (!disposed) {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    resizeVisible()
                }
                recycler.post { if (!disposed) publishVisible() }
            }
        }
    }

    private fun resizeVisible() {
        val position = (pending ?: active)?.let(::positionOf) ?: RecyclerView.NO_POSITION
        val top = if (continuous) layout.findViewByPosition(position)?.top else null
        recycler.children.forEach { (recycler.getChildViewHolder(it) as? Holder)?.resize() }
        if (top != null && position >= 0) layout.scrollToPositionWithOffset(position, top)
        frame.refit()
    }

    init {
        scope.launch {
            val preference = if (continuous) preferences.cropBordersWebtoon() else preferences.cropBorders()
            preference.changes().drop(1).collectLatest {
                if (!disposed) {
                    recycler.children.forEach { child ->
                        (recycler.getChildViewHolder(child) as? Holder)?.surfaces?.forEach(Surface::refreshImage)
                    }
                }
            }
        }
    }

    override fun getView(): View = frame

    override fun destroy() {
        disposed = true
        scope.cancel()
        frame.stop()
        recycler.stopScroll()
        recycler.children.forEach { child ->
            (recycler.getChildViewHolder(child) as? Holder)?.clear()
        }
        recycler.adapter = null
        dimensions.clear()
        renderedDimensions.clear()
        temporaryFiles.forEach { it.delete() }
        temporaryFiles.clear()
    }

    override fun setChapters(chapters: ViewerChapters) {
        if (disposed) return
        val original = active
        this.chapters = chapters
        val allowed = listOfNotNull(chapters.prevChapter, chapters.currChapter, chapters.nextChapter)
        val pages = allowed.flatMap { it.pages.orEmpty() }.toSet()
        dimensions.keys.retainAll(pages)
        renderedDimensions.keys.retainAll(pages)
        val anchor = pending ?: original?.takeIf { it.chapter in allowed } ?: chapters.currChapter.pages
            ?.getOrNull(chapters.currChapter.requestedPage.coerceAtLeast(0))
        rebuild(anchor)
    }

    private fun items(): List<Any> {
        val chapters = chapters ?: return emptyList()
        return buildList {
            chapters.prevChapter?.let { chapter ->
                addAll(
                    SpreadGrouping.group(
                        chapter.pages.orEmpty(),
                        settings.offsetFor(chapter.chapter.url),
                        ::standalone,
                    ).takeLast(2),
                )
            }
            if (preferences.alwaysShowChapterTransition().get() ||
                chapters.prevChapter?.state !is ReaderChapter.State.Loaded ||
                calculateChapterGap(chapters.currChapter, chapters.prevChapter) > 0
            ) {
                add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
            }
            addAll(
                SpreadGrouping.group(
                    chapters.currChapter.pages.orEmpty(),
                    settings.offsetFor(chapters.currChapter.chapter.url),
                    ::standalone,
                ),
            )
            if (preferences.alwaysShowChapterTransition().get() ||
                chapters.nextChapter?.state !is ReaderChapter.State.Loaded ||
                calculateChapterGap(chapters.nextChapter, chapters.currChapter) > 0
            ) {
                add(ChapterTransition.Next(chapters.currChapter, chapters.nextChapter))
            }
            chapters.nextChapter?.let { chapter ->
                addAll(
                    SpreadGrouping.group(
                        chapter.pages.orEmpty(),
                        settings.offsetFor(chapter.chapter.url),
                        ::standalone,
                    ).take(2),
                )
            }
        }
    }

    private fun standalone(page: ReaderPage) =
        dimensions[page]?.let { SpreadGrouping.standalone(it.first, it.second) } == true

    private fun displaySize(page: ReaderPage) =
        SpreadLayout.displaySize(dimensions[page], renderedDimensions[page])

    private fun rebuild(anchor: ReaderPage?) {
        val previousPosition = anchor?.let(::positionOf) ?: RecyclerView.NO_POSITION
        val previousView = layout.findViewByPosition(previousPosition)
        val offset = if (continuous) previousView?.top ?: 0 else 0
        val updated = items()
        val previous = adapter.items
        val differences = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = updated.size
                override fun areItemsTheSame(old: Int, new: Int): Boolean =
                    previous[old].javaClass == updated[new].javaClass && previous[old] == updated[new]
                override fun areContentsTheSame(old: Int, new: Int) = updated[new] !is ChapterTransition
            },
            false,
        )
        adapter.items = updated
        differences.dispatchUpdatesTo(adapter)
        if (anchor != null) {
            pending = anchor
            val position = positionOf(anchor)
            if (position >= 0) layout.scrollToPositionWithOffset(position, offset)
        }
        recycler.post { if (!disposed) publishVisible() }
    }

    private fun regroup() {
        if (disposed) return
        if (recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE || frame.zoomed) {
            regroupPending = true
            return
        }
        regroupPending = false
        rebuild(pending ?: active)
    }

    private fun positionOf(page: ReaderPage): Int = adapter.items.indexOfFirst {
        it is Spread<*> && page in it.pages
    }

    override fun moveToPage(page: ReaderPage) {
        if (disposed) return
        pending = page
        active = page
        frame.reset()
        val position = positionOf(page)
        if (position >= 0) {
            layout.scrollToPositionWithOffset(position, 0)
            recycler.post { if (!disposed) publishVisible() }
        }
    }

    private fun publishVisible() {
        if (disposed || recycler.isComputingLayout) return
        val requested = pending?.let(::positionOf)?.let(layout::findViewByPosition)?.takeIf {
            if (verticalNavigation) {
                it.bottom > 0 && it.top < recycler.height
            } else {
                it.right > 0 &&
                    it.left < recycler.width
            }
        }
        val child = requested ?: if (continuous) {
            recycler.children.filter {
                it.bottom >= minOf(it.height, recycler.height) / 2 && it.top < recycler.height
            }.minByOrNull { it.top }
        } else {
            recycler.children.minByOrNull {
                if (verticalNavigation) {
                    abs((it.top + it.bottom) / 2f - recycler.height / 2f)
                } else {
                    abs((it.left + it.right) / 2f - recycler.width / 2f)
                }
            }
        }
        child ?: return
        val holder = recycler.getChildViewHolder(child) as? Holder ?: return
        currentSlot = holder.bindingAdapterPosition
        val spread = holder.spread ?: run {
            range = null
            (holder.transition)?.let {
                if (it.to != null) activity.requestPreloadChapter(it.to!!)
            }
            return
        }
        val wanted = pending?.takeIf { it in spread.pages }
        val selected = wanted ?: active?.takeIf { it in spread.pages } ?: spread.pages.first()
        if (pending != null && wanted == null) return
        val visible = Rect()
        if (!holder.row.getLocalVisibleRect(visible)) return
        // A requested pair must enter the viewport before replacing the current selection.
        val sufficient = if (verticalNavigation) {
            visible.height() >= minOf(holder.row.height, recycler.height) / 2
        } else {
            visible.width() >= minOf(holder.row.width, recycler.width) / 2
        }
        if (wanted != null && !sufficient) return
        pending = null
        val changed = active != selected
        active = selected
        range = (spread.pages.first().index + 1)..(spread.pages.last().index + 1)
        // Do not count a barely visible or still-loading partner as a read page.
        val progress = if (sufficient) {
            SpreadGrouping.progress(spread) { page ->
                holder.surfaces.firstOrNull { it.page == page }?.ready == true
            }
        } else {
            null
        }
        if (changed || holder.lastProgress != progress || holder.lastActive != selected) {
            holder.lastProgress = progress
            holder.lastActive = selected
            activity.onSpreadPageSelected(selected, progress)
        }
        if (spread.pages.first().chapter == chapters?.currChapter &&
            spread.pages.last().index >= spread.pages.first().chapter.pages.orEmpty().size - 5
        ) {
            chapters?.nextChapter?.let(activity::requestPreloadChapter)
        }
    }

    private fun navigate(delta: Int) {
        if (disposed) return
        frame.reset()
        val current = currentSlot
        val next = (current + delta).coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
        pending = (adapter.items.getOrNull(next) as? Spread<*>)?.pages?.firstOrNull() as? ReaderPage
        if (preferences.pageTransitions().get() && activity.animatorDurationScale > 0f) {
            recycler.smoothScrollToPosition(next)
        } else {
            layout.scrollToPositionWithOffset(next, 0)
            recycler.post { publishVisible() }
        }
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val next = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!preferences.readWithVolumeKeys().get()) return false
                !preferences.readWithVolumeKeysInverted().get()
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!preferences.readWithVolumeKeys().get()) return false
                preferences.readWithVolumeKeysInverted().get()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> !rtl
            KeyEvent.KEYCODE_DPAD_LEFT -> rtl
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> true
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> false
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_UP) navigate(if (next) 1 else -1)
        return true
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return false
        navigate(if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0) 1 else -1)
        return true
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<Any> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder()
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
        override fun onViewRecycled(holder: Holder) = holder.clear()
        override fun onViewDetachedFromWindow(holder: Holder) = holder.clear()
        override fun onViewAttachedToWindow(holder: Holder) {
            val position = holder.bindingAdapterPosition
            if (position >= 0 && holder.surfaces.isEmpty() && holder.transition == null) {
                items.getOrNull(position)?.let(holder::bind)
            }
        }
    }

    private inner class Holder : RecyclerView.ViewHolder(FrameLayout(activity)) {
        val row = itemView as FrameLayout
        var spread: Spread<ReaderPage>? = null
        var transition: ChapterTransition? = null
        val surfaces = mutableListOf<Surface>()
        var lastProgress: ReaderPage? = null
        var lastActive: ReaderPage? = null
        private var line: LinearLayout? = null
        val contentSize get() = line?.layoutParams?.let { it.width to it.height }

        fun resize() {
            val width = recycler.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val viewportHeight = recycler.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val bounds = spread?.let { group ->
                SpreadLayout.fit(width, viewportHeight.takeUnless { continuous }, group.pages.map(::displaySize))
            }
            val height = if (continuous) bounds?.height ?: width else viewportHeight
            if (row.layoutParams.height != height) row.layoutParams = row.layoutParams.apply { this.height = height }
            val line = line ?: return
            val fitted = bounds ?: return
            val widths = if (rtl) fitted.pageWidths.reversed() else fitted.pageWidths
            surfaces.forEachIndexed { index, surface ->
                val size = widths[index]
                if (surface.layoutParams.width != size) {
                    surface.layoutParams = surface.layoutParams.apply { this.width = size }
                }
            }
            if (line.layoutParams.width != fitted.width || line.layoutParams.height != fitted.height) {
                line.layoutParams = line.layoutParams.apply {
                    this.width = fitted.width
                    this.height = fitted.height
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun bind(item: Any) {
            clear()
            spread = item as? Spread<ReaderPage>
            transition = item as? ChapterTransition
            val group = spread
            val width = recycler.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val viewportHeight = recycler.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val bounds = group?.let {
                SpreadLayout.fit(width, viewportHeight.takeUnless { continuous }, it.pages.map(::displaySize))
            }
            val height = if (continuous) bounds?.height ?: width else viewportHeight
            // Cached rows can be rebound on attachment. Preserve RecyclerView's
            // holder bookkeeping instead of replacing its existing LayoutParams.
            row.layoutParams = (
                row.layoutParams as? RecyclerView.LayoutParams ?: RecyclerView.LayoutParams(-1, height)
                ).apply {
                this.width = ViewGroup.LayoutParams.MATCH_PARENT
                this.height = height
            }
            if (group == null) {
                val transition = transition ?: return
                val column = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }
                val info = ReaderTransitionView(activity).apply {
                    bind(transition, downloads, activity.viewModel.manga)
                }
                column.addView(info)
                column.addView(
                    MaterialButton(activity).apply {
                        text = activity.getString(R.string.spread_continue)
                        setOnClickListener {
                            transition.to?.let { chapter ->
                                chapter.pages?.let { pages ->
                                    val page = if (transition is ChapterTransition.Prev) {
                                        pages.lastOrNull()
                                    } else {
                                        pages.firstOrNull()
                                    }
                                    page?.let(::moveToPage)
                                } ?: activity.requestPreloadChapter(chapter)
                            } ?: activity.showMenu()
                        }
                    },
                )
                row.addView(column, FrameLayout.LayoutParams(-1, -1))
                return
            }
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            this.line = line
            val visual = if (rtl) group.pages.reversed() else group.pages
            val fitted = requireNotNull(bounds)
            val widths = if (rtl) fitted.pageWidths.reversed() else fitted.pageWidths
            visual.forEachIndexed { index, page ->
                val surface = Surface(page)
                surfaces += surface
                line.addView(surface, LinearLayout.LayoutParams(widths[index], -1))
            }
            row.addView(line, FrameLayout.LayoutParams(fitted.width, fitted.height, Gravity.CENTER))
        }

        fun clear() {
            surfaces.forEach(Surface::close)
            surfaces.clear()
            row.removeAllViews()
            line = null
            spread = null
            transition = null
            lastProgress = null
            lastActive = null
        }
    }

    private inner class Surface(val page: ReaderPage) : FrameLayout(activity) {
        val image = ReaderPageImageView(activity)
        var ready = false
            private set
        private var closed = false
        private var job: Job? = null
        private var file: File? = null
        private var animated = false
        private val placeholder = TextView(activity).apply {
            gravity = Gravity.CENTER
            text = (page.index + 1).toString()
            setTextColor(if (preferences.readerTheme().get() == 0) 0x55666666 else 0x55999999)
            background = GradientDrawable().apply {
                setColor(if (preferences.readerTheme().get() == 0) 0xffeeeeee.toInt() else 0xff161616.toInt())
                cornerRadius = 8 * resources.displayMetrics.density
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private var error: MaterialButton? = null
        init {
            addView(image, LayoutParams(-1, -1))
            addView(placeholder, LayoutParams(-1, -1))
            image.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) fitImage()
            }
            contentDescription = activity.getString(R.string.spread_page, page.index + 1)
            image.bindReadingPage(page, activity.viewModel.manga)
            image.onImageLoaded = {
                if (!closed && !disposed) {
                    ready = true
                    var renderedSize: Pair<Int, Int>? = null
                    image.children.filterIsInstance<SubsamplingScaleImageView>().forEach {
                        renderedSize = SpreadLayout.displaySize(
                            dimensions[page],
                            it.sWidth to it.sHeight,
                            it.imageRotation.rotation % 180 != 0,
                        )
                        it.setPanEnabled(false)
                        it.setZoomEnabled(false)
                        it.setQuickScaleEnabled(false)
                    }
                    image.children.filterIsInstance<PhotoView>().forEach {
                        renderedSize = SpreadLayout.displaySize(
                            dimensions[page],
                            it.drawable?.let { drawable -> drawable.intrinsicWidth to drawable.intrinsicHeight },
                        )
                        it.isZoomable = false
                        it.scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    renderedSize?.let { size ->
                        if (renderedDimensions.put(page, size) != size) resizeVisible()
                    }
                    fitImage()
                    placeholder.animate().cancel()
                    val duration = (ModernMotion.EXIT_MILLIS * activity.animatorDurationScale).toLong()
                    placeholder.animate().alpha(0f).setDuration(duration).withEndAction {
                        placeholder.isVisible = false
                    }.start()
                    publishVisible()
                }
            }
            image.onImageLoadError = { if (!closed) showError() }
            image.onTouchObserved = { event ->
                if (!frame.zoomed && ReadingTogetherManager.existing()?.tools?.value?.drawing != true) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN && !closed) {
                    active = page
                    pending = page
                    publishVisible()
                }
            }
            job = scope.launch {
                val loader = page.chapter.pageLoader ?: return@launch
                supervisorScope {
                    launch(Dispatchers.IO) {
                        try {
                            loader.loadPage(page)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) { if (!closed && !disposed) showError() }
                        }
                    }
                    page.statusFlow.collectLatest { state ->
                        when (state) {
                            Page.State.READY -> load()
                            Page.State.ERROR -> showError()
                            else -> {
                                ready = false
                                placeholder.isVisible = true
                                placeholder.alpha = 1f
                                error?.isVisible = false
                            }
                        }
                    }
                }
            }
        }

        private fun fitImage() {
            if (!ready || closed || disposed) return
            image.children.filterIsInstance<SubsamplingScaleImageView>().forEach { view ->
                if (view.isReady) {
                    val size = SpreadLayout.displaySize(
                        dimensions[page],
                        view.sWidth to view.sHeight,
                        view.imageRotation.rotation % 180 != 0,
                    ) ?: return@forEach
                    // Native views retain their old zoom when resized. The spread owns
                    // zoom, so every child must refit to its new frame after cropping/rotation.
                    view.setScaleAndCenter(view.minScale, PointF(size.first / 2f, size.second / 2f))
                }
            }
        }

        private suspend fun load() {
            try {
                val data = withContext(Dispatchers.IO) {
                    val cached = File.createTempFile("spread-", ".page", activity.cacheDir)
                    temporaryFiles += cached
                    try {
                        requireNotNull(page.stream).invoke().use { input ->
                            cached.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                var size = 0L
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    size += count
                                    require(size <= 64L * 1024 * 1024)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(cached.absolutePath, bounds)
                        val animated = cached.source().buffer().use { ImageUtil.isAnimatedAndSupported(it) }
                        Triple(cached, animated, bounds.outWidth to bounds.outHeight)
                    } catch (error: Throwable) {
                        cached.delete()
                        temporaryFiles -= cached
                        throw error
                    }
                }
                if (closed || disposed) {
                    data.first.delete()
                    temporaryFiles -= data.first
                    return
                }
                val previousFile = file
                file = data.first
                error?.isVisible = false
                val old = dimensions.put(page, data.third)
                image.pageBackground = if (!data.second && preferences.readerTheme().get() == 3) {
                    withContext(Dispatchers.IO) {
                        data.first.inputStream().use { ImageUtil.chooseBackground(activity, it) }
                    }
                } else {
                    null
                }
                if (closed || disposed) return
                animated = data.second
                refreshImage()
                previousFile?.delete()
                previousFile?.let { temporaryFiles -= it }
                val wasSingle = old?.let { SpreadGrouping.standalone(it.first, it.second) } == true
                val nowSingle = SpreadGrouping.standalone(data.third.first, data.third.second)
                if (wasSingle != nowSingle) {
                    recycler.post { if (!disposed) regroup() }
                } else if (old != data.third) {
                    recycler.post {
                        if (!disposed) resizeVisible()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!closed) showError()
            }
        }

        fun refreshImage() {
            val imageFile = file?.takeIf { it.exists() } ?: return
            if (closed || disposed) return
            image.setImage(
                imageFile,
                animated,
                ReaderPageImageView.Config(
                    zoomDuration = ModernMotion.PAGE_MILLIS,
                    // Integer rounding may leave a fractional letterbox even with the
                    // correct aspect ratio. Fit each page to its adjoining edge exactly.
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                    cropBorders =
                    (if (continuous) preferences.cropBordersWebtoon() else preferences.cropBorders()).get() &&
                        ReadingTogetherManager.existing()?.controller?.state?.value?.active != true,
                ),
            )
        }

        private fun showError() {
            ready = false
            if (error == null) {
                error = MaterialButton(activity).apply {
                    text = activity.stringResource(MR.strings.action_retry)
                    setOnClickListener {
                        if (page.status == Page.State.READY && file?.exists() == true) {
                            refreshImage()
                        } else {
                            page.chapter.pageLoader?.retryPage(page)
                        }
                    }
                }
                addView(error, LayoutParams(-2, -2, Gravity.CENTER))
            }
            error?.isVisible = true
            publishVisible()
        }
        fun close() {
            closed = true
            job?.cancel()
            job = null
            placeholder.animate().cancel()
            image.onImageLoaded = null
            image.onImageLoadError = null
            image.onTouchObserved = null
            image.recycle()
            file?.delete()
            file?.let { temporaryFiles -= it }
            file = null
        }
    }

    /** Shared transform keeps both original image surfaces and their ink in the same spread. */
    private inner class ZoomFrame : FrameLayout(activity) {
        private var zoom = 1f
        private var xShift = 0f
        private var yShift = 0f
        private var previousX = 0f
        private var previousY = 0f
        private var animator: android.animation.ValueAnimator? = null
        private var multiTouch = false
        private var buttonGesture = false
        private var noteGesture = false
        val zoomed get() = zoom > 1.01f
        private val scale = ScaleGestureDetector(
            activity,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleTo((zoom * detector.scaleFactor).coerceIn(1f, 5f), detector.focusX, detector.focusY)
                    transform()
                    return true
                }
            },
        ).apply { isQuickScaleEnabled = false }
        private val gestures = GestureDetector(
            activity,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (multiTouch) return false
                    if (zoomed) {
                        activity.toggleMenu()
                        return true
                    }
                    val mode = if (continuous) {
                        preferences.navigationModeWebtoon().get()
                    } else {
                        preferences.navigationModePager().get()
                    }
                    val navigator = when (mode) {
                        1 -> LNavigation()
                        2 -> KindlishNavigation()
                        3 -> EdgeNavigation()
                        4 -> RightAndLeftNavigation()
                        5 -> DisabledNavigation()
                        else -> if (verticalNavigation) LNavigation() else RightAndLeftNavigation()
                    }.apply {
                        invertMode =
                            if (continuous) {
                                preferences.webtoonNavInverted().get()
                            } else {
                                preferences.pagerNavInverted().get()
                            }
                    }
                    when (navigator.getAction(PointF(e.x / width.coerceAtLeast(1), e.y / height.coerceAtLeast(1)))) {
                        NavigationRegion.NEXT -> navigate(1)
                        NavigationRegion.PREV -> navigate(-1)
                        NavigationRegion.RIGHT -> navigate(if (rtl) -1 else 1)
                        NavigationRegion.LEFT -> navigate(if (rtl) 1 else -1)
                        NavigationRegion.MENU -> activity.toggleMenu()
                    }
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (continuous && !preferences.webtoonDoubleTapZoomEnabled().get()) return false
                    val start = zoom
                    val target = if (zoomed) 1f else 2f
                    val focusX = e.x
                    val focusY = e.y
                    animator?.cancel()
                    animator = android.animation.ValueAnimator.ofFloat(start, target).apply {
                        duration = (ModernMotion.PAGE_MILLIS * activity.animatorDurationScale).toLong()
                        addUpdateListener {
                            scaleTo(it.animatedValue as Float, focusX, focusY)
                            transform()
                        }
                        start()
                    }
                    return true
                }
                override fun onLongPress(e: MotionEvent) {
                    if (multiTouch || !preferences.readWithLongTap().get()) return
                    active?.let(activity::onPageLongTap)
                }
            },
        )

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                multiTouch = false
                previousX = event.x
                previousY = event.y
                animator?.cancel()
                val hit = Rect()
                fun buttonAt(view: View): Boolean {
                    if (view is MaterialButton &&
                        view.isVisible &&
                        view.getGlobalVisibleRect(hit) &&
                        hit.contains(event.rawX.toInt(), event.rawY.toInt())
                    ) {
                        return true
                    }
                    return (view as? ViewGroup)?.children?.any(::buttonAt) == true
                }
                buttonGesture = recycler.children.any(::buttonAt)
                noteGesture = ReadingTogetherManager.existing()?.tools?.value?.noteDraft != null
                recycler.children.forEach { child ->
                    val holder = recycler.getChildViewHolder(child) as? Holder
                    holder?.surfaces?.firstOrNull {
                        it.getGlobalVisibleRect(hit) && hit.contains(event.rawX.toInt(), event.rawY.toInt())
                    }?.let {
                        active = it.page
                        pending = it.page
                        publishVisible()
                    }
                }
            }
            if (buttonGesture) return super.dispatchTouchEvent(event)
            val drawing = ReadingTogetherManager.existing()?.tools?.value?.drawing == true
            if (event.pointerCount > 1) multiTouch = true
            scale.onTouchEvent(event)
            if ((drawing || noteGesture) && !multiTouch && !scale.isInProgress) {
                return super.dispatchTouchEvent(event)
            }
            gestures.onTouchEvent(event)
            if (zoomed && event.pointerCount == 1 && event.actionMasked == MotionEvent.ACTION_MOVE) {
                xShift += event.x - previousX
                yShift += event.y - previousY
                transform()
            }
            previousX = event.x
            previousY = event.y
            if (multiTouch || zoomed) {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                return true
            }
            return super.dispatchTouchEvent(event)
        }

        private fun scaleTo(target: Float, focusX: Float, focusY: Float) {
            val ratio = target / zoom
            xShift = focusX - width / 2f - (focusX - width / 2f - xShift) * ratio
            yShift = focusY - height / 2f - (focusY - height / 2f - yShift) * ratio
            zoom = target
        }

        private fun transform() {
            val content = if (continuous) {
                null
            } else {
                layout.findViewByPosition(currentSlot)
                    ?.let { recycler.getChildViewHolder(it) as? Holder }?.contentSize
            }
            val xLimit = SpreadLayout.panLimit(width, content?.first ?: width, zoom)
            val yLimit = SpreadLayout.panLimit(height, content?.second ?: height, zoom)
            xShift = xShift.coerceIn(-xLimit, xLimit)
            yShift = yShift.coerceIn(-yLimit, yLimit)
            recycler.scaleX = zoom
            recycler.scaleY = zoom
            recycler.translationX = xShift
            recycler.translationY = yShift
            if (!zoomed && regroupPending) recycler.post { if (!disposed) regroup() }
        }
        fun refit() = transform()
        fun reset() {
            animator?.cancel()
            zoom = 1f
            xShift = 0f
            yShift = 0f
            transform()
        }
        fun stop() {
            animator?.cancel()
        }
    }
}
