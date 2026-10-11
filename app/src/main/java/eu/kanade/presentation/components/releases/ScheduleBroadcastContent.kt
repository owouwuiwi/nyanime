package eu.kanade.presentation.components.releases

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.AiringEvent
import eu.kanade.tachiyomi.data.releases.AiringRepository
import eu.kanade.tachiyomi.data.releases.AnimeSchedulePreferences
import eu.kanade.tachiyomi.data.releases.AnimeScheduleRepository
import eu.kanade.tachiyomi.data.releases.ReleasePolicy
import eu.kanade.tachiyomi.data.releases.ScheduleAirType
import eu.kanade.tachiyomi.data.releases.ScheduleBroadcast
import eu.kanade.tachiyomi.data.releases.ScheduleParser
import eu.kanade.tachiyomi.data.releases.ScheduleSnapshot
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun scheduleTypeLabel(type: ScheduleAirType): String = stringResource(scheduleTypeResource(type))
internal fun scheduleTypeResource(type: ScheduleAirType): Int = when (type) {
    ScheduleAirType.RAW -> R.string.schedule_raw
    ScheduleAirType.SUB -> R.string.schedule_sub
    ScheduleAirType.DUB -> R.string.schedule_dub
}

internal fun scheduleBroadcastLabel(context: Context, event: AiringEvent): String {
    if (event.variants.isEmpty()) return context.getString(R.string.release_broadcast_label, event.episode.toString())
    val chosen = event.variants.firstOrNull { it.at == event.airingAt && it.type == AnimeSchedulePreferences().type }
        ?: event.variants.first { it.at == event.airingAt }
    val number = if (chosen.untilEpisode >
        chosen.episode
    ) {
        "${chosen.episode}–${chosen.untilEpisode}"
    } else {
        "${chosen.episode}"
    }
    return context.getString(R.string.schedule_broadcast, number, context.getString(scheduleTypeResource(chosen.type)))
}

@Composable internal fun ScheduleBroadcastRows(rows: List<ScheduleBroadcast>) {
    val context = LocalContext.current
    val formatter =
        remember { DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", context.resources.configuration.locales[0]) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.sortedBy { it.type.ordinal }.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(scheduleTypeLabel(row.type), style = MaterialTheme.typography.labelMedium)
                Text(
                    if (row.at >
                        0
                    ) {
                        Instant.ofEpochMilli(row.at).atZone(ZoneId.systemDefault()).format(formatter)
                    } else {
                        stringResource(R.string.schedule_delay)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.delayed.isNotBlank()) {
                    Text(
                        row.delayed,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.platforms.isNotEmpty()) {
                    Text(
                        row.platforms.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class SchedulePresentation(val snapshot: ScheduleSnapshot, val events: List<AiringEvent>)

/** One local projection for the title header and its detailed schedule, including channel changes. */
@Composable private fun rememberSchedulePresentation(animeId: Long): SchedulePresentation? {
    val preferences = remember { AnimeSchedulePreferences() }
    val flow = remember(animeId) {
        val repository = AnimeScheduleRepository()
        combine(
            AiringRepository().events(),
            repository.records(),
            preferences.enabled.changes(),
            preferences.connected.changes(),
            preferences.preferredType.changes(),
        ) { base, records, enabled, connected, type ->
            if (!enabled || !connected) {
                null
            } else {
                val now = System.currentTimeMillis()
                val record = repository.validRecords(records.filter { it.entryId == animeId }).firstOrNull {
                    it.verifiedAt > 0 && now - it.verifiedAt <= 2 * ReleasePolicy.DAY
                }
                record?.snapshot?.let { snapshot ->
                    val preferred = ScheduleAirType.entries.firstOrNull { it.name == type } ?: ScheduleAirType.SUB
                    val events = ScheduleParser.overlay(
                        base.filter { it.entryId == animeId },
                        listOf(record),
                        preferred,
                        now,
                    ).filter { it.airingAt > now }
                    SchedulePresentation(snapshot, events)
                }
            }
        }
    }
    val presentation by flow.collectAsState(initial = null)
    return presentation
}

@Composable internal fun rememberScheduleNextAiringAt(animeId: Long, fallback: Long?): Long? {
    val presentation = rememberSchedulePresentation(animeId) ?: return fallback
    return presentation.events.firstOrNull()?.airingAt
}

/** Cached display only; refreshes belong to the worker, never to composition or the player. */
@Composable internal fun ScheduleEpisodeCard(
    animeId: Long,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val presentation = rememberSchedulePresentation(animeId)
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    if (presentation == null) {
        fallback()
        return
    }
    val details = presentation.snapshot
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val next = presentation.events.firstOrNull()
        if (next != null && next.variants.isNotEmpty()) {
            eu.kanade.presentation.entries.anime.components.NextEpisodeAiringListItem(
                scheduleBroadcastLabel(context, next),
                next.airingAt,
            )
        } else if (details.broadcasts.none { it.at == 0L } && details.premieres.values.none { it == 0L }) {
            fallback()
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (details.status.isNotEmpty()) {
                    Text(
                        stringResource(R.string.schedule_status, localizedScheduleStatus(details.status)),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (details.delay.isNotBlank()) Text(details.delay, style = MaterialTheme.typography.bodyMedium)
                ScheduleBroadcastRows(next?.variants ?: details.broadcasts.filter { it.at == 0L })
                if (details.premieres.isNotEmpty()) {
                    Text(stringResource(R.string.schedule_premieres), style = MaterialTheme.typography.labelLarge)
                    ScheduleBroadcastRows(details.premieres.map { (type, date) -> ScheduleBroadcast(1, date, type) })
                }
                TextButton(onClick = {
                    navigator.push(AnimeScheduleScreen())
                }) { Text(stringResource(R.string.schedule_credit)) }
            }
        }
    }
}

@Composable private fun localizedScheduleStatus(status: String): String = when (status.lowercase()) {
    "upcoming" -> stringResource(R.string.schedule_status_upcoming)
    "ongoing" -> stringResource(R.string.schedule_status_ongoing)
    "delayed" -> stringResource(R.string.schedule_status_delayed)
    "finished" -> stringResource(R.string.schedule_status_finished)
    else -> status
}
