package eu.kanade.tachiyomi.ui.community

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.data.community.CommunityImageDrafts

/** The previous successful image survives refreshes; geometry is owned by the calling layout. */
@Composable
internal fun CommunityImage(
    model: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var previous by remember { mutableStateOf<Painter?>(null) }
    val context = LocalContext.current
    val motion = modernMotionEnabled()
    val request = remember(model, motion) {
        ImageRequest.Builder(context).data(CommunityImageDrafts(context).file(model) ?: model)
            .crossfade(if (motion) 220 else 0).build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = previous,
        error = previous,
        onSuccess = { previous = it.painter },
    )
}

/** Small original vector stickers: no external artwork, fonts, or network requests. */
@Composable
internal fun NyanimeSticker(name: String) {
    val label = when (name) {
        "heart" -> "Un cuore per te"
        "star" -> "Questa storia brilla"
        "cat" -> "Il gatto Nyanime"
        else -> "Pronti per la prossima puntata"
    }
    Canvas(Modifier.size(88.dp).semantics { contentDescription = label }) {
        scale(size.width / 100, size.height / 100, pivot = Offset.Zero) {
            val ink = Color(0xFF292037)
            val cream = Color(0xFFFFE7B5)
            drawCircle(Color(0xFFBA89F5).copy(alpha = .15f), 47f, Offset(50f, 50f))
            when (name) {
                "heart" -> {
                    val heart = Path().apply {
                        moveTo(50f, 81f)
                        cubicTo(0f, 50f, 16f, 12f, 39f, 22f)
                        quadraticTo(46f, 24f, 50f, 32f)
                        cubicTo(78f, -2f, 111f, 44f, 50f, 81f)
                        close()
                    }
                    drawPath(heart, Color(0xFFFA749F))
                    drawPath(heart, ink, style = Stroke(3f))
                    drawLine(Color.White.copy(alpha = .7f), Offset(27f, 36f), Offset(25f, 43f), 5f)
                    drawCircle(ink, 2.7f, Offset(42f, 48f))
                    drawCircle(ink, 2.7f, Offset(61f, 48f))
                    drawArc(ink, 0f, 180f, false, Offset(46f, 53f), Size(12f, 8f), style = Stroke(2.5f))
                }
                "star" -> {
                    val star = Path()
                    repeat(10) { index ->
                        val angle = -Math.PI / 2 + index * Math.PI / 5
                        val radius = if (index % 2 == 0) 38 else 20
                        val x = 50 + kotlin.math.cos(angle).toFloat() * radius
                        val y = 50 + kotlin.math.sin(angle).toFloat() * radius
                        if (index == 0) star.moveTo(x, y) else star.lineTo(x, y)
                    }
                    star.close()
                    drawPath(star, Color(0xFFFFD56D))
                    drawPath(star, ink, style = Stroke(3f))
                    drawCircle(ink, 2.5f, Offset(41f, 48f))
                    drawCircle(ink, 2.5f, Offset(59f, 48f))
                    drawArc(ink, 0f, 180f, false, Offset(44f, 52f), Size(12f, 8f), style = Stroke(2.5f))
                }
                "cat" -> {
                    val ears = Path().apply {
                        moveTo(22f, 48f)
                        lineTo(18f, 18f)
                        lineTo(41f, 35f)
                        lineTo(59f, 35f)
                        lineTo(82f, 18f)
                        lineTo(78f, 48f)
                        close()
                    }
                    drawPath(ears, cream)
                    drawPath(ears, ink, style = Stroke(3f))
                    drawOval(cream, Offset(18f, 32f), Size(64f, 51f))
                    drawOval(ink, Offset(18f, 32f), Size(64f, 51f), style = Stroke(3f))
                    drawArc(ink, 180f, 180f, false, Offset(30f, 49f), Size(12f, 9f), style = Stroke(3f))
                    drawArc(ink, 180f, 180f, false, Offset(58f, 49f), Size(12f, 9f), style = Stroke(3f))
                    drawCircle(Color(0xFFFFB0B9), 5f, Offset(30f, 65f))
                    drawCircle(Color(0xFFFFB0B9), 5f, Offset(70f, 65f))
                    drawCircle(ink, 2.4f, Offset(50f, 62f))
                    drawArc(ink, 0f, 180f, false, Offset(44f, 64f), Size(12f, 7f), style = Stroke(2.3f))
                    drawLine(ink, Offset(23f, 61f), Offset(9f, 58f), 2f)
                    drawLine(ink, Offset(77f, 61f), Offset(91f, 58f), 2f)
                }
                else -> {
                    val bucket = Path().apply {
                        moveTo(23f, 41f)
                        lineTo(77f, 41f)
                        lineTo(69f, 84f)
                        lineTo(31f, 84f)
                        close()
                    }
                    drawPath(bucket, Color(0xFFFFF0DA))
                    drawPath(bucket, ink, style = Stroke(3f))
                    drawLine(Color(0xFFEE668E), Offset(36f, 44f), Offset(39f, 81f), 8f)
                    drawLine(Color(0xFFEE668E), Offset(63f, 44f), Offset(60f, 81f), 8f)
                    listOf(
                        Offset(28f, 35f),
                        Offset(39f, 24f),
                        Offset(50f, 34f),
                        Offset(62f, 24f),
                        Offset(73f, 36f),
                    ).forEach {
                        drawCircle(cream, 10f, it)
                        drawCircle(ink, 10f, it, style = Stroke(2f))
                    }
                    drawCircle(ink, 2.3f, Offset(44f, 61f))
                    drawCircle(ink, 2.3f, Offset(57f, 61f))
                    drawArc(ink, 0f, 180f, false, Offset(45f, 66f), Size(11f, 7f), style = Stroke(2.3f))
                }
            }
            drawCircle(Color(0xFFECCBFF), 3f, Offset(88f, 16f))
            drawLine(Color(0xFFECCBFF), Offset(9f, 78f), Offset(17f, 78f), 2f)
            drawLine(Color(0xFFECCBFF), Offset(13f, 74f), Offset(13f, 82f), 2f)
        }
    }
}
