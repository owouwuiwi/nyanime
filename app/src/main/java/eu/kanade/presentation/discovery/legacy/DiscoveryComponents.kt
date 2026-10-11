package eu.kanade.presentation.discovery.legacy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.discovery.SourceHomeArtwork
import eu.kanade.presentation.privacy.nsfwPrivacy
import eu.kanade.presentation.privacy.privacyRegion
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
    Row(
        Modifier.fillMaxWidth().padding(
            horizontal = 16.dp,
            vertical = 8.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f).semantics {
                heading()
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (more != null) TextButton(onClick = more) { Text("Mostra tutti") }
    }
}

@Composable
fun LoadNotice(loading: Boolean, error: String?, stale: Boolean = false, retry: (() -> Unit)? = null) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    if (stale || error != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (stale) Text("Dati salvati · aggiornamento non disponibile", style = MaterialTheme.typography.labelSmall)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (retry != null && !loading) TextButton(onClick = retry) { Text("Riprova") }
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
    Column(
        modifier.nsfwPrivacy(cover, badges).width((144 * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)).dp)
            .clickable(onClick = onClick).padding(bottom = 8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(
                144f / 208f,
            ).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (cover is AnimeCover || cover is Anime) {
                SourceHomeArtwork(cover, Modifier.fillMaxSize(), refreshKey = artworkRefreshKey)
            } else {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (badges.isNotEmpty()) {
                Text(
                    badges.joinToString(" · "),
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)).padding(6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(
            title,
            Modifier.padding(top = 8.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                maxLines = subtitleMaxLines,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun CatalogRow(items: List<CatalogAnime>, onClick: (CatalogAnime) -> Unit, calendar: Boolean = false) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
fun FeaturedCarousel(items: List<CatalogAnime>, onClick: (CatalogAnime) -> Unit) {
    if (items.isEmpty()) return
    val pager = rememberPagerState { items.size.coerceAtMost(5) }
    HorizontalPager(state = pager, contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 12.dp) { index ->
        val anime = items[index]
        Card(onClick = { onClick(anime) }, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 340.dp)) {
                AsyncImage(
                    anime.banner ?: anime.cover,
                    anime.title,
                    Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    Modifier.fillMaxWidth().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.15f),
                                Color.Black.copy(alpha = 0.95f),
                            ),
                        ),
                    ).padding(20.dp),

                ) {
                    Text(
                        "IN EVIDENZA · ${index + 1}/${pager.pageCount}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(100.dp))
                    Text(
                        anime.title,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val highlights = listOfNotNull(
                        anime.score?.let { "★ $it/100" },
                        anime.genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    Text(highlights, color = Color.White, style = MaterialTheme.typography.labelMedium)
                    anime.synopsis?.let {
                        Text(
                            it,
                            Modifier.padding(top = 8.dp),
                            color = Color.White,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "Scopri l’anime →",
                        Modifier.padding(top = 12.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
fun LocalAnimeRow(
    state: SectionState<List<LocalHomeItem>>,
    onOpen: (LocalHomeItem) -> Unit,
    emptyMessage: String = "Gli anime che segui compariranno qui.",
    onHide: ((LocalHomeItem) -> Unit)? = null,
    onPlay: (LocalHomeItem) -> Unit,
) {
    LoadNotice(state.loading, state.error)
    val items = state.data.orEmpty()
    if (!state.loading && items.isEmpty() && state.error == null) {
        Text(
            emptyMessage,
            Modifier.padding(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    LazyRow(
        modifier = Modifier.privacyRegion(PrivacyArea.RESUME),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.anime.id }) { item ->
            Column(
                Modifier.nsfwPrivacy(item.anime).width((160 * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)).dp),
            ) {
                PosterCard(item.anime.title, item.anime.asAnimeCover(), item.episode.name, { onOpen(item) })
                if (item.progress > 0) {
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (item.finale != null) {
                    Text("Prossimo episodio", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = {
                        onPlay(
                            item.copy(
                                episode = item.finale,
                                finale = null,
                                forcedStartPositionMs = item.finale.lastSecondSeen,
                            ),
                        )
                    }) {
                        Text("Riprendi il finale")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onPlay(item) }, modifier = Modifier.weight(1f)) {
                        Text(if (item.progress > 0) "Riprendi" else "Guarda")
                    }
                    if (onHide != null) {
                        IconButton(onClick = { onHide(item) }) {
                            Icon(Icons.Outlined.VisibilityOff, "Nascondi ${item.anime.title} da Continua a guardare")
                        }
                    }
                }
            }
        }
    }
}
