package it.lorelore.nyanime.presentation.themes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.presentation.workinfo.WorkInfoState
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.workinfo.WorkKind
import eu.kanade.tachiyomi.data.workinfo.WorkProvider
import eu.kanade.tachiyomi.util.system.openInBrowser
import it.lorelore.nyanime.themes.AnimeTheme
import it.lorelore.nyanime.themes.AnimeThemesRepository
import it.lorelore.nyanime.themes.ThemeCatalogue
import it.lorelore.nyanime.themes.ThemeChoices
import it.lorelore.nyanime.themes.ThemeIdentity
import it.lorelore.nyanime.themes.ThemeKind
import it.lorelore.nyanime.themes.ThemeVideo
import it.lorelore.nyanime.themes.ThemeVideoKind
import kotlinx.coroutines.CancellationException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Native detail content; neither metadata nor search claims a playback session. */
@Composable
internal fun AnimeThemeSection(work: WorkInfoState, seasonContainer: Boolean) {
    if (!work.enabled || seasonContainer) return
    val summary = work.information?.work
    if (summary != null && summary.reference.kind != WorkKind.ANIME) return
    val reference = summary?.reference ?: work.resolution.reference
    if (reference != null && reference.kind != WorkKind.ANIME) return
    val identity = summary?.reference?.takeIf { it.provider == WorkProvider.ANILIST && it.isValid() }?.let {
        ThemeIdentity(it.id.toLongOrNull(), summary.malId)
    }
    val repository = remember { Injekt.get<AnimeThemesRepository>() }
    val choices = remember { Injekt.get<ThemeChoices>() }
    var catalogue by remember(identity) { mutableStateOf(ThemeCatalogue(loading = identity != null)) }
    var retry by remember(identity) { mutableIntStateOf(0) }
    var previousRetry by remember(identity) { mutableIntStateOf(0) }
    var kindName by rememberSaveable(work.identity.localKey) { mutableStateOf(ThemeKind.OPENING.name) }
    val kind = ThemeKind.valueOf(kindName)
    var expanded by rememberSaveable(work.identity.localKey, kindName) { mutableStateOf(false) }
    var choosing by remember(identity) { mutableStateOf<Pair<AnimeTheme, ThemeVideoKind>?>(null) }
    var choiceRevision by remember(identity) { mutableIntStateOf(0) }
    val sessionChoices = remember(identity) { mutableStateMapOf<String, ThemeVideo>() }
    val context = LocalContext.current
    val play: (ThemeVideo) -> Unit = { video ->
        if (it.lorelore.nyanime.themes.ThemeVideoIds.valid(video.id)) {
            context.openInBrowser("https://www.youtube.com/watch?v=${video.id}")
        }
    }
    LaunchedEffect(identity, work.network, work.incognito, retry) {
        if (identity == null) return@LaunchedEffect
        try {
            val force = retry != previousRetry
            previousRetry = retry
            repository.observe(identity, work.network, !work.incognito, force).collect { catalogue = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            catalogue = catalogue.copy(loading = false, partialFailure = true)
        }
    }
    val motion = modernMotionEnabled()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.anime_themes_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "AnimeThemes · Jikan",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (identity != null) {
                IconButton(onClick = { retry++ }, enabled = work.network && !catalogue.loading) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.anime_themes_retry))
                }
            }
        }
        if (identity == null) {
            if (work.loading) {
                ThemeSkeleton()
            } else {
                Text(stringResource(R.string.anime_themes_identify), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { work.choosing = true }, enabled = work.network) {
                    Text(stringResource(R.string.anime_themes_associate))
                }
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeKind.entries.forEach { value ->
                FilterChip(
                    selected = kind == value,
                    onClick = { kindName = value.name },
                    label = {
                        Text(
                            stringResource(
                                if (value ==
                                    ThemeKind.OPENING
                                ) {
                                    R.string.anime_themes_opening
                                } else {
                                    R.string.anime_themes_ending
                                },
                            ),
                        )
                    },
                )
            }
        }
        AnimatedContent(
            targetState = kind to (catalogue.loading && catalogue.songs.isEmpty()),
            transitionSpec = {
                ModernMotion.transform(motion).using(
                    if (motion) SizeTransform { _, _ -> tween(ModernMotion.RESIZE_MILLIS) } else null,
                )
            },
            label = "theme-kind",
        ) { (displayedKind, initialLoading) ->
            val songs = catalogue.songs.filter { it.kind == displayedKind }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (initialLoading) ThemeSkeleton()
                if (songs.isEmpty() && !catalogue.loading && !catalogue.partialFailure) {
                    Text(
                        stringResource(if (work.network) R.string.anime_themes_none else R.string.anime_themes_offline),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                (if (expanded) songs else songs.take(2)).forEach { song ->
                    key(song.key) {
                        // A revision invalidates saved choices without persisting an incognito session.
                        @Suppress("UNUSED_VARIABLE")
                        val observedRevision = choiceRevision
                        ThemeSongCard(song, work.network, { variant ->
                            sessionChoices["${song.key}:$variant"]
                                ?: if (work.incognito) null else choices.selected(identity, song, variant)
                        }, { variant, selected ->
                            val linked = song.videos.filter { it.kind == variant }
                            val direct = selected ?: linked.singleOrNull()
                            if (direct != null) play(direct) else choosing = song to variant
                        }, { variant -> choosing = song to variant })
                    }
                }
                if (songs.size > 2) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) {
                                stringResource(
                                    R.string.anime_themes_less,
                                )
                            } else {
                                stringResource(R.string.anime_themes_more_songs, songs.size)
                            },
                        )
                    }
                }
            }
        }
        if (catalogue.partialFailure) {
            Text(
                stringResource(R.string.anime_themes_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                retry++
            }, enabled = work.network && !catalogue.loading) { Text(stringResource(R.string.anime_themes_retry)) }
        }
    }
    choosing?.let { (song, variant) ->
        ThemeVideoSheet(
            song,
            variant,
            summary?.title ?: work.identity.title,
            work.network,
            onDismiss = { choosing = null },
            onSelect = { video ->
                if (!work.incognito && identity != null) choices.select(identity, song, variant, video)
                sessionChoices["${song.key}:$variant"] = video.copy(kind = variant)
                choiceRevision++
                choosing = null
                play(video)
            },
        )
    }
}

