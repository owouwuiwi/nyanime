package eu.kanade.presentation.discovery

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.presentation.theme.NyanimeWordmark
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.AnimeImageFetcher
import eu.kanade.tachiyomi.data.coil.ArtworkRequestPolicy
import eu.kanade.tachiyomi.data.coil.artworkTimeout
import kotlinx.coroutines.delay
import tachiyomi.domain.entries.anime.model.AnimeCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

data class SourceHomeLogo(
    val sourceId: Long,
    val sourceName: String,
    val url: String,
    val name: String? = null,
    val background: String? = null,
)

@Composable
fun sourceHomeLogoEnabled(): Boolean {
    return if (LocalInspectionMode.current) {
        false
    } else {
        val preference = remember { Injekt.get<UiPreferences>().sourceHomeLogo() }
        val value by preference.collectPreferenceAsState()
        value
    }
}

@Composable
fun SourceHomeWordmark(
    logo: SourceHomeLogo?,
    modifier: Modifier = Modifier,
    enabled: Boolean = sourceHomeLogoEnabled(),
    refreshKey: Int = 0,
) {
    val motion = modernMotionEnabled()
    val context = LocalContext.current
    val activeLogo = logo.takeIf { enabled }
    var attempt by remember(activeLogo, refreshKey) { mutableIntStateOf(0) }
    val cover = activeLogo?.let { AnimeCover(-1, it.sourceId, false, it.url, 0) }
    val request = remember(activeLogo, motion, context, attempt) {
        ImageRequest.Builder(context)
            .data(cover)
            .apply { extras[AnimeImageFetcher.USE_CUSTOM_COVER_KEY] = false }
            .memoryCacheKey(activeLogo?.let { "home-logo:${it.sourceId}:${it.url}:$motion:$attempt" })
            .size(720, 144)
            // The complete wordmark (including its surface) shares a single transition below.
            .crossfade(false)
            .artworkTimeout(ArtworkRequestPolicy.HOME_TIMEOUT_MILLIS)
            .diskCachePolicy(if (attempt == 0) CachePolicy.ENABLED else CachePolicy.WRITE_ONLY)
            .apply {
                if (!motion && activeLogo?.url?.substringBefore('?')?.endsWith(".gif", ignoreCase = true) == true) {
                    decoderFactory(BitmapFactoryDecoder.Factory())
                }
            }
            .build()
    }
    Box(modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
        // Keep one composition, including the unbranded state, so a cached image cannot bypass the fade.
        // One header image: subcomposition also avoids the empty first frame on a memory-cache hit.
        SubcomposeAsyncImage(request, contentDescription = null, modifier = Modifier.fillMaxSize()) {
            val state by painter.state.collectAsState()
            var previous by remember(activeLogo?.sourceId) { mutableStateOf<SourceHomeLoadedLogo?>(null) }
            val loaded = (state as? AsyncImagePainter.State.Success)
                ?.takeIf { activeLogo != null && it.result.request.data == cover }
                ?.let { SourceHomeLoadedLogo(requireNotNull(activeLogo), it.painter) }
            LaunchedEffect(loaded) { if (loaded != null) previous = loaded }
            val error = (state as? AsyncImagePainter.State.Error)?.result
                ?.takeIf { it.request.data == cover }?.throwable
            LaunchedEffect(error, activeLogo) {
                // A stale, undecodable disk entry gets one fresh download, just like a transient network failure.
                val retry = error != null &&
                    (
                        ArtworkRequestPolicy.shouldRetry(error, attempt) ||
                            attempt == 0 &&
                            error is IllegalStateException
                        )
                if (activeLogo != null && retry) {
                    delay(ArtworkRequestPolicy.RETRY_DELAY_MILLIS)
                    attempt++
                }
            }
            LaunchedEffect(refreshKey) {
                if (refreshKey > 0 && activeLogo != null && state is AsyncImagePainter.State.Error) painter.restart()
            }
            SourceHomeWordmarkTransition(loaded ?: previous, motion)
        }
    }
}

internal data class SourceHomeLoadedLogo(val logo: SourceHomeLogo, val painter: Painter)

@Composable
internal fun SourceHomeWordmarkTransition(value: SourceHomeLoadedLogo?, motion: Boolean) {
    Crossfade(
        targetState = value,
        modifier = Modifier.fillMaxSize(),
        animationSpec = tween(if (motion) ModernMotion.RESIZE_MILLIS else 0),
        label = "homeWordmark",
    ) { displayed ->
        // Identical bounds for both endpoints: loading never moves the header or its actions.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            if (displayed == null) {
                NyanimeHomeWordmark()
            } else {
                SourceHomeBrandImage(displayed)
            }
        }
    }
}

@Composable
private fun NyanimeHomeWordmark() {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < .5f
    SubcomposeAsyncImage(
        model = if (darkTheme) R.raw.nyanime_wordmark_dark else R.raw.nyanime_wordmark_light,
        contentDescription = "Nyanime",
        modifier = Modifier.widthIn(max = 148.dp).height(36.dp),
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
        loading = { NyanimeWordmark() },
        error = { NyanimeWordmark() },
    )
}

@Composable
private fun SourceHomeBrandImage(displayed: SourceHomeLoadedLogo) {
    val logo = displayed.logo
    val dimensions = displayed.painter.intrinsicSize
    val compact = dimensions.width < dimensions.height * 1.6f && logo.name != null
    val background = when (logo.background) {
        "light" -> Color.White
        "dark" -> Color(0xFF101010)
        else -> Color.Transparent
    }
    Row(
        Modifier.widthIn(max = 240.dp).height(40.dp).clip(RoundedCornerShape(8.dp))
            .background(background).padding(horizontal = if (logo.background == null) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            displayed.painter,
            logo.sourceName,
            if (compact) Modifier.size(32.dp) else Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
        )
        if (compact) {
            Text(
                requireNotNull(logo.name),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when (logo.background) {
                    "light" -> Color.Black
                    "dark" -> Color.White
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
