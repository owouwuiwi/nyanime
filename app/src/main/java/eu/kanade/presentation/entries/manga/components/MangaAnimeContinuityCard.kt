package eu.kanade.presentation.entries.manga.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.ContinuityCard
import eu.kanade.presentation.components.ContinuityDestination
import eu.kanade.presentation.components.ContinuityDestinationSheet
import eu.kanade.presentation.components.ContinuityMissingCopySheet
import eu.kanade.tachiyomi.data.track.AnimeMangaContinuity
import eu.kanade.tachiyomi.data.track.MangaAnimeContinuity
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun MangaAnimeContinuityCard(
    result: MangaAnimeContinuity.Result?,
    onOpenAnime: (Anime) -> Unit,
    onSearchAnime: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val found = result as? MangaAnimeContinuity.Result.Found
    if (found?.choices.isNullOrEmpty() && result != MangaAnimeContinuity.Result.Unavailable) return
    if (found == null) {
        ContinuityCard(
            title = stringResource(MR.strings.continuity_to_anime),
            subtitle = stringResource(MR.strings.continuity_retry),
            onOpen = onRetry,
            modifier = modifier,
        )
        return
    }
    var selectedId by rememberSaveable { mutableLongStateOf(found.choices.first().catalogId) }
    var choosingAdaptation by rememberSaveable { mutableStateOf(false) }
    var choosingCopy by rememberSaveable { mutableStateOf(false) }
    var awaitingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var missingCopy by rememberSaveable { mutableStateOf(false) }
    val choice = found.choices.firstOrNull { it.catalogId == selectedId } ?: found.choices.first()
    fun openChoice(candidate: MangaAnimeContinuity.Choice) {
        awaitingId = null
        selectedId = candidate.catalogId
        when (candidate.matches.size) {
            0 -> if (candidate.catalogId in found.resolvingIds) {
                awaitingId = candidate.catalogId
            } else {
                missingCopy = true
            }
            1 -> onOpenAnime(candidate.matches.single())
            else -> choosingCopy = true
        }
    }
    LaunchedEffect(found, awaitingId) {
        val pending = awaitingId?.let { id -> found.choices.firstOrNull { it.catalogId == id } }
            ?: return@LaunchedEffect
        if (pending.matches.isNotEmpty() || pending.catalogId !in found.resolvingIds) {
            awaitingId = null
            openChoice(pending)
        }
    }
    ContinuityCard(
        title = stringResource(MR.strings.continuity_to_anime),
        subtitle = choice.title,
        onOpen = {
            if (found.choices.size > 1) choosingAdaptation = true else openChoice(choice)
        },
        modifier = modifier,
        status = if (choice.matches.isEmpty() && choice.catalogId in found.resolvingIds) {
            stringResource(
                if (awaitingId == choice.catalogId) {
                    MR.strings.continuity_open_when_ready
                } else {
                    MR.strings.continuity_finding_copy
                },
            )
        } else {
            null
        },
    )
    if (choosingAdaptation) {
        ContinuityDestinationSheet(
            title = stringResource(MR.strings.continuity_choose_anime),
            destinations = found.choices.map {
                ContinuityDestination(
                    it.title,
                    listOfNotNull(
                        if (it.format in setOf("TV", "TV_SHORT", "ONA") && it.season != null) {
                            stringResource(MR.strings.continuity_season, it.season)
                        } else {
                            formatLabel(it.format)
                        },
                        it.year?.toString(),
                        it.episodes?.takeUnless { count -> it.format == "MOVIE" && count == 1 }?.let { count ->
                            stringResource(MR.strings.continuity_episode_count, count)
                        },
                    )
                        .joinToString(" · "),
                    listOfNotNull(
                        it.beginning?.let { point ->
                            stringResource(MR.strings.continuity_beginning) +
                                " · " +
                                chapterReference(point)
                        },
                        it.latestAdapted?.let { point ->
                            stringResource(MR.strings.continuity_ending) +
                                " · " +
                                chapterReference(point)
                        },
                    ),
                )
            },
            onSelect = {
                choosingAdaptation = false
                openChoice(found.choices[it])
            },
            onDismiss = { choosingAdaptation = false },
        )
    }
    if (choosingCopy) {
        val sources = Injekt.get<AnimeSourceManager>()
        ContinuityDestinationSheet(
            title = stringResource(MR.strings.continuity_choose_copy),
            destinations = choice.matches.map { ContinuityDestination(it.title, sources.getOrStub(it.source).name) },
            onSelect = {
                choosingCopy = false
                onOpenAnime(choice.matches[it])
            },
            onDismiss = { choosingCopy = false },
        )
    }
    if (missingCopy) {
        ContinuityMissingCopySheet(
            onSearch = {
                missingCopy = false
                onSearchAnime(choice.title)
            },
            onDismiss = { missingCopy = false },
        )
    }
}

@Composable
private fun chapterReference(point: AnimeMangaContinuity.Checkpoint): String {
    val chapter = if (point.chapter % 1.0 == 0.0) point.chapter.toInt().toString() else point.chapter.toString()
    return stringResource(MR.strings.continuity_chapter, chapter) +
        (point.page?.let { " · " + stringResource(MR.strings.continuity_page, it) } ?: "")
}

@Composable
private fun formatLabel(format: String?): String? = when (format) {
    "TV", "TV_SHORT" -> stringResource(MR.strings.continuity_tv)
    "MOVIE" -> stringResource(MR.strings.continuity_movie)
    "OVA" -> "OVA"
    "ONA" -> stringResource(MR.strings.continuity_web)
    "SPECIAL" -> stringResource(MR.strings.continuity_special)
    else -> format
}
