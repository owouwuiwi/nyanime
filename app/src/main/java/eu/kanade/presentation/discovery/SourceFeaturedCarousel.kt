package eu.kanade.presentation.discovery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.posterSource
import eu.kanade.presentation.motion.posterSourcePlaceholder
import eu.kanade.presentation.privacy.nsfwPrivacy
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.tachiyomi.R
import tachiyomi.domain.discovery.homeItemKey
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime
import androidx.compose.ui.res.stringResource as androidStringResource

/** Every title, image and action comes from the generic extension Home contract. */
@Composable
fun SourceFeaturedCarousel(
    items: List<Anime>,
    refreshKey: Int = 0,
    title: String = androidStringResource(R.string.home_panorama_featured),
    onSources: ((Anime) -> Unit)? = null,
    autoplay: Boolean = false,
    onClick: (Anime) -> Unit,
) {
    if (!LocalNyanimeStyle.current) {
        return eu.kanade.presentation.discovery.legacy.SourceFeaturedCarousel(items, refreshKey, onClick)
    }
    PanoramaCarousel(
        items = items.distinctBy { it.homeItemKey },
        itemKey = { it.homeItemKey },
        title = { it.title },
        metadata = {
            (it.homePresentation?.badges.orEmpty() + it.homePresentation?.details.orEmpty()).joinToString(" · ")
        },
        artworkData = { it },
        privacyModifier = { Modifier.nsfwPrivacy(it) },
        heading = title,
        autoplay = autoplay,
        onOpen = onClick,
        actions = { anime ->
            if (onSources != null && anime.homePresentation?.choices.orEmpty().size > 1) {
                Surface(shape = CircleShape, color = Color.Black.copy(alpha = .8f), contentColor = Color.White) {
                    IconButton(onClick = { onSources(anime) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.SwapHoriz, androidStringResource(R.string.home_panorama_sources))
                    }
                }
            }
        },
    ) { anime, modifier, poster ->
        Box(modifier.nsfwPrivacy(anime)) {
            SourceHomeArtwork(
                data = anime,
                refreshKey = refreshKey,
                contentDescription = anime.title,
                modifier = Modifier.matchParentSize().posterSource(poster),
                initialPainter = posterSourcePlaceholder(poster),
                onPainterReady = { poster.painter = it },
            )
        }
    }
}
