package it.lorelore.nyanime.presentation.entries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.tracking.PersonalTrackingRecord
import it.lorelore.nyanime.tracking.PersonalTrackingStatus
import it.lorelore.nyanime.tracking.PersonalTrackingStore
import it.lorelore.nyanime.tracking.TrackingSubject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** A read-only projection of the existing journal. Opening a detail never creates a record. */
internal data class PersonalTrackingSummaryState(
    val subject: TrackingSubject?,
    val record: PersonalTrackingRecord? = null,
    val failed: Boolean = false,
)

@Composable
internal fun rememberPersonalTrackingSummaryState(subject: TrackingSubject?): PersonalTrackingSummaryState {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { PersonalTrackingStore.get(context) }
    val state by produceState(PersonalTrackingSummaryState(subject), subject, store) {
        if (subject == null) return@produceState
        store.changes.collectLatest {
            value = try {
                withContext(Dispatchers.IO) { PersonalTrackingSummaryState(subject, store.get(subject)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PersonalTrackingSummaryState(subject, failed = true)
            }
        }
    }
    return state.takeIf { it.subject == subject } ?: PersonalTrackingSummaryState(subject)
}

@Composable
internal fun PersonalTrackingSummary(
    state: PersonalTrackingSummaryState,
    manga: Boolean,
    onTracking: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
) {
    val values = state.record?.values
    val format =
        remember(java.util.Locale.getDefault()) {
            NumberFormat.getNumberInstance().apply {
                maximumFractionDigits =
                    2
            }
        }
    val status = when (values?.status?.toInt()?.let { PersonalTrackingStatus.entries.getOrNull(it) }) {
        PersonalTrackingStatus.CURRENT -> if (manga) R.string.tracking_reading else R.string.tracking_watching
        PersonalTrackingStatus.REPEATING -> if (manga) R.string.tracking_rereading else R.string.tracking_rewatching
        PersonalTrackingStatus.COMPLETED -> R.string.tracking_completed
        PersonalTrackingStatus.PAUSED -> R.string.tracking_paused
        PersonalTrackingStatus.DROPPED -> R.string.tracking_dropped
        PersonalTrackingStatus.PLANNED -> R.string.tracking_planned
        null -> R.string.title_details_personal_tracking
    }
    Surface(
        onClick = onTracking,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.EditNote, null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(status),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            state.failed -> stringResource(R.string.title_details_tracking_unavailable)
                            values == null -> stringResource(R.string.title_details_tracking_local)
                            else -> stringResource(
                                if (manga) {
                                    R.string.title_details_personal_chapters
                                } else {
                                    R.string.title_details_personal_episodes
                                },
                                format.format(values.progress),
                            ) +
                                if (values.score >
                                    0
                                ) {
                                    " · " +
                                        stringResource(
                                            R.string.title_details_your_score,
                                            format.format(values.score),
                                        )
                                } else {
                                    ""
                                }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Outlined.ChevronRight, stringResource(R.string.detail_tracking), Modifier.size(20.dp))
            }
            if (expanded && values != null) {
                if (values.notes.isNotBlank()) {
                    Text(stringResource(R.string.title_details_notes), style = MaterialTheme.typography.labelLarge)
                    Text(
                        values.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (values.tags.isNotEmpty()) {
                    Text(
                        values.tags.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
