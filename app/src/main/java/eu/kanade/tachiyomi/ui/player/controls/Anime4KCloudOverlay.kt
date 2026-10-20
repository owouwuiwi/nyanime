package eu.kanade.tachiyomi.ui.player.controls

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.Anime4KCloudFrame
import eu.kanade.tachiyomi.ui.player.Anime4KCloudGeometry
import eu.kanade.tachiyomi.ui.player.Anime4KCloudMesh
import eu.kanade.tachiyomi.ui.player.Anime4KReveal
import android.graphics.Canvas as AndroidCanvas

/** Decorative Canvas only: never samples, captures or changes the video surface. */
@Composable
internal fun Anime4KCloudOverlay(frame: Anime4KCloudFrame?, onComplete: (Long) -> Unit) {
    if (frame == null) return
    val progress = remember(frame.id) { Animatable(0f) }
    val opacity = remember(frame.id) { Animatable(1f) }
    val mesh = remember { Anime4KCloudMesh() }
    val paint = remember { Paint().apply { color = android.graphics.Color.WHITE } }
    var drawFailed by remember(frame.id) { mutableStateOf(false) }
    // Hardware drawVertices arrived in Android 10. Older supported versions use
    // the same single mesh with one immutable white texel, never a video bitmap.
    val whitePixel = remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        } else {
            null
        }
    }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val origin = remember(frame.origin, bounds) { Offset(frame.origin.x - bounds.left, frame.origin.y - bounds.top) }
    val reach = remember(origin, bounds) { Anime4KCloudGeometry.reach(bounds.width, bounds.height, origin.x, origin.y) }
    val density = LocalDensity.current
    val halfWidth = with(density) { 32.dp.toPx() }
    LaunchedEffect(drawFailed) {
        if (drawFailed) onComplete(frame.id)
    }
    LaunchedEffect(frame.id, frame.exiting) {
        if (frame.exiting) {
            opacity.animateTo(0f, tween(Anime4KReveal.FADE_MILLIS.toInt()))
        } else {
            progress.animateTo(1f, tween(Anime4KReveal.DURATION_MILLIS.toInt(), easing = LinearEasing))
        }
        onComplete(frame.id)
    }
    // An independent display list prevents the cloud's clock from redrawing
    // the complete chrome. Alpha stays in Paint: no fullscreen offscreen layer.
    Canvas(Modifier.fillMaxSize().graphicsLayer().onGloballyPositioned { bounds = it.boundsInWindow() }) {
        if (reach <= 0 || drawFailed) return@Canvas
        val p = progress.value
        val envelope = (p / .07f).coerceIn(0f, 1f) * ((1f - p) / .12f).coerceIn(0f, 1f) * opacity.value
        val radius = reach * p * p * (3f - 2f * p)
        if (envelope <= 0 || !mesh.update(radius, halfWidth)) return@Canvas
        paint.alpha = (envelope * 255).toInt().coerceIn(0, 255)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val saved = native.save()
            try {
                native.translate(origin.x, origin.y)
                if (whitePixel == null) {
                    native.drawVertices(
                        AndroidCanvas.VertexMode.TRIANGLES, mesh.vertices.size, mesh.vertices, 0,
                        null, 0, mesh.colors, 0, mesh.indices, 0, mesh.indices.size, paint,
                    )
                } else {
                    native.drawBitmapMesh(
                        whitePixel,
                        mesh.bandSegments,
                        Anime4KCloudGeometry.POINTS,
                        mesh.vertices,
                        0,
                        mesh.colors,
                        0,
                        paint,
                    )
                }
            } catch (_: RuntimeException) {
                // Decorative failures must not alter SM or end playback.
                drawFailed = true
            } finally {
                native.restoreToCount(saved)
            }
        }
    }
}
