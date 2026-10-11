package eu.kanade.presentation.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.posterForeground
import eu.kanade.presentation.motion.posterOpen
import eu.kanade.presentation.motion.posterSource
import eu.kanade.presentation.motion.posterSourcePlaceholder
import eu.kanade.presentation.motion.rememberPosterSource
import eu.kanade.presentation.privacy.nsfwPrivacy
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.discovery.LocalHomeItem
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import tachiyomi.domain.discovery.SectionState
import java.util.Locale
import androidx.compose.ui.res.stringResource as androidStringResource

@Composable
internal fun panoramaAccent(manga: Boolean): Color = if (manga) {
    Color(0xFF7BC8DD)
} else {
    Color(0xFFF0A561)
}

@Composable
internal fun PanoramaResumeCard(
    title: String,
    subtitle: String,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    manga: Boolean = false,
    menu: (@Composable (() -> Unit) -> Unit)? = null,
    artwork: @Composable (Modifier) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        onClick = onResume,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.heightIn(min = panoramaResumeHeight())
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(48.dp).height(68.dp).clip(RoundedCornerShape(8.dp))) { artwork(Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                progress?.let {
                    LinearProgressIndicator(
                        progress = { it.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(3.dp),
                        color = panoramaAccent(manga),
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
            }
            Box {
                IconButton(onClick = { if (menu == null) onResume() else expanded = true }) {
                    Icon(
                        if (menu !=
                            null
                        ) {
                            Icons.Outlined.MoreHoriz
                        } else if (manga) {
                            Icons.AutoMirrored.Outlined.MenuBook
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        if (menu !=
                            null
                        ) {
                            androidStringResource(R.string.home_panorama_options, title)
                        } else {
                            androidStringResource(R.string.home_resume_action)
                        },
                    )
                }
                if (menu !=
                    null
                ) {
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) { menu { expanded = false } }
                }
            }
        }
    }
}

@Composable
internal fun PanoramaUpdateCard(
    title: String,
    subtitle: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    manga: Boolean = false,
    onDetails: (() -> Unit)? = null,
    artwork: @Composable (Modifier) -> Unit,
) {
    val accent = panoramaAccent(manga)
    Surface(
        onClick = onOpen,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, accent.copy(alpha = .55f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(42.dp).height(58.dp).clip(RoundedCornerShape(8.dp))) {
                    artwork(Modifier.fillMaxSize())
                }
                Spacer(Modifier.weight(1f))
                onDetails?.let {
                    IconButton(onClick = it, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.Info,
                            androidStringResource(R.string.home_named_details, title),
                            Modifier.size(20.dp),
                        )
                    }
                }
            }
            Text(
                androidStringResource(
                    if (manga) R.string.home_panorama_new_chapter else R.string.home_panorama_new_episode,
                ),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PanoramaLocalAnimeRow(
    state: SectionState<List<LocalHomeItem>>,
    onOpen: (LocalHomeItem) -> Unit,
    emptyMessage: String,
    onHide: ((LocalHomeItem) -> Unit)?,
    resume: Boolean,
    onPlay: (LocalHomeItem) -> Unit,
) {
    LoadNotice(state.loading, state.error, showLoadingIndicator = false)
    val entries = state.data.orEmpty()
    if (!state.loading && entries.isEmpty() && state.error == null) {
        Text(
            emptyMessage,
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    HomeLoadingTransition(
        loading = state.awaitingContent,
        placeholder = { if (resume) PanoramaWatchResumeSkeleton() else HomePosterRowSkeleton() },
    ) {
        if (resume) {
            BoxWithConstraints(Modifier.fillMaxWidth().privacyRegion(PrivacyArea.RESUME)) {
                val cardWidth = PanoramaResumeLayout.width(maxWidth, entries.size)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(entries, key = { it.anime.id }) { item ->
                        val cloudResume = eu.kanade.tachiyomi.ui.cloud.rememberCloudResume(
                            "anime",
                            item.anime.id,
                            item.episode.id,
                        )
                        val poster =
                            rememberPosterSource(item.anime, RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
                        val openDetails = posterOpen(poster, item.anime.title) { onOpen(item) }
                        PanoramaWatchResumeCard(
                            title = item.anime.title,
                            episode = item.episode.name,
                            timing = item.episode.totalSeconds.takeIf { it > 0 }?.let { total ->
                                androidStringResource(
                                    R.string.home_panorama_watch_progress,
                                    panoramaTime(item.episode.lastSecondSeen.coerceIn(0L, total)),
                                    panoramaTime(total),
                                )
                            }?.let { timing ->
                                cloudResume.firstOrNull()?.let { "$timing · ${it.deviceName}" } ?: timing
                            },
                            onResume = { onPlay(item) },
                            modifier = Modifier.width(cardWidth).nsfwPrivacy(item.anime),
                            foregroundModifier = Modifier.posterForeground(poster),
                            progress = item.progress,
                            menu = { close ->
                                eu.kanade.tachiyomi.ui.cloud.CloudResumeMenu(cloudResume, manga = false) { position ->
                                    close()
                                    onPlay(item.copy(forcedStartPositionMs = position))
                                }
                                DropdownMenuItem(text = {
                                    Text(androidStringResource(R.string.home_resume_action))
                                }, onClick = {
                                    close()
                                    onPlay(item)
                                })
                                DropdownMenuItem(text = {
                                    Text(androidStringResource(R.string.home_open_title_details))
                                }, onClick = {
                                    close()
                                    openDetails()
                                })
                                item.finale?.let { finale ->
                                    DropdownMenuItem(text = {
                                        Text(androidStringResource(R.string.home_resume_ending))
                                    }, onClick = {
                                        close()
                                        onPlay(
                                            item.copy(
                                                episode = finale,
                                                finale = null,
                                                forcedStartPositionMs = finale.lastSecondSeen,
                                            ),
                                        )
                                    })
                                }
                                onHide?.let { hide ->
                                    DropdownMenuItem(text = {
                                        Text(
                                            androidStringResource(
                                                R.string.home_hide_resume,
                                                item.anime.title,
                                            ),
                                        )
                                    }, onClick = {
                                        close()
                                        hide(item)
                                    })
                                }
                            },
                        ) { modifier ->
                            SourceHomeArtwork(
                                item.anime,
                                modifier.posterSource(poster),
                                item.anime.title,
                                initialPainter = posterSourcePlaceholder(poster),
                                onPainterReady = { poster.painter = it },
                            )
                        }
                    }
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth().privacyRegion(PrivacyArea.RESUME)) {
                val width = ((maxWidth - 52.dp) / 2).coerceAtLeast(
                    150.dp * LocalDensity.current.fontScale.coerceAtMost(1.4f),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(entries, key = { it.episode.id }) { item ->
                        PanoramaUpdateCard(item.anime.title, item.episode.name, {
                            onPlay(item)
                        }, Modifier.width(width).nsfwPrivacy(item.anime), onDetails = { onOpen(item) }) {
                            SourceHomeArtwork(item.anime, it, item.anime.title)
                        }
                    }
                }
            }
        }
    }
}

private fun panoramaTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >=
        3600
    ) {
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(
            Locale.ROOT,
            "%d:%02d",
            seconds / 60,
            seconds % 60,
        )
    }
}

@Composable
private fun panoramaResumeHeight() = with(LocalDensity.current) {
    (
        MaterialTheme.typography.titleSmall.lineHeight.toDp() *
            2 +
            MaterialTheme.typography.bodySmall.lineHeight.toDp() *
            2 +
            12.dp
        ).coerceAtLeast(68.dp) +
        24.dp
}

@Composable
internal fun PanoramaResumeSkeleton() {
    HomeSkeleton(Modifier.padding(horizontal = 20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(2) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = panoramaResumeHeight()).background(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        RoundedCornerShape(16.dp),
                    ).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkeletonBlock(Modifier.size(48.dp, 68.dp))
                    Column(
                        Modifier.weight(1f).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SkeletonBlock(Modifier.fillMaxWidth(.85f).height(16.dp))
                        SkeletonBlock(Modifier.fillMaxWidth(.65f).height(12.dp))
                        SkeletonBlock(Modifier.fillMaxWidth().height(3.dp))
                    }
                    SkeletonBlock(Modifier.size(24.dp))
                }
            }
        }
    }
}
