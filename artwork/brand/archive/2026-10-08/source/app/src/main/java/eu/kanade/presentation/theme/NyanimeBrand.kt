package eu.kanade.presentation.theme

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import eu.kanade.tachiyomi.R

val LocalDarkTheme = staticCompositionLocalOf { true }

/** Brand and artwork roles are distinct from ordinary surface/text colors. */
data class NyanimeBrandColors(
    val wordmark: Color,
    val artworkText: Color,
    val artworkShade: Color,
    val actionBackground: Color,
    val actionText: Color,
)

private val darkBrand = NyanimeBrandColors(
    Color(0xFFE50914),
    Color.White,
    Color.Black,
    Color.White,
    Color.Black,
)
private val lightBrand = NyanimeBrandColors(
    Color(0xFFC45110),
    Color(0xFF20201F),
    Color.White,
    Color(0xFFC45110),
    Color.White,
)

val nyanimeBrandColors: NyanimeBrandColors
    @Composable get() = if (LocalDarkTheme.current) darkBrand else lightBrand

@Composable
fun nyanimeHeroGradient(artworkHeight: Dp): Brush {
    val shade = nyanimeBrandColors.artworkShade
    val end = with(LocalDensity.current) { artworkHeight.toPx() }
    if (LocalDarkTheme.current) {
        return Brush.verticalGradient(
            0f to shade.copy(alpha = 0.12f),
            0.38f to shade.copy(alpha = 0.05f),
            0.52f to shade.copy(alpha = 0.7f),
            0.7f to shade.copy(alpha = 0.82f),
            1f to MaterialTheme.colorScheme.background,
            endY = end,
        )
    }
    return Brush.verticalGradient(
        0f to Color.Transparent,
        0.38f to shade.copy(alpha = 0.05f),
        0.52f to shade.copy(alpha = 0.78f),
        0.58f to shade.copy(alpha = 0.92f),
        0.7f to shade.copy(alpha = 0.98f),
        1f to MaterialTheme.colorScheme.background,
        endY = end,
    )
}

@Composable
fun NyanimeMark(modifier: Modifier = Modifier) {
    Image(
        painterResource(
            if (LocalDarkTheme.current) R.drawable.ic_nyanime_mark_red else R.drawable.ic_nyanime_mark_orange,
        ),
        contentDescription = null,
        modifier = modifier,
    )
}
