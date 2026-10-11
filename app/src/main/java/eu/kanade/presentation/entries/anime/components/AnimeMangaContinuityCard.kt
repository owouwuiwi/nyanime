package eu.kanade.presentation.entries.anime.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ContinuityCard
import eu.kanade.presentation.components.ContinuityDestination
import eu.kanade.presentation.components.ContinuityDestinationSheet
import eu.kanade.presentation.components.ContinuityMissingCopySheet
import eu.kanade.tachiyomi.data.track.AnimeMangaContinuity
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun AnimeMangaContinuityCard(
    result: AnimeMangaContinuity.Result?,
    onOpenManga: (Manga, Double?) -> Unit,
    onSearchManga: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (result == AnimeMangaContinuity.Result.Unavailable) {
        ContinuityCard(
            title = stringResource(MR.strings.continuity_to_manga),
            subtitle = stringResource(MR.strings.continuity_retry),
            onOpen = onRetry,
            modifier = modifier,
        )
        return
    }
    if (result !is AnimeMangaContinuity.Result.Found) return
    if (result.choices.isEmpty()) return
    var selectedId by rememberSaveable { mutableLongStateOf(result.choices.first().catalogId) }
    var choosingAdaptation by rememberSaveable { mutableStateOf(false) }
    var choosingCopy by rememberSaveable { mutableStateOf(false) }
    var awaitingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var missingCopy by rememberSaveable { mutableStateOf(false) }
    val choice = result.choices.firstOrNull { it.catalogId == selectedId } ?: result.choices.first()
    val continuation = choice.continuationAfter(result.watchedEpisode)
    fun openChoice(candidate: AnimeMangaContinuity.Choice) {
        awaitingId = null
        selectedId = candidate.catalogId
        when (candidate.matches.size) {
            0 -> if (candidate.catalogId in result.resolvingIds) {
                awaitingId = candidate.catalogId
            } else {
                missingCopy = true
            }
            1 -> onOpenManga(candidate.matches.single(), candidate.continuationAfter(result.watchedEpisode))
            else -> choosingCopy = true
        }
    }
    LaunchedEffect(result, awaitingId) {
        val pending = awaitingId?.let { id -> result.choices.firstOrNull { it.catalogId == id } }
            ?: return@LaunchedEffect
        if (pending.matches.isNotEmpty() || pending.catalogId !in result.resolvingIds) {
            awaitingId = null
            openChoice(pending)
        }
    }
    ContinuityCard(
        title =
        continuation?.let { stringResource(MR.strings.continuity_to_manga_chapter, chapterLabel(it)) }
            ?: stringResource(MR.strings.continuity_to_manga),
        subtitle = choice.title,
        onOpen = {
            if (choice.matches.isEmpty() && result.choices.size > 1) {
                choosingAdaptation = true
            } else {
                openChoice(choice)
            }
        },
        modifier = modifier,
        status = if (choice.matches.isEmpty() && choice.catalogId in result.resolvingIds) {
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
        preview = {
            if (choice.beginning != null || choice.latestAdapted != null) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    choice.beginning?.let {
                        ChapterReference(choice.season, stringResource(MR.strings.continuity_beginning), it.chapter)
                    }
                    choice.latestAdapted?.let {
                        ChapterReference(choice.season, stringResource(MR.strings.continuity_ending), it.chapter)
                    }
                }
            }
        },
    )
    if (choosingAdaptation) {
        ContinuityDestinationSheet(
            title = stringResource(MR.strings.continuity_choose_manga),
            destinations = result.choices.map { ContinuityDestination(it.title) },
            onSelect = {
                choosingAdaptation = false
                openChoice(result.choices[it])
            },
            onDismiss = { choosingAdaptation = false },
        )
    }
    if (choosingCopy) {
        val sources = Injekt.get<MangaSourceManager>()
        ContinuityDestinationSheet(
            title = stringResource(MR.strings.continuity_choose_copy),
            destinations = choice.matches.map { ContinuityDestination(it.title, sources.getOrStub(it.source).name) },
            onSelect = {
                choosingCopy = false
                onOpenManga(choice.matches[it], continuation)
            },
            onDismiss = { choosingCopy = false },
        )
    }
    if (missingCopy) {
        ContinuityMissingCopySheet(
            onSearch = {
                missingCopy = false
                onSearchManga(choice.title)
            },
            onDismiss = { missingCopy = false },
        )
    }
}

@Composable
private fun ChapterReference(season: Int?, label: String, chapter: Double) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                append(
                    season?.let { stringResource(MR.strings.continuity_season, it) }
                        ?: stringResource(MR.strings.continuity_adaptation),
                )
                append(" • $label • ")
            }
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(stringResource(MR.strings.continuity_chapter, chapterLabel(chapter)))
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private fun chapterLabel(number: Double): String =
    if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
