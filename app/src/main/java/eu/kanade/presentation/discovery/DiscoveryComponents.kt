package eu.kanade.presentation.discovery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.presentation.motion.posterForeground
import eu.kanade.presentation.motion.posterOpen
import eu.kanade.presentation.motion.posterSource
import eu.kanade.presentation.motion.posterSourcePlaceholder
import eu.kanade.presentation.motion.rememberPosterSource
import eu.kanade.presentation.privacy.nsfwPrivacy
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.discovery.LocalHomeItem
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import tachiyomi.domain.discovery.CatalogAnime
import tachiyomi.domain.discovery.CatalogFeed
import tachiyomi.domain.discovery.SectionState
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.entries.anime.model.asAnimeCover
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.ui.res.stringResource as androidStringResource

fun CatalogFeed.displayTitle(): String = when (this) {
    CatalogFeed.TRENDING -> "In tendenza"
    CatalogFeed.SEASON -> "Questa stagione"
    CatalogFeed.TOP -> "Più apprezzati"
    CatalogFeed.NEXT_SEASON -> "Prossima stagione"
    CatalogFeed.WEEK -> "Questa settimana"
    CatalogFeed.SEARCH -> "Cerca anime"
}

fun airingLabel(anime: CatalogAnime): String? = anime.airingAt?.let {
    val date = Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())
    "Ep. ${anime.airingEpisode ?: "?"} · ${DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(date)}"
}

fun metadataLabel(value: String?): String? = when (value) {
    "RELEASING" -> "In corso"
    "FINISHED" -> "Concluso"
    "NOT_YET_RELEASED" -> "In arrivo"
    "CANCELLED" -> "Annullato"
    "HIATUS" -> "In pausa"
    "WINTER" -> "Inverno"
    "SPRING" -> "Primavera"
    "SUMMER" -> "Estate"
    "FALL" -> "Autunno"
    "TV_SHORT" -> "Serie breve"
    "MOVIE" -> "Film"
    "SPECIAL" -> "Speciale"
    "PREQUEL" -> "Prequel"
    "SEQUEL" -> "Sequel"
    "SIDE_STORY" -> "Storia secondaria"
    "ALTERNATIVE" -> "Versione alternativa"
    "SPIN_OFF" -> "Spin-off"
    else -> value?.replace('_', ' ')
}