@Composable
private fun ThemeSongCard(
    song: AnimeTheme,
    online: Boolean,
    selected: (ThemeVideoKind) -> ThemeVideo?,
    onPlay: (ThemeVideoKind, ThemeVideo?) -> Unit,
    onChange: (ThemeVideoKind) -> Unit,
) {
    val variants = listOf(ThemeVideoKind.SEQUENCE, ThemeVideoKind.FULL)
    val selections = variants.associateWith(selected)
    val preview = selections.values.firstOrNull { it != null } ?: song.videos.firstOrNull()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeThumbnail(
                    preview?.id,
                    song.versions.any {
                        it.spoiler
                    } ||
                        preview?.spoiler == true,
                    online,
                    Modifier.size(96.dp, 54.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        song.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (song.artists.isNotEmpty()) {
                        Text(
                            song.artists.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val ranges = song.versions.mapNotNull { it.episodes }.distinct()
            if (ranges.isNotEmpty()) {
                Text(
                    stringResource(R.string.anime_themes_episodes, ranges.joinToString(" · ")),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            variants.forEach { variant ->
                val chosen = selections[variant]
                val linked = song.videos.filter { it.kind == variant }
                val available = chosen != null || linked.isNotEmpty()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = {
                        onPlay(variant, chosen)
                    }, enabled = online, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(
                            if (available) Icons.Outlined.PlayArrow else Icons.Outlined.Search,
                            null,
                            Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(
                                when {
                                    variant == ThemeVideoKind.SEQUENCE && available -> R.string.anime_themes_sequence
                                    variant == ThemeVideoKind.SEQUENCE -> R.string.anime_themes_search_sequence
                                    available -> R.string.anime_themes_full
                                    else -> R.string.anime_themes_search_full
                                },
                            ),
                            Modifier.padding(start = 8.dp),
                        )
                    }
                    if (available) {
                        IconButton(onClick = {
                            onChange(variant)
                        }, enabled = online) { Icon(Icons.Outlined.Edit, stringResource(R.string.anime_themes_change)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSkeleton() {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.fillMaxWidth().height(200.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            repeat(3) { index ->
                Box(
                    Modifier.fillMaxWidth(
                        if (index ==
                            0
                        ) {
                            0.65f
                        } else {
                            0.9f
                        },
                    ).height(
                        if (index ==
                            2
                        ) {
                            48.dp
                        } else {
                            20.dp
                        },
                    ).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
                )
            }
        }
    }
}
