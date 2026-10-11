package eu.kanade.tachiyomi.ui.reader.viewer.guided

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nyanime.panels.GuidedLocation
import nyanime.panels.GuidedPageRecord
import nyanime.panels.GuidedPanelOrder
import nyanime.panels.GuidedReadingStore
import nyanime.panels.PanelAnalysis
import nyanime.panels.PanelAnalyzer
import nyanime.panels.PanelDirection
import nyanime.panels.PanelGeometry
import nyanime.panels.PanelPageRef
import nyanime.panels.PanelRect
import okio.buffer
import okio.source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Uses original page coordinates for subsampling, rooms, ink, bookmarks and corrections. */
class GuidedViewer(
    private val activity: ReaderActivity,
    private val model: ReaderViewModel,
    val readingMode: Int,
) : Viewer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writes = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, _ ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        activity.applicationContext,
                        R.string.guided_save_failed,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            },
    )
    private val preferences: ReaderPreferences = Injekt.get()
    val titleId = requireNotNull(model.manga).id
    private var direction by mutableStateOf(model.getGuidedPanelOrder())
    val verticalNavigation = ReadingMode.fromPreference(readingMode).direction == ReadingMode.Direction.Vertical
    private val analyzer by lazy { PanelAnalyzer(activity) }
    private var analyzerStarted = false
    private val store by lazy { GuidedReadingStore.get(activity.applicationContext) }
    private var chapters: ViewerChapters? = null
    private var page: ReaderPage? = null
    private var pageJob: Job? = null
    private var prefetchJob: Job? = null
    private val files = ConcurrentHashMap.newKeySet<File>()
    private var hash: String? = null
    private var automatic = listOf(PanelRect.PAGE)
    private var panels by mutableStateOf(listOf(PanelRect.PAGE))
    private var panel by mutableIntStateOf(0)
    private var overview by mutableStateOf(true)
    private var analyzing by mutableStateOf(true)
    private var failure by mutableStateOf(false)
    private var editing by mutableStateOf(false)
    private var draft by mutableStateOf(listOf<PanelRect>())
    private var selected by mutableIntStateOf(0)
    private var disposed = false
    private var ready by mutableStateOf(false)
    private var focusSupported by mutableStateOf(true)
    private var currentFile: File? = null
    private var resetDraft = false
    private var multiTouch = false
    private var swipeFromPanel = false
    private var navigationEntry: Int? = null
    private var restored: GuidedPageRecord? = null
    private var resumeLocation: GuidedLocation? = null
    private var corrections by mutableStateOf<List<PanelRect>?>(null)
    val hasCustomPanelOrder: Boolean get() = corrections != null
    private var savedAt = 0L
    var controlsBottomInset by mutableStateOf(96.dp)
        private set
    private var bottomSystemInset = 0
    private val image = ReaderPageImageView(activity)
    private val overlay = PanelOverlay()
    private val stage = FrameLayout(activity).apply {
        addView(image, FrameLayout.LayoutParams(-1, -1))
        addView(this@GuidedViewer.overlay, FrameLayout.LayoutParams(-1, -1))
    }
    private val bar = ComposeView(activity).apply {
        setComposeContent(legacyManga = true) { Controls() }
    }
    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(
            if (preferences.readerTheme().get() ==
                0
            ) {
                android.graphics.Color.WHITE
            } else {
                android.graphics.Color.BLACK
            },
        )
        addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(bar, LinearLayout.LayoutParams(-1, -2))
    }
    private val gestures = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                val current = image.guidedLocation(panel, overview)
                val fitted = panels[panel].padded()
                swipeFromPanel = !overview &&
                    !editing &&
                    current != null &&
                    image.guidedAtPanelScale(panels[panel]) &&
                    abs(current.centerX - fitted.centerX) <= fitted.width * .12f &&
                    abs(current.centerY - fitted.centerY) <= fitted.height * .12f
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (multiTouch || editing) return false
                val point = image.guidedSourcePoint(e.x, e.y)
                if (overview && point != null) {
                    val index = panels.indices.filter { panels[it].contains(point.x, point.y) }
                        .minByOrNull { panels[it].area }
                    if (index != null) {
                        panel = index
                        overview = false
                        focus()
                        return true
                    }
                }
                val navigator = navigation()
                when (navigator.getAction(PointF(e.x / image.width, e.y / image.height))) {
                    ViewerNavigation.NavigationRegion.NEXT -> step(1)
                    ViewerNavigation.NavigationRegion.PREV -> step(-1)
                    ViewerNavigation.NavigationRegion.LEFT -> step(if (direction == PanelDirection.RTL) 1 else -1)
                    ViewerNavigation.NavigationRegion.RIGHT -> step(if (direction == PanelDirection.RTL) -1 else 1)
                    else -> activity.toggleMenu()
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean = !editing && !multiTouch && image.guidedDoubleTap(e)
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || multiTouch || overview || editing || !swipeFromPanel) return false
                // Zooming/panning stays personal. Swipe navigation is used only near the fitted panel scale.
                val distance = if (verticalNavigation) e2.y - e1.y else e2.x - e1.x
                if (abs(distance) < 64 * activity.resources.displayMetrics.density) return false
                step(GuidedPanelOrder.swipeDelta(distance, verticalNavigation, direction))
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (!multiTouch && !editing && preferences.readWithLongTap().get()) page?.let(activity::onPageLongTap)
            }
        },
    )

    init {
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateControlsInset() }
        image.onViewClicked = {} // One gesture owner; preserve subsampling's pinch and pan.
        image.onImageLoaded = {
            ready = true
            if (!analyzing) focus(false, resumeLocation)
        }
        image.onImageLoadError = {
            failure = true
            analyzing = false
        }
        image.onSourceTransformChanged = { overlay.invalidate() }
        image.onTouchObserved = { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
            if (event.pointerCount > 1) multiTouch = true
            gestures.onTouchEvent(event)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            bottomSystemInset = bars.bottom
            updateControlsInset()
            insets
        }
        model.state.map { it.menuVisible }.distinctUntilChanged().onEach {
            bar.visibility = if (it) View.INVISIBLE else View.VISIBLE
            bar.isEnabled = !it
            bar.importantForAccessibility = if (it) {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
        }.launchIn(scope)
    }

    override fun getView(): View = root

    // ViewModels survive rotation; a viewer bound to the destroyed activity does not.
    fun canReuse(owner: ReaderActivity): Boolean = activity === owner && !disposed

    fun updatePanelOrder(next: PanelDirection) {
        if (next == direction || disposed) return
        val location = currentLocation()
        direction = next
        automatic = PanelGeometry.order(automatic, direction)
        if (editing && resetDraft) {
            val anchor = draft.getOrNull(selected)
            draft = PanelGeometry.order(draft, direction)
            selected = anchor?.let { draft.indexOf(it).coerceAtLeast(0) } ?: 0
        }
        if (corrections == null && !editing && !analyzing) {
            panels = PanelGeometry.order(panels, direction)
            val restoredLocation = GuidedPanelOrder.restore(location, panels)
            panel = restoredLocation?.panel ?: 0
            overview = restoredLocation?.overview ?: overview
            resumeLocation = restoredLocation
            // Reordering keeps the exact focused rectangle and its personal zoom.
            // Only an ambiguous old bookmark needs to return to the whole page.
            if (location?.overview != overview && ready) focus(location = restoredLocation)
        }
        overlay.invalidate()
        if (!analyzing || corrections != null) save()
    }
    private fun updateControlsInset() {
        controlsBottomInset = ((bar.height + bottomSystemInset) / activity.resources.displayMetrics.density).dp
    }
    override fun setChapters(chapters: ViewerChapters) {
        val previous = this.chapters
        chapters.ref()
        this.chapters = chapters
        previous?.unref()
        if (page?.chapter != chapters.currChapter) {
            val pages = chapters.currChapter.pages.orEmpty()
            pages.getOrNull(
                chapters.currChapter.requestedPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
            )?.let(::moveToPage)
        }
    }

    private fun navigation(): ViewerNavigation {
        val vertical = verticalNavigation
        val value = if (vertical) preferences.navigationModeWebtoon().get() else preferences.navigationModePager().get()
        return when (value) {
            1 -> LNavigation()
            2 -> KindlishNavigation()
            3 -> EdgeNavigation()
            4 -> RightAndLeftNavigation()
            5 -> DisabledNavigation()
            else -> if (vertical) LNavigation() else RightAndLeftNavigation()
        }.apply {
            invertMode =
                if (vertical) preferences.webtoonNavInverted().get() else preferences.pagerNavInverted().get()
        }
    }

    override fun moveToPage(page: ReaderPage) {
        if (this.page === page) return
        val entry = navigationEntry
        navigationEntry = null
        save()
        pageJob?.cancel()
        prefetchJob?.cancel()
        image.recycle()
        currentFile?.let { old ->
            writes.launch {
                old.delete()
                files -= old
            }
        }
        currentFile = null
        ready = false
        focusSupported = true
        this.page = page
        hash = null
        restored = null
        resumeLocation = null
        corrections = null
        panels = listOf(PanelRect.PAGE)
        panel = 0
        overview = true
        editing = false
        analyzing = true
        failure = false
        activity.onPageSelected(page, false)
        image.bindReadingPage(page, model.manga)
        val loader = page.chapter.pageLoader ?: return
        pageJob = scope.launch {
            launch(Dispatchers.IO) { loader.loadPage(page) }
            page.statusFlow.collectLatest { status ->
                if (status == Page.State.ERROR) {
                    analyzing = false
                    failure = true
                }
                if (status != Page.State.READY) return@collectLatest
                try {
                    val prepared = prepare(page)
                    ensureActive()
                    hash = prepared.second
                    currentFile = prepared.first
                    restored = if (model.guidedPersistenceAllowed()) {
                        withContext(Dispatchers.IO) {
                            store.get(reference(page))?.takeIf { it.imageHash == prepared.second }
                        }
                    } else {
                        null
                    }
                    corrections = restored?.corrections
                    val location = restored?.location.takeIf { entry == null }
                    resumeLocation = null // Restore only after mapping the bookmark to the current order.
                    val animated = withContext(Dispatchers.IO) {
                        prepared.first.source().buffer().use {
                            tachiyomi.core.common.util.system.ImageUtil.isAnimatedAndSupported(it)
                        }
                    }
                    if (animated) {
                        focusSupported = false
                        analyzing = false
                        image.setGuidedAnimatedImage(prepared.first)
                        return@collectLatest
                    }
                    image.setGuidedImage(prepared.first)
                    corrections?.let { manual ->
                        panels = manual
                        resumeLocation = GuidedPanelOrder.restore(location, panels)
                        panel =
                            (if (entry == -1) panels.lastIndex else resumeLocation?.panel ?: 0).coerceIn(panels.indices)
                        overview = resumeLocation?.overview ?: false
                        analyzing = false
                        if (ready) focus(location = resumeLocation)
                    }
                    val result = analyze(prepared.first, prepared.second)
                    ensureActive()
                    // The selection may have changed while inference was in flight.
                    automatic = PanelGeometry.order(result.panels, direction)
                    // A saved correction is already interactive while automatic recognition runs.
                    // Its current selection must not jump back when that background work finishes.
                    if (corrections == null && !editing) {
                        panels = automatic
                        resumeLocation = GuidedPanelOrder.restore(location, panels)
                        panel =
                            (if (entry == -1) panels.lastIndex else resumeLocation?.panel ?: 0).coerceIn(panels.indices)
                        overview = resumeLocation?.overview ?: (panels.size == 1)
                    }
                    analyzing = false
                    if (ready && corrections == null && !editing) focus(location = resumeLocation)
                    prefetch(page)
                } catch (_: TimeoutCancellationException) {
                    fallback()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    fallback()
                } catch (_: OutOfMemoryError) {
                    analyzing = false
                    failure = true
                    overview = true
                }
            }
        }
        val pages = page.chapter.pages.orEmpty()
        if (pages.size - page.index < 5) chapters?.nextChapter?.let(activity::requestPreloadChapter)
    }

    private fun fallback() {
        analyzing = false
        failure = true
        if (corrections == null) {
            panels = listOf(PanelRect.PAGE)
            overview = true
            if (ready) focus(false)
        }
        overlay.invalidate()
    }

    private suspend fun prepare(page: ReaderPage): Pair<File, String> = withContext(Dispatchers.IO) {
        val file = File.createTempFile("guided-", ".page", activity.cacheDir)
        files += file
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = requireNotNull(page.stream).invoke()
            input.use { stream ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 64L * 1024 * 1024)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            file to digest.digest().joinToString("") { "%02x".format(it) }
        } catch (error: Throwable) {
            file.delete()
            files -= file
            throw error
        }
    }

    private suspend fun analyze(file: File, hash: String): PanelAnalysis {
        analyzerStarted = true
        val engine = withContext(Dispatchers.IO) { analyzer }
        return engine.analyze(file, hash, direction)
    }

    private fun prefetch(current: ReaderPage) {
        val next = current.chapter.pages?.getOrNull(current.index + 1) ?: return
        prefetchJob = scope.launch {
            var file: File? = null
            try {
                launch(Dispatchers.IO) { current.chapter.pageLoader?.loadPage(next) }
                next.statusFlow.first { it == Page.State.READY || it == Page.State.ERROR }
                if (next.status != Page.State.READY) return@launch
                val prepared = prepare(next)
                file = prepared.first
                analyze(prepared.first, prepared.second)
            } catch (
                error: CancellationException,
            ) {
                throw error
            } catch (
                _: Exception,
            ) { /* Optional prefetch must not affect the current page. */ } finally {
                file?.let {
                    files -= it
                    withContext(NonCancellable + Dispatchers.IO) { it.delete() }
                }
            }
        }
    }

    private fun reference(page: ReaderPage): PanelPageRef {
        val title = requireNotNull(model.manga)
        return PanelPageRef(title.source, title.url, page.chapter.chapter.url, page.index)
    }

    private fun currentLocation(): GuidedLocation? = image.guidedLocation(panel, overview)?.copy(
        focusedPanel = if (overview) null else panels.getOrNull(panel),
    )

    private fun save(corrections: List<PanelRect>? = null, updateCorrections: Boolean = false) {
        val current = page ?: return
        val fingerprint = hash ?: return
        if (!model.guidedPersistenceAllowed() || !ready || editing) return
        val ref = reference(current)
        val location = currentLocation() ?: return
        if (updateCorrections) this.corrections = corrections
        val correctionSnapshot = this.corrections
        // A bookmark restored from another device may have a clock ahead of this phone.
        val previous = maxOf(savedAt, restored?.changedAt ?: 0)
        savedAt = maxOf(if (previous == Long.MAX_VALUE) previous else previous + 1, System.currentTimeMillis())
        val timestamp = savedAt
        writes.launch {
            store.put(
                GuidedPageRecord(
                    ref,
                    fingerprint,
                    corrections = correctionSnapshot,
                    location = location,
                    changedAt = timestamp,
                ),
            )
            eu.kanade.tachiyomi.data.cloud.sync.CloudSyncManager.progressSaved()
        }
    }

    fun checkpoint() = save()

    private fun focus(animate: Boolean = true, location: GuidedLocation? = null) {
        if (!ready) return
        image.focusPanel(if (overview || editing) PanelRect.PAGE else panels[panel], animate, location)
        overlay.transition(animate)
        overlay.invalidate()
        // Whole page fallback is a genuine last-page view. An analyzed page needs its final panel.
        page?.let {
            activity.onPageSelected(
                it,
                !analyzing && !editing && (panels.size == 1 || !overview && panel == panels.lastIndex),
            )
        }
    }

    private fun step(delta: Int) {
        if (editing || !ready) return
        save()
        val target = panel + delta
        if (!overview && target in panels.indices) {
            panel = target
            focus()
        } else if (overview && panels.size > 1) {
            panel = if (delta > 0) 0 else panels.lastIndex
            overview = false
            focus()
        } else {
            val current = page ?: return
            val next = current.chapter.pages?.getOrNull(current.index + delta)
            if (next != null) {
                navigationEntry = if (delta < 0) -1 else 0
                moveToPage(next)
            } else {
                scope.launch {
                    if (if (delta > 0) chapters?.nextChapter == null else chapters?.prevChapter == null) return@launch
                    navigationEntry = if (delta < 0) -1 else 0
                    if (delta > 0) model.loadNextChapter() else model.loadPreviousChapter()
                }
            }
        }
    }

    private fun edit() {
        if (!ready || analyzing) return
        save()
        draft = panels.toList()
        resetDraft = false
        selected = panel.coerceIn(draft.indices)
        bar.doOnNextLayout { if (!disposed) focus() }
        editing = true
    }

    @Composable private fun Controls() {
        val compact = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.15f
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (editing) {
                    Text(
                        stringResource(R.string.guided_editor_hint),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(draft) { index, _ ->
                            var movement by remember { mutableFloatStateOf(0f) }
                            FilterChip(
                                selected = index == selected,
                                onClick = {
                                    selected = index
                                    overlay.invalidate()
                                },
                                label = { Text((index + 1).toString()) },
                                modifier = Modifier.pointerInput(index, draft.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { selected = index },
                                        onDragEnd = { movement = 0f },
                                        onDragCancel = { movement = 0f },
                                    ) { change, distance ->
                                        change.consume()
                                        movement += distance.x
                                        if (abs(movement) > 44.dp.toPx()) {
                                            val target = (
                                                selected +
                                                    if (movement >
                                                        0
                                                    ) {
                                                        1
                                                    } else {
                                                        -1
                                                    }
                                                ).coerceIn(draft.indices)
                                            if (target != selected) {
                                                val from = selected
                                                draft = draft.toMutableList().apply { add(target, removeAt(from)) }
                                                resetDraft = false
                                                selected = target
                                                overlay.invalidate()
                                            }
                                            movement = 0f
                                        }
                                    }
                                },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = {
                            if (draft.size <
                                256
                            ) {
                                draft = draft + PanelRect(.25f, .25f, .75f, .75f)
                                resetDraft = false
                                selected =
                                    draft.lastIndex
                                overlay.invalidate()
                            }
                        }) { Icon(Icons.Outlined.Add, stringResource(R.string.guided_add)) }
                        IconButton(onClick = {
                            if (draft.size >
                                1
                            ) {
                                draft = draft.filterIndexed { i, _ -> i != selected }
                                resetDraft = false
                                selected =
                                    selected.coerceIn(draft.indices)
                                overlay.invalidate()
                            }
                        }, enabled = draft.size > 1) {
                            Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.guided_delete))
                        }
                        TextButton(onClick = {
                            draft = automatic
                            resetDraft = true
                            selected = 0
                            overlay.invalidate()
                        }) {
                            Text(stringResource(R.string.guided_reset))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            bar.doOnNextLayout { if (!disposed) focus() }
                            editing = false
                        }) { Text(stringResource(android.R.string.cancel)) }
                        FilledTonalButton(onClick = {
                            panels = draft
                            panel = selected
                            bar.doOnNextLayout {
                                if (!disposed) {
                                    focus()
                                    save(panels.takeUnless { resetDraft }, true)
                                }
                            }
                            editing = false
                            overview = false
                        }) { Text(stringResource(R.string.guided_save)) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = { step(-1) }, enabled = ready) {
                            Icon(
                                if (direction ==
                                    PanelDirection.RTL
                                ) {
                                    Icons.AutoMirrored.Outlined.NavigateNext
                                } else {
                                    Icons.AutoMirrored.Outlined.NavigateBefore
                                },
                                stringResource(R.string.guided_previous),
                            )
                        }
                        Column(Modifier.weight(1f).clickable { activity.toggleMenu() }) {
                            Text(
                                if (analyzing) {
                                    stringResource(
                                        if (compact) R.string.guided_compact_analyzing else R.string.guided_analyzing,
                                    )
                                } else if (overview) {
                                    stringResource(
                                        if (compact) R.string.guided_compact_page else R.string.guided_whole_page,
                                    )
                                } else {
                                    stringResource(
                                        if (compact) R.string.guided_compact_counter else R.string.guided_counter,
                                        panel + 1,
                                        panels.size,
                                    )
                                },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (failure) {
                                Text(
                                    stringResource(R.string.guided_fallback),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (preferences.showPageNumber().get()) {
                                Text(
                                    stringResource(
                                        if (compact) {
                                            R.string.guided_compact_page_counter
                                        } else {
                                            R.string.guided_page_counter
                                        },
                                        (page?.index ?: 0) + 1,
                                        page?.chapter?.pages?.size ?: 0,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        if (failure && !ready) {
                            IconButton(onClick = {
                                failure = false
                                analyzing = true
                                page?.let { it.chapter.pageLoader?.retryPage(it) }
                            }) {
                                Icon(Icons.Outlined.Refresh, stringResource(R.string.guided_retry))
                            }
                        }
                        IconButton(onClick = {
                            save()
                            overview = !overview
                            focus()
                        }, enabled = ready && !analyzing && focusSupported) {
                            Icon(
                                if (overview) Icons.Outlined.CenterFocusStrong else Icons.Outlined.Fullscreen,
                                stringResource(if (overview) R.string.guided_focus else R.string.guided_whole_page),
                            )
                        }
                        IconButton(onClick = ::edit, enabled = ready && !analyzing && focusSupported) {
                            Icon(Icons.Outlined.Edit, stringResource(R.string.guided_edit))
                        }
                        IconButton(onClick = { step(1) }, enabled = ready) {
                            Icon(
                                if (direction ==
                                    PanelDirection.RTL
                                ) {
                                    Icons.AutoMirrored.Outlined.NavigateBefore
                                } else {
                                    Icons.AutoMirrored.Outlined.NavigateNext
                                },
                                stringResource(R.string.guided_next),
                            )
                        }
                    }
                }
            }
        }
    }

    private inner class PanelOverlay : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var maskRect = PanelRect.PAGE
        private var maskAlpha = 0f
        private var motion: ValueAnimator? = null
        fun transition(animate: Boolean) {
            motion?.cancel()
            val start = maskRect
            val startAlpha = maskAlpha
            val target = (if (overview || editing) PanelRect.PAGE else panels[panel]).padded()
            val targetAlpha = if (overview || editing || !focusSupported) 0f else 1f
            val duration = (ModernMotion.PAGE_MILLIS * activity.animatorDurationScale).toLong()
            fun update(f: Float) {
                fun mix(a: Float, b: Float) = a + (b - a) * f
                maskRect = PanelRect(
                    mix(start.left, target.left),
                    mix(start.top, target.top),
                    mix(start.right, target.right),
                    mix(start.bottom, target.bottom),
                )
                maskAlpha = mix(startAlpha, targetAlpha)
                invalidate()
            }
            if (!animate || duration == 0L) {
                update(1f)
                return
            }
            motion = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                setInterpolator { t -> if (t < .5f) 2 * t * t else 1 - 2 * (1 - t) * (1 - t) }
                addUpdateListener { update(it.animatedValue as Float) }
                start()
            }
        }
        fun close() {
            motion?.cancel()
        }
        private var origin: PointF? = null
        private var initial: PanelRect? = null
        private var corner = -1
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!ready) return
            if (maskAlpha > 0f) {
                val a = image.guidedViewPoint(maskRect.left, maskRect.top)
                val b = image.guidedViewPoint(maskRect.right, maskRect.bottom)
                if (a != null && b != null) {
                    val left = a.x.coerceIn(0f, width.toFloat())
                    val right = b.x.coerceIn(left, width.toFloat())
                    val top = a.y.coerceIn(0f, height.toFloat())
                    val bottom = b.y.coerceIn(top, height.toFloat())
                    paint.style = Paint.Style.FILL
                    paint.color =
                        if (preferences.readerTheme().get() ==
                            0
                        ) {
                            android.graphics.Color.WHITE
                        } else {
                            android.graphics.Color.BLACK
                        }
                    paint.alpha = (maskAlpha * 255).toInt()
                    canvas.drawRect(0f, 0f, width.toFloat(), top, paint)
                    canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), paint)
                    canvas.drawRect(0f, top, left, bottom, paint)
                    canvas.drawRect(right, top, width.toFloat(), bottom, paint)
                    paint.alpha = 255
                }
            }
            if (!overview && !editing) return
            val values = if (editing) draft else panels
            values.forEachIndexed { index, rect ->
                val a = image.guidedViewPoint(rect.left, rect.top) ?: return@forEachIndexed
                val b = image.guidedViewPoint(rect.right, rect.bottom) ?: return@forEachIndexed
                paint.color = if (editing && index == selected) 0xffffc857.toInt() else 0xffe8e8e8.toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * resources.displayMetrics.density
                canvas.drawRect(a.x, a.y, b.x, b.y, paint)
                paint.style = Paint.Style.FILL
                paint.textSize = 16 * resources.displayMetrics.scaledDensity
                canvas.drawRoundRect(
                    a.x,
                    a.y,
                    a.x + 30 * resources.displayMetrics.density,
                    a.y + 28 * resources.displayMetrics.density,
                    5f,
                    5f,
                    paint,
                )
                paint.color = android.graphics.Color.BLACK
                canvas.drawText(
                    (index + 1).toString(),
                    a.x + 6 * resources.displayMetrics.density,
                    a.y + 20 * resources.displayMetrics.density,
                    paint,
                )
                if (editing && index == selected) {
                    paint.color = 0xffffc857.toInt()
                    listOf(a, PointF(b.x, a.y), b, PointF(a.x, b.y)).forEach {
                        canvas.drawCircle(it.x, it.y, 7 * resources.displayMetrics.density, paint)
                    }
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!editing) return false
            val point = image.guidedSourcePoint(event.x, event.y) ?: return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val nearest = draft.indices.minByOrNull { index ->
                        val rect = draft[index]
                        val points = listOf(
                            rect.left to rect.top,
                            rect.right to rect.top,
                            rect.right to rect.bottom,
                            rect.left to rect.bottom,
                        )
                        points.minOf { (x, y) ->
                            image.guidedViewPoint(x, y)?.let {
                                abs(it.x - event.x) +
                                    abs(it.y - event.y)
                            }
                                ?: Float.MAX_VALUE
                        }
                    }
                    corner = -1
                    if (nearest != null) {
                        val rect = draft[nearest]
                        val points = listOf(
                            rect.left to rect.top,
                            rect.right to rect.top,
                            rect.right to rect.bottom,
                            rect.left to rect.bottom,
                        )
                        val index = points.indices.minBy { i ->
                            image.guidedViewPoint(points[i].first, points[i].second)?.let {
                                abs(it.x - event.x) +
                                    abs(it.y - event.y)
                            }
                                ?: Float.MAX_VALUE
                        }
                        val handle = image.guidedViewPoint(points[index].first, points[index].second)
                        if (handle != null &&
                            abs(handle.x - event.x) + abs(handle.y - event.y) < 48 * resources.displayMetrics.density
                        ) {
                            selected = nearest
                            corner = index
                        } else {
                            draft.indexOfLast { it.contains(point.x, point.y) }.takeIf { it >= 0 }?.let {
                                selected =
                                    it
                            }
                        }
                    }
                    origin = point
                    initial = draft.getOrNull(selected)
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val old = initial ?: return true
                    val start = origin ?: return true
                    val next = if (corner >= 0) {
                        PanelGeometry.resize(old, point.x, point.y, corner)
                    } else {
                        val dx = (point.x - start.x).coerceIn(-old.left, 1 - old.right)
                        val dy = (point.y - start.y).coerceIn(-old.top, 1 - old.bottom)
                        old.copy(
                            left = old.left + dx,
                            right = old.right + dx,
                            top = old.top + dy,
                            bottom =
                            old.bottom + dy,
                        )
                    }
                    draft = draft.toMutableList().apply { set(selected, next) }
                    resetDraft = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    origin = null
                    initial = null
                }
            }
            invalidate()
            return true
        }
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val delta = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (preferences.readWithVolumeKeys().get() &&
                !model.state.value.menuVisible
            ) {
                if (preferences.readWithVolumeKeysInverted().get()) -1 else 1
            } else {
                return false
            }
            KeyEvent.KEYCODE_VOLUME_UP -> if (preferences.readWithVolumeKeys().get() &&
                !model.state.value.menuVisible
            ) {
                if (preferences.readWithVolumeKeysInverted().get()) 1 else -1
            } else {
                return false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (direction == PanelDirection.RTL) -1 else 1
            KeyEvent.KEYCODE_DPAD_LEFT -> if (direction == PanelDirection.RTL) 1 else -1
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
            KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_UP) activity.toggleMenu()
                return true
            }
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_UP) step(delta)
        return true
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return false
        val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (scroll == 0f) return false
        step(if (scroll < 0) 1 else -1)
        return true
    }

    override fun destroy() {
        if (disposed) return
        save()
        disposed = true
        // Persistence already started; only obsolete loading/inference is canceled.
        pageJob?.cancel()
        prefetchJob?.cancel()
        if (analyzerStarted) analyzer.close()
        overlay.close()
        image.recycle()
        bar.disposeComposition()
        chapters?.unref()
        chapters = null
        val loading = pageJob
        val preloading = prefetchJob
        val pendingWrites = writes.coroutineContext[Job]?.children?.toList().orEmpty()
        writes.launch {
            pendingWrites.forEach { it.join() }
            loading?.join()
            preloading?.join()
            files.forEach { it.delete() }
            files.clear()
            scope.cancel()
            writes.cancel()
        }
    }
}
