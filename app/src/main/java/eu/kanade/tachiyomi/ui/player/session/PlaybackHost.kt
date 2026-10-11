package eu.kanade.tachiyomi.ui.player.session

import android.content.Intent
import android.graphics.Rect
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.Dialogs
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlayerSession
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.controls.GestureHandler
import eu.kanade.tachiyomi.ui.player.controls.PlayerControls
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One AndroidView survives all presentation changes within a window. Only its bounds change. */
@Composable
internal fun PlaybackHost(
    activity: BaseActivity,
    reader: Boolean = false,
    reservedBottomDp: Float = 0f,
) {
    val session by PlaybackSessions.current.collectAsState()
    val current = session ?: return
    androidx.compose.runtime.key(current.sessionId) {
        SessionHost(current, activity, reader, reservedBottomDp)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SessionHost(session: PlayerSession, activity: BaseActivity, reader: Boolean, reservedBottomDp: Float) {
    val presentation by session.presentation.collectAsState()
    val aspectRatio by remember(session) {
        session.snapshot.map { it.aspect }.distinctUntilChanged()
    }.collectAsState(initial = session.snapshot.value.aspect)
    val videoReplacement by session.replacementFrame.collectAsState()
    val windowFrame by session.windowFrame.collectAsState()
    val replacement = windowFrame ?: videoReplacement
    var retainedFrame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(replacement) {
        if (replacement != null) {
            retainedFrame = replacement
        } else {
            kotlinx.coroutines.delay(ModernMotion.EXIT_MILLIS.toLong())
            retainedFrame = null
        }
    }
    val live by session.live.collectAsState()
    val watchList = androidx.compose.foundation.lazy.rememberLazyListState()
    var episodeQuery by remember { mutableStateOf("") }
    var hideSeenEpisodes by remember { mutableStateOf(false) }
    val inPip by session.pip.collectAsState()
    val restricted by session.closeRestriction.collectAsState()
    val reduceMotion by Injekt.get<PlayerPreferences>().reduceMotion().collectAsState()
    val inputCoordinates = remember { PlaybackInputCoordinates() }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val miniLayout by session.miniLayout.collectAsState()
    val miniWidth = miniLayout.widthDp
    val corner = miniLayout.corner
    var draggingMini by remember { mutableStateOf(false) }
    var dismissingMini by remember { mutableStateOf(false) }
    var renderView by remember { mutableStateOf<MpvRenderView?>(null) }
    val videoAspectMode by Injekt.get<PlayerPreferences>().aspectState().collectAsState()
    var drag by remember { mutableFloatStateOf(0f) }
    var dragStart by remember { mutableStateOf<PlaybackPresentation?>(null) }
    var gestureStart by remember { mutableStateOf<PlaybackFrame?>(null) }
    var gestureAnchorY by remember { mutableFloatStateOf(0.5f) }
    val gestureVelocity = remember { androidx.compose.ui.input.pointer.util.VelocityTracker() }
    var settlingRevision by remember { mutableStateOf(0L) }

    val shown = if (reader) PlaybackPresentation.Mini else presentation
    BackHandler(!reader && shown != PlaybackPresentation.Mini && !inPip) { session.back() }

    CompositionLocalProvider(
        LocalPlayerSession provides session,
        eu.kanade.tachiyomi.ui.player.controls.components.LocalPlaybackControlMotion provides !reduceMotion,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val awaitingPortrait = !reader && !inPip && shown != PlaybackPresentation.Fullscreen && width > height
            val visualPresentation = if (awaitingPortrait) PlaybackPresentation.Fullscreen else shown
            LaunchedEffect(windowFrame, width, height) {
                val cover = windowFrame ?: return@LaunchedEffect
                if (width < height) {
                    // The native surface and app window use separate compositor layers. Keep a
                    // single bounded video frame above them until the portrait layout is committed.
                    androidx.compose.runtime.withFrameNanos { }
                    androidx.compose.runtime.withFrameNanos { }
                    session.windowFrameReady(cover)
                }
            }
            val top = WindowInsets.statusBarsIgnoringVisibility.getTop(density).toFloat()
            val navigation = WindowInsets.navigationBarsIgnoringVisibility.getBottom(density).toFloat()
            val keyboard = WindowInsets.ime.getBottom(density).toFloat()
            val bottom = max(keyboard, navigation + (reservedBottomDp + if (reader) 0f else 84f) * density.density)
            val wide = width / density.density >= 720f || width > height * 1.25f
            val aspect = aspectRatio.toFloat().takeIf { it.isFinite() && it in 0.1f..10f } ?: (16f / 9f)
            val full = PlaybackRect(0f, 0f, width, height)
            val page = PlaybackGeometry.page(width, height, top, navigation, aspect, wide)
            val mini = PlaybackGeometry.mini(width, height, top, bottom, density.density, aspect, miniWidth, corner)
            val dismissalCorner = if (corner == MiniCorner.TopStart || corner == MiniCorner.BottomStart) {
                MiniCorner.BottomStart
            } else {
                MiniCorner.BottomEnd
            }
            val dismissalMini = PlaybackGeometry.mini(
                width,
                height,
                top,
                bottom,
                density.density,
                aspect,
                miniWidth,
                dismissalCorner,
            )
            fun bounds(mode: PlaybackPresentation): PlaybackRect = when (mode) {
                PlaybackPresentation.Page -> page
                PlaybackPresentation.Fullscreen -> full
                PlaybackPresentation.Mini -> mini
            }
            val target = if (inPip) full else bounds(visualPresentation)
            fun frame(mode: PlaybackPresentation) = PlaybackFrame(
                if (inPip) full else bounds(mode),
                if (!reader && !inPip && mode == PlaybackPresentation.Page) 1f else 0f,
                if (!reader && !inPip && mode != PlaybackPresentation.Mini) 1f else 0f,
            )
            // Android animates window rotation. Pixel bounds from the previous window must not
            // start another resize after rotation, otherwise the surface jumps across the new layout.
            val motion = remember(width, height) {
                // Android's rotated screenshot is disabled: the incoming portrait host
                // owns one continuous resize instead of composing two unrelated animations.
                val arrivingFromFullscreen = windowFrame != null &&
                    !reader &&
                    !inPip &&
                    shown != PlaybackPresentation.Fullscreen &&
                    width < height &&
                    !reduceMotion
                PlaybackMotion(
                    frame(if (arrivingFromFullscreen) PlaybackPresentation.Fullscreen else visualPresentation),
                )
            }
            LaunchedEffect(
                target,
                shown,
                reduceMotion,
                inPip,
                settlingRevision,
                draggingMini,
                dismissingMini,
                awaitingPortrait,
            ) {
                // Keep the released fullscreen frame intact until Android supplies the portrait
                // window. Animating toward a temporary landscape page creates a clipped square
                // immediately before the system rotation and moves the video twice.
                if (awaitingPortrait) {
                    motion.stop()
                } else if (dragStart == null && !draggingMini && !dismissingMini) {
                    motion.moveTo(frame(shown), if (reduceMotion || inPip) 0 else ModernMotion.CONTINUOUS_RESIZE_MILLIS)
                    // Publish the final video rectangle, not the page/mini rectangle from
                    // before the animation. Avoid a binder update for every drag frame.
                    androidx.compose.runtime.withFrameNanos { }
                    session.refreshPipGeometry()
                }
            }
            val extent = max(page.height, height * 0.35f)

            fun gestureTravel(): Float = if (shown == PlaybackPresentation.Page && drag >= 0f) {
                PlaybackGeometry.pageDragTravel((gestureStart ?: motion.current).rect, dismissalMini, gestureAnchorY)
            } else {
                extent
            }

            fun onDrag(delta: Float) {
                if (dragStart == null) {
                    dragStart = shown
                    gestureStart = motion.current
                    scope.launch { motion.stop() }
                }
                drag = if (shown == PlaybackPresentation.Fullscreen) (drag + delta).coerceAtLeast(0f) else drag + delta
                val origin = gestureStart ?: motion.current
                if (shown == PlaybackPresentation.Fullscreen || drag < 0f) {
                    // Fullscreen dismissal is a translation under the finger. Resizing and
                    // portrait rotation belong to release, not to the interactive drag.
                    motion.follow(origin.copy(rect = origin.rect.copy(y = origin.rect.y + drag)))
                } else {
                    val fraction = (drag / gestureTravel()).coerceIn(0f, 1f)
                    motion.follow(
                        origin.interpolate(frame(PlaybackPresentation.Mini), fraction).copy(
                            rect = PlaybackGeometry.pageDrag(origin.rect, dismissalMini, gestureAnchorY, drag),
                        ),
                    )
                }
            }
            fun finishDrag(cancelled: Boolean) {
                val origin = dragStart ?: return
                val travel = gestureTravel()
                val result = if (cancelled) {
                    origin
                } else {
                    PlaybackGeometry.settlePresentation(
                        origin,
                        drag / travel,
                        gestureVelocity.calculateVelocity().y / travel,
                    )
                }
                dragStart = null
                gestureStart = null
                drag = 0f
                if (result == PlaybackPresentation.Mini) {
                    session.miniLayout.value = miniLayout.copy(corner = dismissalCorner)
                }
                session.present(result)
                settlingRevision++
            }

            if (!reader && !inPip) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = motion.current.expanded
                    }.background(MaterialTheme.colorScheme.background),
                )
                run {
                    if (live != null) {
                        PlaybackLivePage(session, requireNotNull(live), page, wide, { motion.current.page })
                    } else {
                        PlaybackWatchPage(session, page, wide, {
                            motion.current.page
                        }, activity, watchList, episodeQuery, {
                            episodeQuery =
                                it
                        }, hideSeenEpisodes, { hideSeenEpisodes = it })
                    }
                }
            }

            Box(
                Modifier
                    .offset {
                        val rect = motion.current.rect
                        IntOffset(rect.x.roundToInt(), rect.y.roundToInt())
                    }
                    .layout { measurable, incoming ->
                        val rect = motion.current.rect
                        val renderWidth = rect.width.roundToInt().coerceAtLeast(1)
                        val renderHeight = rect.height.roundToInt().coerceAtLeast(1)
                        val child = measurable.measure(
                            androidx.compose.ui.unit.Constraints.fixed(renderWidth, renderHeight),
                        )
                        session.surfaceBounds(
                            Rect(
                                rect.x.roundToInt(),
                                rect.y.roundToInt(),
                                (rect.x + rect.width).roundToInt(),
                                (
                                    rect.y +
                                        rect.height
                                    ).roundToInt(),
                            ),
                        )
                        layout(renderWidth, renderHeight) { child.place(0, 0) }
                    }
                    .onGloballyPositioned { inputCoordinates.value = it }
                    // Observe cancellation on the common ancestor. A fullscreen sibling observer
                    // would win hit testing and prevent the gesture layer below from receiving input.
                    .motionEventSpy { event ->
                        if (event.actionMasked == MotionEvent.ACTION_CANCEL) session.viewModel.cancelPlaybackScrub()
                    }
                    .pointerInput(session.sessionId, shown, inPip, width, height, bottom) {
                        // Input coordinates belong to the stationary app window. Tracking on the moving
                        // video feeds its translation back into the next drag delta and causes judder.
                        awaitEachGesture {
                            val down =
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                                )
                            val coordinate = inputCoordinates.value?.takeIf { it.isAttached } ?: return@awaitEachGesture
                            val rootDown = coordinate.localToRoot(down.position)
                            gestureAnchorY = ((rootDown.y - motion.current.rect.y) / motion.current.rect.height)
                                .coerceIn(0f, 1f)
                            gestureVelocity.resetTracking()
                            gestureVelocity.addPosition(down.uptimeMillis, rootDown)
                            val tracker = PlaybackPointerTracker()
                            tracker.sample(mapOf(down.id.value to rootDown))
                            val initial = motion.current.rect
                            val inside =
                                rootDown.x in initial.x..(initial.x + initial.width) &&
                                    rootDown.y in initial.y..(initial.y + initial.height)
                            val unlocked =
                                !session.viewModel.areControlsLocked.value &&
                                    session.viewModel.sheetShown.value == Sheets.None &&
                                    session.viewModel.panelShown.value == Panels.None &&
                                    session.viewModel.dialogShown.value == Dialogs.None
                            val center =
                                rootDown.x in (initial.x + initial.width * 0.35f)..(initial.x + initial.width * 0.65f)
                            val controlRow = rootDown.y >= initial.y + initial.height - 48f * density.density
                            if (inPip ||
                                dismissingMini ||
                                !inside ||
                                !unlocked ||
                                shown != PlaybackPresentation.Mini &&
                                controlRow ||
                                shown == PlaybackPresentation.Fullscreen &&
                                !center
                            ) {
                                return@awaitEachGesture
                            }
                            var moved = false
                            var finished = false
                            var accumulated = androidx.compose.ui.geometry.Offset.Zero
                            var accumulatedZoom = 1f
                            var multiplePointers = false
                            val axisLock = PlaybackGestureAxisLock(viewConfiguration.touchSlop)
                            try {
                                do {
                                    val event =
                                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    // This ancestor sees Initial before the seek handler. It must not
                                    // steal a horizontal scrub when the finger later drifts downward.
                                    if (shown != PlaybackPresentation.Mini &&
                                        session.viewModel.isSeeking.value
                                    ) {
                                        return@awaitEachGesture
                                    }
                                    if (event.changes.count { it.pressed || it.previousPressed } >
                                        1
                                    ) {
                                        multiplePointers = true
                                    }
                                    val coordinates = inputCoordinates.value?.takeIf { it.isAttached } ?: break
                                    val positions = event.changes.filter {
                                        it.pressed || it.previousPressed
                                    }.associate {
                                        it.id.value to coordinates.localToRoot(it.position)
                                    }
                                    positions[down.id.value]?.let {
                                        gestureVelocity.addPosition(event.changes.first().uptimeMillis, it)
                                    }
                                    val delta = tracker.sample(positions)
                                    var pan = delta.pan
                                    var zoom = delta.zoom
                                    if (!moved) {
                                        accumulated += pan
                                        accumulatedZoom *= zoom
                                        val axis = axisLock.sample(accumulated)
                                        if (shown != PlaybackPresentation.Mini &&
                                            axis == PlaybackGestureAxis.Horizontal
                                        ) {
                                            return@awaitEachGesture
                                        }
                                        moved = if (shown == PlaybackPresentation.Mini) {
                                            accumulated.getDistance() > viewConfiguration.touchSlop ||
                                                abs(accumulatedZoom - 1f) * initial.width > viewConfiguration.touchSlop
                                        } else {
                                            val allowed = shown != PlaybackPresentation.Fullscreen || accumulated.y > 0
                                            axis == PlaybackGestureAxis.Vertical && allowed
                                        }
                                        if (moved) {
                                            pan = accumulated
                                            zoom = accumulatedZoom
                                        }
                                    }
                                    if (moved) {
                                        if (shown == PlaybackPresentation.Mini) {
                                            draggingMini = true
                                            val current = motion.current
                                            val previous = current.rect
                                            val maximum = (width / density.density * 0.62f).coerceAtLeast(1f)
                                            val requested = (previous.width / density.density * zoom).coerceIn(
                                                144f.coerceAtMost(maximum),
                                                maximum,
                                            )
                                            val resized = PlaybackGeometry.mini(
                                                width,
                                                height,
                                                top,
                                                bottom,
                                                density.density,
                                                aspect,
                                                requested,
                                                corner,
                                            )
                                            val x = previous.x + pan.x - (resized.width - previous.width) / 2
                                            val y = previous.y + pan.y - (resized.height - previous.height) / 2
                                            motion.follow(
                                                current.copy(
                                                    rect = PlaybackGeometry.dragMini(
                                                        resized.copy(x = x, y = y),
                                                        width,
                                                        height,
                                                        top,
                                                    ),
                                                ),
                                            )
                                        } else {
                                            onDrag(pan.y)
                                        }
                                        event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                                finished = true
                            } finally {
                                if (moved && shown == PlaybackPresentation.Mini) {
                                    val actual = motion.current.rect
                                    val dismiss = finished &&
                                        !multiplePointers &&
                                        PlaybackGeometry.dismissMini(
                                            actual,
                                            height,
                                            bottom,
                                            density.density,
                                            actual.y - initial.y,
                                        )
                                    if (dismiss) {
                                        dismissingMini = true
                                        session.setMiniDismissalPending(true)
                                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                            try {
                                                motion.moveTo(
                                                    motion.current.copy(
                                                        rect = actual.copy(
                                                            y =
                                                            height + 12f * density.density,
                                                        ),
                                                    ),
                                                    if (reduceMotion) 0 else ModernMotion.EXIT_MILLIS,
                                                )
                                                session.requestClose()
                                            } finally {
                                                // A room with legacy participants can refuse observation.
                                                // Restore its visible mini rather than hiding an active session.
                                                session.setMiniDismissalPending(false)
                                                dismissingMini = false
                                                settlingRevision++
                                            }
                                        }
                                    } else {
                                        val destination = PlaybackGeometry.nearestCorner(
                                            actual.x + actual.width / 2,
                                            actual.y + actual.height / 2,
                                            width,
                                            height,
                                        )
                                        session.miniLayout.value =
                                            MiniLayout(actual.width / density.density, destination)
                                    }
                                    draggingMini = false
                                    settlingRevision++
                                } else if (moved) {
                                    finishDrag(!finished)
                                }
                            }
                        }
                    }
                    .graphicsLayer {
                        clip = true
                        shape = RoundedCornerShape(if (inPip) 0.dp else (14f * (1f - motion.current.expanded)).dp)
                    }
                    .background(Color.Black),

            ) {
                val scaleMode = if (visualPresentation == PlaybackPresentation.Fullscreen && !inPip) {
                    videoAspectMode
                } else {
                    eu.kanade.tachiyomi.ui.player.VideoAspect.Fit
                }
                // Presentation never changes the renderer's layout or buffer, including stretch/crop.
                // PiP changes the viewport, not the native buffer. Resizing its producer
                // during the window transition can leave EGL displaying a cropped old frame.
                val displayBounds = remember(activity, width, height) {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        activity.windowManager.maximumWindowMetrics.bounds
                    } else {
                        android.graphics.Point().let { size ->
                            @Suppress("DEPRECATION")
                            activity.windowManager.defaultDisplay.getRealSize(size)
                            Rect(0, 0, size.x, size.y)
                        }
                    }
                }
                val (baseWidth, baseHeight) = PlaybackGeometry.renderSize(
                    displayBounds.width().toFloat(),
                    displayBounds.height().toFloat(),
                    aspect,
                )
                AndroidView(
                    factory = { MpvRenderView(it, session, activity).also { renderView = it } },
                    modifier = Modifier.fillMaxSize(),
                    onReset = null,
                    onRelease = {
                        it.release()
                        if (renderView === it) renderView = null
                    },
                    update = {
                        it.configure(baseWidth, baseHeight, scaleMode, windowCovered = windowFrame != null) {
                            if (inPip) {
                                0f
                            } else {
                                14f *
                                    density.density *
                                    (1f - motion.current.expanded)
                            }
                        }
                    },
                )
                if (windowFrame != null) {
                    // This exact bitmap must be in the same frame that hides the native
                    // layer, before the frame-commit callback permits window rotation.
                    Image(
                        requireNotNull(windowFrame).asImageBitmap(),
                        null,
                        Modifier.fillMaxSize().background(Color.Black),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    AnimatedVisibility(
                        videoReplacement != null,
                        enter = fadeIn(tween(0)),
                        exit = fadeOut(tween(if (reduceMotion) 0 else ModernMotion.EXIT_MILLIS)),
                    ) {
                        (videoReplacement ?: retainedFrame)?.let {
                            Image(
                                it.asImageBitmap(),
                                null,
                                Modifier.fillMaxSize().background(Color.Black),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
                if (visualPresentation != PlaybackPresentation.Fullscreen && !inPip) {
                    PlaybackVideoBrightness(session)
                }
                if (!inPip) {
                    when (visualPresentation) {
                        PlaybackPresentation.Fullscreen -> if (live != null) {
                            val runtime = requireNotNull(live)
                            eu.kanade.tachiyomi.ui.tv.TvPlayerControls(
                                runtime.session, runtime.output, runtime.services, session.pip, { session.back() },
                                { eu.kanade.tachiyomi.ui.tv.TvActions.share(activity, it) },
                                {
                                    session.openLive(it.ref)
                                }, { eu.kanade.tachiyomi.ui.tv.TvActions.room(activity, it) },
                                session.viewModel.watchManager,
                                onPresentationDrag = {}, onPresentationDragEnd = {},
                            )
                        } else {
                            PlayerControls(
                                viewModel = session.viewModel,
                                onBackPress = { session.back() },
                                onToggleAnime4KSmart = session::toggleAnime4KSmart,
                                onSelectAnime4KCustom = session::selectAnime4KCustom,
                                onPresentationDrag = {},
                                onPresentationDragEnd = {},
                                horizontalSeekEnabled = shown == PlaybackPresentation.Fullscreen,
                            )
                        }
                        PlaybackPresentation.Page -> {
                            if (live == null) {
                                GestureHandler(
                                    session.viewModel,
                                    remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    onPresentationDrag = {},
                                    onPresentationDragEnd = {},
                                    presentationDragEverywhere = true,
                                    horizontalSeekEnabled = false,
                                )
                            }

                            PlaybackCompactControls(session, onReduce = { session.present(PlaybackPresentation.Mini) })
                        }
                        PlaybackPresentation.Mini -> {
                            val closeLabel = stringResource(R.string.playback_close)
                            Box(
                                Modifier.fillMaxSize().semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction(closeLabel) {
                                            session.requestClose()
                                            true
                                        },
                                    )
                                }.clickable(onClickLabel = stringResource(R.string.playback_watch_page)) {
                                    session.present(PlaybackPresentation.Page)
                                    if (reader) {
                                        activity.startActivity(
                                            Intent(
                                                activity,
                                                MainActivity::class.java,
                                            ).setAction(PlaybackSessionService.OPEN)
                                                .addFlags(
                                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                                ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (!inPip && visualPresentation != PlaybackPresentation.Fullscreen && live == null) {
                PlayerControls(
                    viewModel = session.viewModel,
                    onBackPress = { session.back() },
                    onToggleAnime4KSmart = session::toggleAnime4KSmart,
                    onSelectAnime4KCustom = session::selectAnime4KCustom,
                    toolsOnly = true,
                )
            }
            if (restricted) {
                AlertDialog(
                    onDismissRequest = { session.closeRestriction.value = false },
                    title = { Text(stringResource(R.string.playback_observer_unavailable)) },
                    text = { Text(stringResource(R.string.playback_observer_legacy)) },
                    confirmButton = {
                        TextButton(onClick = {
                            session.closeRestriction.value = false
                            session.viewModel.watchTogether.leave()
                            session.close()
                        }) { Text(stringResource(R.string.playback_leave_and_close)) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            session.closeRestriction.value = false
                        }) { Text(stringResource(R.string.playback_keep_mini)) }
                    },
                )
            }
        }
    }
}

private class PlaybackInputCoordinates {
    var value: androidx.compose.ui.layout.LayoutCoordinates? = null
}

@Composable
private fun PlaybackVideoBrightness(session: PlayerSession) {
    val brightness by session.viewModel.currentBrightness.collectAsState()
    eu.kanade.tachiyomi.ui.player.controls.components.BrightnessOverlay(brightness)
}