@Composable
fun SectionHeader(title: String, more: (() -> Unit)? = null) {
    if (!LocalNyanimeStyle.current) return eu.kanade.presentation.discovery.legacy.SectionHeader(title, more)
    Row(
        Modifier.fillMaxWidth().heightIn(min = homeSectionHeaderHeight())
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f).semantics {
                heading()
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (more != null) {
            IconButton(onClick = more) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    androidStringResource(R.string.home_show_section, title),
                    Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun LoadNotice(
    loading: Boolean,
    error: String?,
    stale: Boolean = false,
    showLoadingIndicator: Boolean = true,
    retry: (() -> Unit)? = null,
) {
    if (loading && showLoadingIndicator) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    if (stale || error != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (stale) {
                Text(
                    androidStringResource(R.string.home_saved_offline),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (retry != null &&
                !loading
            ) {
                TextButton(onClick = retry) { Text(androidStringResource(R.string.room_retry)) }
            }
        }
    }
}

@Composable
fun PosterCard(
    title: String,
    cover: Any?,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badges: List<String> = emptyList(),
    subtitleMaxLines: Int = 3,
    artworkRefreshKey: Int = 0,
) {
    if (!LocalNyanimeStyle.current) {
        return eu.kanade.presentation.discovery.legacy.PosterCard(
            title,
            cover,
            subtitle,
            onClick,
            modifier,
            badges,
            subtitleMaxLines,
            artworkRefreshKey,
        )
    }
    var information by rememberSaveable(title) { mutableStateOf(false) }
    val poster = rememberPosterSource(cover)
    val openDetails = posterOpen(poster, title, onClick)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = modernMotionEnabled()
    val pressAlpha = animateFloatAsState(if (motion && pressed) 0.9f else 1f, tween(100), label = "posterPress")
    if (information) {
        TitleInformationSheet(title, badges.joinToString(" · "), subtitle, { information = false }, onClick)
    }
    Column(
        modifier.nsfwPrivacy(cover, badges).width(HomeLayout.posterWidth(LocalDensity.current.fontScale))
            .graphicsLayer {
                alpha = pressAlpha.value
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = androidStringResource(R.string.home_open_named_title, title),
                onClick = openDetails,
            ).padding(bottom = 8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (cover is AnimeCover || cover is Anime) {
                SourceHomeArtwork(
                    cover,
                    Modifier.fillMaxSize().posterSource(poster),
                    refreshKey = artworkRefreshKey,
                    initialPainter = posterSourcePlaceholder(poster),
                    onPainterReady = { poster.painter = it },
                )
            } else {
                AsyncImage(
                    model = cover,
                    placeholder = posterSourcePlaceholder(poster),
                    error = posterSourcePlaceholder(poster),
                    onSuccess = { poster.painter = it.painter },
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().posterSource(poster),
                    contentScale = ContentScale.Crop,
                )
            }
            if (badges.isNotEmpty()) {
                Text(
                    badges.joinToString(" · "),
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .posterForeground(poster)
                        .background(Color.Black.copy(alpha = 0.84f)).padding(horizontal = 6.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!subtitle.isNullOrBlank() || badges.isNotEmpty()) {
                IconButton(onClick = {
                    information = true
                }, modifier = Modifier.align(Alignment.TopEnd).posterForeground(poster)) {
                    Icon(
                        Icons.Outlined.Info,
                        androidStringResource(R.string.home_title_information, title),
                        Modifier.background(Color.Black.copy(alpha = 0.72f), CircleShape).padding(3.dp),
                        tint = Color.White,
                    )
                }
            }
        }
        Text(
            title,
            Modifier.posterForeground(poster).padding(top = 8.dp),
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            subtitle.orEmpty(),
            modifier = Modifier.posterForeground(poster),
            maxLines = subtitleMaxLines.coerceIn(1, 2),
            minLines = subtitleMaxLines.coerceIn(1, 2),
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CatalogRow(items: List<CatalogAnime>, onClick: (CatalogAnime) -> Unit, calendar: Boolean = false) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { "${it.id.provider}:${it.id.value}:${it.airingAt}" }) { anime ->
            PosterCard(
                anime.title,
                anime.cover,
                if (calendar) {
                    airingLabel(anime)
                } else {
                    listOfNotNull(
                        anime.score?.let { "★ $it/100" },
                        metadataLabel(anime.format),
                    ).joinToString(" · ")
                },
                { onClick(anime) },
            )
        }
    }
}

@Composable
fun FeaturedCarousel(items: List<CatalogAnime>, onClick: (CatalogAnime) -> Unit, autoplay: Boolean = false) {
    if (!LocalNyanimeStyle.current) return eu.kanade.presentation.discovery.legacy.FeaturedCarousel(items, onClick)
    PanoramaCarousel(
        items = items.take(8),
        itemKey = { "${it.id.provider}:${it.id.value}" },
        title = { it.title },
        metadata = { anime ->
            listOfNotNull(anime.score?.let { "★ $it/100" }, anime.genres.joinToString(" · "))
                .filter { it.isNotBlank() }.joinToString(" · ")
        },
        artworkData = { it.cover.orEmpty() },
        autoplay = autoplay,
        onOpen = onClick,
    ) { item, modifier, poster ->
        AsyncImage(
            model = item.cover,
            contentDescription = item.title,
            modifier = modifier.posterSource(poster),
            placeholder = posterSourcePlaceholder(poster),
            error = posterSourcePlaceholder(poster),
            onSuccess = { poster.painter = it.painter },
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
fun LocalAnimeRow(
    state: SectionState<List<LocalHomeItem>>,
    onOpen: (LocalHomeItem) -> Unit,
    emptyMessage: String = androidStringResource(R.string.home_library_empty),
    onHide: ((LocalHomeItem) -> Unit)? = null,
    resume: Boolean = false,
    onPlay: (LocalHomeItem) -> Unit,
) {
    if (!LocalNyanimeStyle.current) {
        return eu.kanade.presentation.discovery.legacy.LocalAnimeRow(state, onOpen, emptyMessage, onHide, onPlay)
    }
    PanoramaLocalAnimeRow(state, onOpen, emptyMessage, onHide, resume, onPlay)
}
