package eu.kanade.presentation.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import eu.kanade.presentation.motion.posterBackdropOpacity
import eu.kanade.presentation.motion.posterForeground

/** Home and title pages share the same bounded glass, atmosphere and surface materials. */
@Composable
fun Modifier.nyanimeBackdropSource(state: HazeState): Modifier {
    val atmosphere = if (LocalDarkTheme.current) Color(0xFF69203D) else Color(0xFFEEC0C3)
    return hazeSource(state).nyanimeAtmosphere(atmosphere)
}

/** A pinned control paints the same stationary backdrop, rather than a flat opaque stripe. */
@Composable
fun Modifier.nyanimePinnedBackdrop(
    viewportWidth: Dp,
    topInset: Dp,
    opacity: () -> Float,
): Modifier {
    val atmosphere = if (LocalDarkTheme.current) Color(0xFF69203D) else Color(0xFFEEC0C3)
    return nyanimeAtmosphere(
        atmosphere,
        viewportWidth = viewportWidth,
        topInset = topInset,
        background = MaterialTheme.colorScheme.background,
        opacity = opacity,
    )
}

private fun Modifier.nyanimeAtmosphere(
    atmosphere: Color,
    viewportWidth: Dp? = null,
    topInset: Dp = 0.dp,
    background: Color? = null,
    opacity: () -> Float = { 1f },
): Modifier = drawWithCache {
    val width = viewportWidth?.toPx() ?: size.width
    // The pinned slot is centered inside the viewport and sits directly below the top bar.
    // Keeping the brush in page coordinates prevents its color from jumping when it pins.
    val center = Offset(width * 0.35f - (width - size.width) / 2f, 180.dp.toPx() - topInset.toPx())
    val radius = 320.dp.toPx()
    val brush = Brush.radialGradient(
        colors = listOf(atmosphere.copy(alpha = 0.62f), atmosphere.copy(alpha = 0f)),
        center = center,
        radius = radius,
    )
    onDrawBehind {
        val alpha = opacity()
        if (alpha > 0f) {
            clipRect {
                background?.let { drawRect(it, alpha = alpha) }
                withTransform({ scale(width * 0.9f / radius, 1f, pivot = center) }) {
                    drawCircle(brush, radius, center, alpha = alpha)
                }
            }
        }
    }
}

