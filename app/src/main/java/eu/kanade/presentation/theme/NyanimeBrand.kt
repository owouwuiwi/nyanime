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
    val atmosphere: Color,
    val floatingStart: Color,
    val floatingEnd: Color,
    val floatingText: Color,
    val floatingBorder: Color,
)

private val classicDarkBrand = NyanimeBrandColors(
    Color(0xFFE50914),
    Color.White,
    Color.Black,
    Color.White,
    Color.Black,
    Color(0xFF69203D),
    Color(0xFF26080E),
    Color(0xFF490D19),
    Color.White,
    Color.White.copy(alpha = 0.10f),
)
private val classicLightBrand = NyanimeBrandColors(
    Color(0xFFC45110),
    Color(0xFF20201F),
    Color.White,
    Color(0xFFC45110),
    Color.White,
    Color(0xFFEEC0C3),
    Color(0xFFFFFCF8),
    Color(0xFFFFF3E9),
    Color(0xFF32251F),
    Color(0xFFE4DAD2),
)

val nyanimeBrandColors: NyanimeBrandColors
    @Composable get() = if (LocalDarkTheme.current) classicDarkBrand else classicLightBrand

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
            if (LocalDarkTheme.current) R.drawable.nyanime_app_icon_dark else R.drawable.nyanime_app_icon_light,
        ),
        contentDescription = null,
        modifier = modifier,
    )
}
