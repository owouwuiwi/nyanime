package eu.kanade.presentation.discovery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.data.coil.ArtworkHttpException
import eu.kanade.tachiyomi.data.coil.ArtworkRequestPolicy
import eu.kanade.tachiyomi.data.coil.artworkTimeout
import eu.kanade.tachiyomi.data.coil.useBackground
import eu.kanade.tachiyomi.data.discovery.SourceHomeArtworkResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import tachiyomi.domain.discovery.homeItemKey
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Ignores artwork revisions while keeping sources and distinct entries isolated. */
internal fun sourceHomeArtworkIdentity(data: Any): Any = when (data) {
    is Anime -> "anime:${data.homeItemKey}"
    is AnimeCover -> "cover:${data.sourceId}:${data.animeId}:" + if (data.animeId > 0) "" else data.url.orEmpty()
    else -> data
}

/** Metadata and playback changes do not invalidate an unchanged image request. */
internal fun sourceHomeArtworkRevision(data: Any, background: Boolean): Any = when (data) {
    is Anime -> listOf(
        sourceHomeArtworkIdentity(data),
        data.id,
        data.favorite,
        data.thumbnailUrl,
        data.coverLastModified,
        data.backgroundUrl.takeIf { background },
        data.backgroundLastModified.takeIf { background },
    )
    else -> data
}

/** Retries only a failed image. Refresh and retry reuse healthy caches and never alter library data. */
@Composable
fun SourceHomeArtwork(
    data: Any,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    background: Boolean = false,
    refreshKey: Int = 0,
    imageLoader: ImageLoader = SingletonImageLoader.get(LocalContext.current),
    initialPainter: Painter? = null,
    onPainterReady: (Painter) -> Unit = {},
) {
    val reduceMotion = !appMotionEnabled()
    key(sourceHomeArtworkIdentity(data), background) {
        var previousPainter by remember { mutableStateOf(initialPainter) }
        key(sourceHomeArtworkRevision(data, background)) {
            var attempt by remember { mutableIntStateOf(0) }
            var failure by remember { mutableStateOf<Throwable?>(null) }
            var artwork by remember { mutableStateOf(data) }
            var useBackground by remember { mutableStateOf(background) }
            var detailsAttempted by remember { mutableStateOf(false) }
            var recovering by remember { mutableStateOf(false) }
            var manualRetry by remember { mutableStateOf(false) }
            var lastRefreshKey by remember { mutableIntStateOf(refreshKey) }
            val context = LocalContext.current
            val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
            val active = lifecycle.isAtLeast(Lifecycle.State.STARTED)
            val request = remember(artwork, useBackground, attempt, context) {
                ImageRequest.Builder(context).data(artwork).useBackground(useBackground)
                    .artworkTimeout(ArtworkRequestPolicy.HOME_TIMEOUT_MILLIS)
                    // A failed decode must not keep rereading an unusable disk entry on retry.
                    .diskCachePolicy(if (attempt == 0) CachePolicy.ENABLED else CachePolicy.WRITE_ONLY)
                    .build()
            }
            LaunchedEffect(refreshKey) {
                if (lastRefreshKey == refreshKey) return@LaunchedEffect
                lastRefreshKey = refreshKey
                // Refresh recovers failures; healthy and resolved artwork keeps its painter
                // and request instead of restarting every visible cell in the same frame.
                if (failure != null) {
                    failure = null
                    detailsAttempted = false
                    manualRetry = true
                    attempt++
                }
            }
            LaunchedEffect(failure, active) {
                if (!active) return@LaunchedEffect
                val error = failure ?: return@LaunchedEffect
                if (data is Anime && useBackground && !data.thumbnailUrl.isNullOrBlank()) {
                    useBackground = false
                    failure = null
                    return@LaunchedEffect
                }
                if (data is Anime &&
                    !detailsAttempted &&
                    (error is IllegalStateException || error is ArtworkHttpException && error.code in listOf(404, 410))
                ) {
                    recovering = true
                    try {
                        val resolved = Injekt.get<SourceHomeArtworkResolver>().resolve(data, refresh = manualRetry)
                        detailsAttempted = true
                        val cover = resolved.cover ?: data.thumbnailUrl
                        val backdrop = resolved.background ?: data.backgroundUrl
                        check(!cover.isNullOrBlank() || useBackground && !backdrop.isNullOrBlank()) {
                            "No cover available"
                        }
                        artwork = data.copy(thumbnailUrl = cover, backgroundUrl = backdrop)
                        failure = null
                        attempt++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (cause: Exception) {
                        detailsAttempted = true
                        failure = cause
                    } finally {
                        recovering = false
                    }
                    return@LaunchedEffect
                }
                if (ArtworkRequestPolicy.shouldRetry(error, attempt)) {
                    delay(ArtworkRequestPolicy.RETRY_DELAY_MILLIS)
                    failure = null
                    attempt++
                }
            }
            Box(modifier, contentAlignment = Alignment.Center) {
                ArtworkPlaceholder(Modifier.fillMaxSize())
                if (!active && previousPainter != null) {
                    Image(
                        painter = previousPainter!!,
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (active) {
                    key(attempt) {
                        FadingAsyncImage(
                            model = request,
                            imageLoader = imageLoader,
                            contentDescription = contentDescription,
                            modifier = Modifier.fillMaxSize(),
                            previousPainter = previousPainter,
                            reduceMotion = reduceMotion,
                            onSuccess = {
                                previousPainter = it.painter
                                onPainterReady(it.painter)
                                failure = null
                            },
                            onError = {
                                failure = it.result.throwable
                            },
                        )
                    }
                }
                if (failure != null && !recovering) {
                    IconButton(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.55f),
                            contentColor = Color.White,
                        ),
                        onClick = {
                            failure = null
                            detailsAttempted = false
                            manualRetry = true
                            attempt++
                        },
                    ) {
                        Icon(Icons.Outlined.Refresh, stringResource(AYMR.strings.home_artwork_retry))
                    }
                }
            }
        }
    }
}

/** A still, low-contrast placeholder avoids continuous animation work for off-screen rows. */
@Composable
fun ArtworkPlaceholder(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.background(
            Brush.linearGradient(listOf(colors.surfaceContainerHigh, colors.surfaceContainerLowest)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Image,
            contentDescription = null,
            tint = colors.onSurface.copy(alpha = 0.12f),
            modifier = Modifier.size(32.dp),
        )
    }
}
