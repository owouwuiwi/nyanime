package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import dev.vivvvek.seeker.Segment
import eu.kanade.presentation.motion.ModernMotion

/** One thin timeline for page and fullscreen. The 48dp touch target is independent of its drawing. */
@Composable
internal fun PlaybackSeekBar(
    value: Double,
    duration: Double,
    onChange: (Double) -> Unit,
    onFinished: () -> Unit,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
    buffered: Double = 0.0,
    segments: List<Segment> = emptyList(),
    bottomAligned: Boolean = false,
    reduceMotion: Boolean = false,
    advancing: Boolean = false,
) {
    val maximum = duration.takeIf { it.isFinite() && it > 0 } ?: 1.0
    val safeValue = value.takeIf { it.isFinite() }?.coerceIn(0.0, maximum) ?: 0.0
    val ahead = buffered.takeIf { it.isFinite() }?.coerceIn(safeValue, maximum) ?: safeValue
    val orange = Color(0xFFFFA13B)
    val red = Color(0xFFFF2948)
    val bufferColor = Color(0xFFC84B58)
    val progressBrush = remember { Brush.horizontalGradient(listOf(orange, red)) }
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    val cancelled by rememberUpdatedState(onCancelled)
    var pressed by remember { mutableStateOf(false) }
    val radius = animateFloatAsState(
        if (pressed) 8f else 4f,
        ModernMotion.controlResponse(!reduceMotion),
        label = "playback_seek_thumb",
    )
    val progress = remember { Animatable(safeValue.toFloat()) }
    LaunchedEffect(safeValue, pressed, reduceMotion, advancing) {
        val target = safeValue.toFloat()
        if (pressed || reduceMotion || !advancing || kotlin.math.abs(progress.value - target) > 1.25f) {
            progress.snapTo(target)
        } else {
            // Native seconds arrive at 1Hz. Interpolate only their small playback increments.
            progress.animateTo(target, tween(900, easing = LinearEasing))
        }
    }
    val markers = remember(segments, maximum) {
        segments.filter { it.start.isFinite() && it.start > 0 && it.start < maximum }
            .distinctBy { it.start }.sortedBy { it.start }
    }
    Canvas(
        modifier.fillMaxWidth().height(48.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(safeValue.toFloat(), 0f..maximum.toFloat())
                setProgress { target ->
                    change(target.toDouble().coerceIn(0.0, maximum))
                    finished()
                    true
                }
            }
            .pointerInput(maximum) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pressed = true
                    change((down.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * maximum)
                    var released = false
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            change((pointer.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * maximum)
                            pointer.consume()
                            if (!pointer.pressed) {
                                released = true
                                break
                            }
                        } while (true)
                    } finally {
                        pressed = false
                        if (released) finished() else cancelled()
                    }
                }
            },
    ) {
        val y = if (bottomAligned) size.height - 10.dp.toPx() else size.height / 2
        val visibleValue = if (pressed || !advancing || kotlin.math.abs(progress.value - safeValue) > 1.25) {
            safeValue
        } else {
            progress.value.toDouble()
        }
        val fraction = (visibleValue / maximum).toFloat().coerceIn(0f, 1f)
        val x = fraction * size.width
        val color = lerp(orange, red, fraction)
        val stroke = 2.dp.toPx()
        drawLine(Color.White.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), stroke)
        drawLine(
            bufferColor,
            Offset(0f, y),
            Offset((ahead / maximum).toFloat() * size.width, y),
            stroke,
        )
        drawLine(
            progressBrush,
            Offset(0f, y),
            Offset(x, y),
            stroke,
            cap = StrokeCap.Round,
        )
        for (marker in markers) {
            val markerX = (marker.start / maximum).toFloat() * size.width
            drawLine(
                Color.Black.copy(alpha = 0.7f),
                Offset(markerX, y - stroke / 2),
                Offset(markerX, y + stroke / 2),
                1.dp.toPx(),
            )
        }
        val thumb = radius.value.dp.toPx().coerceAtMost(size.width / 2)
        drawCircle(color, thumb, Offset(x.coerceIn(thumb, size.width - thumb), y))
    }
}
