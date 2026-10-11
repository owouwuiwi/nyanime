package eu.kanade.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val LocalNyanimeStyle = staticCompositionLocalOf { false }

/** Renders the chosen appearance using only visual inputs. */
@Composable
fun NyanimeTheme(
    palette: NyanimePalette,
    dark: Boolean,
    amoled: Boolean,
    modern: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalNyanimeStyle provides modern,
        LocalDarkTheme provides dark,
    ) {
        MaterialTheme(
            colorScheme = palette.colorScheme(LocalContext.current, dark, amoled),
            shapes = if (modern) NyanimeShapes else Shapes(),
            typography = if (modern) NyanimeTypography else Typography(),
            content = content,
        )
    }
}

private const val RIPPLE_DRAGGED_ALPHA = .1f
private const val RIPPLE_FOCUSED_ALPHA = .1f
private const val RIPPLE_HOVERED_ALPHA = .1f
private const val RIPPLE_PRESSED_ALPHA = .1f

val playerRippleConfiguration
    @Composable
    get() =
        RippleConfiguration(
            color = if (isSystemInDarkTheme()) Color.White else Color.Black,
            rippleAlpha =
            RippleAlpha(
                draggedAlpha = RIPPLE_DRAGGED_ALPHA,
                focusedAlpha = RIPPLE_FOCUSED_ALPHA,
                hoveredAlpha = RIPPLE_HOVERED_ALPHA,
                pressedAlpha = RIPPLE_PRESSED_ALPHA,
            ),
        )
