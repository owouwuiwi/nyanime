package eu.kanade.presentation.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R

internal val NyanimeShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

private val baseTypography = Typography()
internal val NyanimeTypography = Typography(
    headlineLarge = baseTypography.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineMedium = baseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = baseTypography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = baseTypography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = baseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = baseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = baseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun nyanimeWordmarkResource(): Int = if (LocalDarkTheme.current) {
    R.drawable.nyanime_wordmark_dark
} else {
    R.drawable.nyanime_wordmark_light
}

@Composable
fun NyanimeWordmark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(nyanimeWordmarkResource()),
        contentDescription = "Nyanime",
        modifier = modifier
            .wrapContentWidth(Alignment.Start)
            .height(42.dp)
            .widthIn(max = 148.dp),
        alignment = Alignment.CenterStart,
    )
}
