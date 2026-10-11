package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.watch.WatchRecovery
import eu.kanade.tachiyomi.data.watch.WatchRoomState
import eu.kanade.tachiyomi.data.watch.preparationCaption
import eu.kanade.tachiyomi.data.watch.recovery
import eu.kanade.tachiyomi.data.watch.showPreparationFeedback
import eu.kanade.tachiyomi.ui.watch.WatchParticipantStrip
import eu.kanade.tachiyomi.ui.watch.rememberRoomText
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

private val TogetherAccent = Color(0xFFFF4767)
private enum class PlaybackGlyph { Play, Pause, Preparing, Countdown, Retry }

/** A visual reflection of the room clock: animations never schedule or start playback. */
@Composable
fun WatchPlaybackButton(
    room: WatchRoomState,
    loading: Boolean,
    paused: Boolean,
    enabled: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    showParticipants: Boolean = true,
    showCaption: Boolean = true,
) {
    val text = rememberRoomText()
    val seconds = room.resumeSeconds?.takeIf { it > 0 }
    val preparing = room.showPreparationFeedback || loading
    val glyph = when {
        room.recovery == WatchRecovery.Command || room.recovery == WatchRecovery.Connection -> PlaybackGlyph.Retry
        seconds != null -> PlaybackGlyph.Countdown
        preparing -> PlaybackGlyph.Preparing
        paused -> PlaybackGlyph.Play
        else -> PlaybackGlyph.Pause
    }
    val details = room.recovery == WatchRecovery.Details
    val busy = glyph != PlaybackGlyph.Play && glyph != PlaybackGlyph.Pause || details
    val caption = when {
        glyph == PlaybackGlyph.Countdown -> text(R.string.room_starting)
        else -> room.preparationCaption(text, loading)
    }
    val action = when (glyph) {
        PlaybackGlyph.Retry -> text(R.string.room_retry)
        else -> if (room.wantsPlayback &&
            !room.localHold
        ) {
            text(R.string.room_pause_action)
        } else {
            text(R.string.room_play_action)
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale =
        animateFloatAsState(
            if (pressed) 0.96f else 1f,
            ModernMotion.controlResponse(!reduceMotion),
            label = "togetherPress",
        )

    // Keep the same 96 dp footprint as Play. The caption does not move the button or its neighbours.
    Box(modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(96.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .clip(CircleShape)
                .clickable(
                    enabled =
                    enabled && (glyph == PlaybackGlyph.Retry || room.host || room.sharedControls || room.localHold),
                    interactionSource = interaction,
                    indication = ripple(),
                    role = Role.Button,
                    onClickLabel = action,
                    onClick = when (glyph) {
                        PlaybackGlyph.Retry -> onRetry
                        else -> onClick
                    },
                )
                .semantics(mergeDescendants = true) {
                    stateDescription = when {
                        glyph == PlaybackGlyph.Countdown -> text(R.string.room_start_seconds, seconds ?: 1)
                        busy -> caption
                        else -> action
                    }
                    liveRegion = LiveRegionMode.Polite
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = glyph to seconds,
                contentKey = {
                    when (it.first) {
                        PlaybackGlyph.Play, PlaybackGlyph.Pause -> "play_pause"
                        else -> it.first
                    }
                },
                transitionSpec = {
                    (
                        fadeIn(tween(if (reduceMotion) 0 else 240)) +
                            scaleIn(tween(if (reduceMotion) 0 else 300), initialScale = 0.88f)
                        )
                        .togetherWith(fadeOut(tween(if (reduceMotion) 0 else 160)))
                        .using(SizeTransform(clip = false))
                },
                contentAlignment = Alignment.Center,
                label = "togetherPlaybackGlyph",
            ) { (visibleGlyph, visibleSeconds) ->
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    when (visibleGlyph) {
                        PlaybackGlyph.Preparing -> TogetherLoadingGlyph(reduceMotion)
                        PlaybackGlyph.Countdown -> TogetherCountdown(visibleSeconds ?: 1, reduceMotion)
                        PlaybackGlyph.Play, PlaybackGlyph.Pause -> PlaybackPlayPauseGlyph(
                            paused = visibleGlyph == PlaybackGlyph.Play,
                            reduceMotion = reduceMotion,
                            modifier = Modifier.size(64.dp),
                            contentDescription = action,
                        )
                        PlaybackGlyph.Retry -> Icon(Icons.Default.Refresh, action, Modifier.size(48.dp), TogetherAccent)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = busy && showParticipants && room.members.isNotEmpty(),
            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-36).dp).wrapContentWidth(unbounded = true),
            enter = fadeIn(tween(if (reduceMotion) 0 else 180)),
            exit = fadeOut(tween(if (reduceMotion) 0 else 140)),
        ) {
            WatchParticipantStrip(room.members)
        }
        AnimatedContent(
            targetState = caption.takeIf { showCaption && busy && !details },
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 106.dp)
                .wrapContentWidth(unbounded = true).widthIn(max = 264.dp),
            transitionSpec = {
                fadeIn(tween(if (reduceMotion) 0 else 220))
                    .togetherWith(fadeOut(tween(if (reduceMotion) 0 else 120)))
                    .using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.TopCenter,
            label = "togetherPlaybackCaption",
        ) { visibleCaption ->
            if (visibleCaption != null) {
                Text(
                    visibleCaption,
                    modifier = Modifier.clearAndSetSemantics {},
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall.copy(shadow = Shadow(Color.Black, blurRadius = 12f)),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TogetherCountdown(seconds: Int, reduceMotion: Boolean) {
    val initialSeconds = remember { seconds.coerceAtLeast(1) }
    val progress = animateFloatAsState(
        (seconds.toFloat() / initialSeconds).coerceIn(0f, 1f),
        tween(if (reduceMotion) 0 else 450),
        label = "togetherCountdownRing",
    )
    Canvas(Modifier.size(96.dp)) {
        val inset = 9.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.16f), size.minDimension / 2 - inset, style = Stroke(1.dp.toPx()))
        drawArc(
            TogetherAccent,
            -90f,
            360f * progress.value,
            false,
            Offset(inset, inset),
            Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
    AnimatedContent(
        targetState = seconds,
        transitionSpec = {
            (
                fadeIn(tween(if (reduceMotion) 0 else 200)) +
                    scaleIn(tween(if (reduceMotion) 0 else 260), initialScale = 0.8f)
                )
                .togetherWith(
                    fadeOut(tween(if (reduceMotion) 0 else 130)) +
                        scaleOut(tween(if (reduceMotion) 0 else 180), targetScale = 1.12f),
                ).using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.Center,
        label = "togetherCountdownDigit",
    ) {
        Text(
            it.toString(),
            Modifier.clearAndSetSemantics {},
            color = Color.White,
            style = MaterialTheme.typography.displaySmall.copy(
                fontSize = 44.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
                shadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 14f),
            ),
        )
    }
}

@Composable
internal fun TogetherLoadingGlyph(reduceMotion: Boolean, shared: Boolean = true) {
    val still = reduceMotion || LocalInspectionMode.current
    val motion = if (still) null else rememberInfiniteTransition(label = "togetherLoading")
    val phase = motion?.animateFloat(
        if (shared) 0f else 2f,
        3f,
        infiniteRepeatable(
            tween(if (shared) 4200 else 1400, easing = LinearEasing),
            repeatMode = if (shared) RepeatMode.Restart else RepeatMode.Reverse,
        ),
        label = "togetherMorph",
    )
    val rotation = motion?.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(2100, easing = LinearEasing)),
        label = "togetherOrbit",
    )
    TogetherLoadingArtwork(phase = {
        phase?.value ?: if (shared) 1f else 2f
    }, angle = { rotation?.value ?: 30f }, still = still)
}

@Composable
internal fun TogetherLoadingArtwork(phase: () -> Float, angle: () -> Float, still: Boolean = false) {
    val shapes = remember { TogetherGlyphPaths() }
    val outline = remember { Path() }
    val glow = remember { Brush.radialGradient(listOf(TogetherAccent.copy(alpha = 0.22f), Color.Transparent)) }
    Canvas(Modifier.size(96.dp)) {
        // Animated state is read in drawing only: no player layout/recomposition on each frame.
        val current = phase()
        val rotation = angle()
        val segment = floor(current).toInt().coerceIn(0, 2)
        val fraction = (current - segment).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        val from = shapes.points[segment]
        val to = shapes.points[(segment + 1) % 3]
        val circle = when (segment) {
            1 -> fraction
            2 -> 1 - fraction
            else -> 0f
        }
        val pulse = if (still) 1f else 1f + 0.04f * sin(rotation * PI.toFloat() / 90f)
        val glyphSize = 48.dp.toPx() * pulse
        val radius = size.minDimension / 2
        drawCircle(glow, radius)
        val inset = 9.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.13f), radius - inset, style = Stroke(1.dp.toPx()))
        drawArc(
            TogetherAccent,
            rotation - 90f,
            84f,
            false,
            Offset(inset, inset),
            Size(size.width - 2 * inset, size.height - 2 * inset),
            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
        )
        outline.reset()
        from.indices.forEach { i ->
            val x = center.x + (from[i].x + (to[i].x - from[i].x) * fraction - 50f) * glyphSize / 100
            val y = center.y + (from[i].y + (to[i].y - from[i].y) * fraction - 50f) * glyphSize / 100
            if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
        }
        outline.close()
        drawPath(outline, TogetherAccent, alpha = 1 - circle)
        drawPath(outline, TogetherAccent, alpha = circle, style = Stroke(2.5.dp.toPx()))
    }
}

/** Equal arc-length samples keep the play/heart/circle transformation continuous at the loop seam. */
private class TogetherGlyphPaths {
    val points: List<List<Offset>> = listOf(
        Path().apply {
            moveTo(29f, 82f)
            lineTo(29f, 18f)
            quadraticTo(29f, 14f, 33f, 17f)
            lineTo(81f, 47f)
            quadraticTo(85f, 50f, 81f, 53f)
            lineTo(33f, 83f)
            quadraticTo(29f, 86f, 29f, 82f)
            close()
        },
        Path().apply {
            moveTo(50f, 82f)
            cubicTo(43f, 74f, 15f, 54f, 15f, 35f)
            cubicTo(15f, 13f, 40f, 11f, 50f, 31f)
            cubicTo(60f, 11f, 85f, 13f, 85f, 35f)
            cubicTo(85f, 54f, 57f, 74f, 50f, 82f)
            close()
        },
        Path().apply {
            moveTo(50f, 82f)
            cubicTo(32.33f, 82f, 18f, 67.67f, 18f, 50f)
            cubicTo(18f, 32.33f, 32.33f, 18f, 50f, 18f)
            cubicTo(67.67f, 18f, 82f, 32.33f, 82f, 50f)
            cubicTo(82f, 67.67f, 67.67f, 82f, 50f, 82f)
            close()
        },
    ).map { path ->
        val measure = PathMeasure().apply { setPath(path, true) }
        List(96) { measure.getPosition(measure.length * it / 96) }
    }
}
