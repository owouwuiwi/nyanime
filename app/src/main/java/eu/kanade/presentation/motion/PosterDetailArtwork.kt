package eu.kanade.presentation.motion

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.discovery.ArtworkPlaceholder
import eu.kanade.presentation.discovery.FadingAsyncImage
import eu.kanade.presentation.discovery.SourceHomeArtwork
import eu.kanade.presentation.discovery.sourceHomeArtworkIdentity
import eu.kanade.presentation.theme.nyanimeBrandColors
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

internal fun posterDetailHeight(width: Dp, tablet: Boolean, catalog: Boolean = false): Dp = when {
    catalog -> (width * 0.75f).coerceIn(240.dp, 380.dp)
    tablet -> 280.dp
    else -> (width * 0.66f).coerceIn(220.dp, 360.dp)
}

/** Loading and loaded screens use the same artwork bounds, gradient and title metrics. */
@Composable
internal fun PosterDetailHero(
    data: Any?,
    tablet: Boolean = false,
    catalog: Boolean = false,
    appBarPadding: Dp = 0.dp,
    background: Boolean = false,
    sourceArtwork: Boolean = false,
    actions: @Composable BoxScope.() -> Unit = {},
) = PosterDestinationContent {
    PosterDetailHeroContent(data, tablet, catalog, appBarPadding, background, sourceArtwork, actions)
}

@Composable
private fun PosterDetailHeroContent(
    data: Any?,
    tablet: Boolean,
    catalog: Boolean,
    appBarPadding: Dp,
    background: Boolean,
    sourceArtwork: Boolean,
    actions: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(posterDetailHeight(maxWidth, tablet, catalog) + appBarPadding)) {
            PosterDetailArtwork(
                data,
                Modifier.matchParentSize(),
                background,
                sourceArtwork,
                shade = Brush.verticalGradient(
                    0f to if (catalog) Color.Transparent else nyanimeBrandColors.artworkShade.copy(alpha = 0.6f),
                    (if (catalog) 0.5f else 0.4f) to Color.Transparent,
                    1f to MaterialTheme.colorScheme.background,
                ),
            )
            Box(Modifier.matchParentSize().posterForeground(), content = actions)
        }
    }
}

@Composable
internal fun PosterDetailTitle(
    title: String,
    modifier: Modifier = Modifier,
    catalog: Boolean = false,
    textAlign: TextAlign? = null,
) {
    Text(
        title.ifBlank { stringResource(MR.strings.unknown_title) },
        modifier,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = if (catalog) FontWeight.Bold else null,
        textAlign = textAlign,
    )
}

/** Keep the exact decoded source image throughout the flight; load the detail backdrop afterwards. */
@Composable
internal fun PosterDetailArtwork(
    data: Any?,
    modifier: Modifier = Modifier,
    background: Boolean = false,
    sourceArtwork: Boolean = false,
    shade: Brush? = null,
    decoration: @Composable BoxScope.() -> Unit = {},
) {
    val preview = posterDetailPreview()?.artwork
    val canRequest = posterRequestsEnabled()
    var requested by remember(data?.let(::sourceHomeArtworkIdentity)) { mutableStateOf(canRequest || preview == null) }
    LaunchedEffect(canRequest) { if (canRequest) requested = true }
    Box(modifier.posterDestination(shade).posterShade(shade)) {
        when {
            (data == null || !requested) && preview != null -> Image(
                preview,
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            data == null -> ArtworkPlaceholder(Modifier.fillMaxSize())
            sourceArtwork -> SourceHomeArtwork(
                data,
                Modifier.fillMaxSize(),
                background = background,
                initialPainter = preview,
            )
            else -> {
                val context = LocalContext.current
                val request = remember(data, context) {
                    ImageRequest.Builder(context).data(data).build()
                }
                FadingAsyncImage(
                    model = request,
                    imageLoader = SingletonImageLoader.get(context),
                    modifier = Modifier.fillMaxSize(),
                    previousPainter = preview,
                    reduceMotion = !modernMotionEnabled(),
                )
            }
        }
        decoration()
    }
}

@Composable
internal fun PosterLoadingBody(
    catalog: Boolean = false,
    tablet: Boolean = false,
    appBarPadding: Dp = 0.dp,
) {
    val title = posterDetailPreview()?.title.orEmpty()
    Column {
        PosterDetailHero(null, tablet, catalog, appBarPadding)
        Column(
            Modifier.posterForeground().padding(horizontal = 16.dp).padding(top = if (catalog) 16.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PosterDetailTitle(title, catalog = catalog)
            listOf(0.7f, 1f, 0.9f).forEach { width ->
                Spacer(
                    Modifier.fillMaxWidth(width).height(18.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)),
                )
            }
        }
    }
}

@Composable
internal fun PosterAnimeLoadingScreen(tablet: Boolean, navigateUp: () -> Unit) {
    if (posterDetailPreview() == null) {
        LoadingScreen()
        return
    }
    Scaffold(
        topBar = {
            AppBar(titleContent = {}, navigateUp = navigateUp, backgroundColor = Color.Transparent)
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        Box(
            Modifier.padding(
                start = padding.calculateStartPadding(direction),
                end = padding.calculateEndPadding(direction),
            ),
        ) {
            if (tablet) {
                TwoPanelBox(
                    startContent = { PosterLoadingBody(tablet = true, appBarPadding = padding.calculateTopPadding()) },
                    endContent = {},
                )
            } else {
                PosterLoadingBody(appBarPadding = padding.calculateTopPadding())
            }
        }
    }
}