/** Only the backdrop is captured. Wordmarks, category labels and actions remain sharp. */
@Composable
fun NyanimeGlassHeader(
    state: HazeState,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    sharedTransitionOverlay: Boolean = false,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val header = subcompose("controls") {
            val foreground = if (sharedTransitionOverlay) Modifier.posterForeground(zIndex = 2.5f) else Modifier
            Column(Modifier.fillMaxWidth().then(foreground).padding(top = topInset)) { content() }
        }.single().measure(constraints.copy(minHeight = 0))
        val glass = subcompose("glass") { NyanimeGlass(state, sharedTransitionOverlay) }.single().measure(
            Constraints.fixed(header.width, header.height + 44.dp.roundToPx()),
        )
        layout(header.width, header.height) {
            glass.place(0, -32.dp.roundToPx())
            header.place(0, 0)
        }
    }
}

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun NyanimeGlass(
    state: HazeState,
    sharedTransitionOverlay: Boolean,
) {
    val background = androidx.compose.material3.MaterialTheme.colorScheme.background
    val dark = LocalDarkTheme.current
    val atmosphere = if (dark) Color(0xFF69203D) else Color(0xFFEEC0C3)
    val supportedBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val opacity = posterBackdropOpacity()
    val tint = remember(background, atmosphere) {
        val warm = lerp(background, atmosphere, if (dark) 0.09f else 0.13f)
        if (!supportedBlur) {
            Brush.verticalGradient(
                0f to background,
                0.74f to warm,
                0.84f to warm.copy(alpha = 0.88f),
                0.92f to warm.copy(alpha = 0.40f),
                1f to Color.Transparent,
            )
        } else {
            Brush.verticalGradient(
                0f to background.copy(alpha = 0.94f),
                0.14f to warm.copy(alpha = 0.90f),
                0.30f to warm.copy(alpha = 0.84f),
                0.44f to warm.copy(alpha = 0.78f),
                0.58f to warm.copy(alpha = 0.70f),
                0.69f to warm.copy(alpha = 0.58f),
                0.80f to warm.copy(alpha = 0.38f),
                0.91f to warm.copy(alpha = 0.16f),
                0.97f to warm.copy(alpha = 0.04f),
                1f to warm.copy(alpha = 0f),
            )
        }
    }
    val feather = remember {
        Brush.verticalGradient(
            0f to Color.Black,
            0.62f to Color.Black,
            0.74f to Color.Black.copy(alpha = 0.90f),
            0.83f to Color.Black.copy(alpha = 0.65f),
            0.90f to Color.Black.copy(alpha = 0.30f),
            1f to Color.Transparent,
        )
    }
    val edge = remember {
        Brush.verticalGradient(
            0f to Color.Black,
            0.90f to Color.Black,
            0.95f to Color.Black.copy(alpha = 0.70f),
            0.98f to Color.Black.copy(alpha = 0.20f),
            1f to Color.Transparent,
        )
    }
    val progressiveBlur = remember(feather) { HazeProgressive.Brush(feather) }
    val style = remember(background) {
        HazeStyle(
            backgroundColor = background,
            tints = listOf(HazeTint(Color.Transparent)),
            fallbackTint = HazeTint(Color.Transparent),
            blurRadius = 10.dp,
            noiseFactor = 0f,
        )
    }
    // One bounded backdrop, not three full-screen copies. Old Android versions use a readable
    // soft tint without forcing the library's experimental CPU/RenderScript path.
    // Only Home's header needs to cover artwork escaping from a shared source card. A destination
    // header stays in its normal Scaffold layer, including while modal windows open and close.
    val foreground = if (sharedTransitionOverlay) {
        Modifier.posterForeground(zIndex = 2.4f, fadeOnEnter = false)
    } else {
        Modifier
    }
    Box(
        Modifier.fillMaxSize()
            // The glass must already cover travelling artwork on the page's first frame.
            // Only the sharp controls fade in; fading the glass exposes the original image.
            .then(foreground)
            .hazeEffect(state, style) {
                // Fade the blur radius before its opacity: the sharp original must not
                // bleed through category labels as it moves under the fixed controls.
                progressive = progressiveBlur.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU }
                mask = edge
                inputScale = HazeInputScale.Fixed(0.8f)
                alpha = opacity.value
            }
            .drawWithCache {
                onDrawBehind { drawRect(tint, alpha = opacity.value) }
            }
            .testTag("nyanime_continuity_glass"),
    )
}

/** The Home navigation and highlighted detail cards use one material, without another blur layer. */
@Composable
fun NyanimeFloatingSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = LocalDarkTheme.current
    val brush = remember(dark) {
        Brush.horizontalGradient(
            if (dark) {
                listOf(Color(0xFF26080E), Color(0xFF490D19))
            } else {
                listOf(Color(0xFFFFFCF8), Color(0xFFFFF3E9))
            },
        )
    }
    Surface(
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        contentColor = if (dark) Color.White else Color(0xFF32251F),
        border = BorderStroke(1.dp, if (dark) Color.White.copy(alpha = 0.10f) else Color(0xFFE4DAD2)),
    ) {
        Box(Modifier.background(brush, shape), content = content)
    }
}

/** White captions over artwork need the same neutral scrim in both application themes. */
internal val nyanimeArtworkScrim: Brush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.08f),
    0.30f to Color.Transparent,
    0.58f to Color.Black.copy(alpha = 0.56f),
    1f to Color.Black.copy(alpha = 0.94f),
)
