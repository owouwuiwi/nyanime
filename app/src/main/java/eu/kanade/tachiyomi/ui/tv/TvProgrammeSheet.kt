package eu.kanade.tachiyomi.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import nyanime.tv.api.TvChannel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Full schedule remains virtualized, including unusually dense or long guides. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvProgrammeSheet(
    channel: TvChannel,
    state: TvHomeState,
    now: Long,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    actionLabel: Int = R.string.tv_play,
) {
    val list = rememberLazyListState()
    val programmes = state.programmes[channel.ref].orEmpty()
    val currentIndex = remember(programmes, state.day) {
        programmes.indexOfFirst { it.endUtc > now }.coerceAtLeast(0)
    }
    LaunchedEffect(channel.ref, state.day, programmes.isNotEmpty()) {
        if (programmes.isNotEmpty()) list.scrollToItem(currentIndex)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.84f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvLogo(channel, Modifier.size(52.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.day.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (channel.ref in
                state.guideSaved
            ) {
                Text(stringResource(R.string.tv_saved), style = MaterialTheme.typography.labelSmall)
            }
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (programmes.isEmpty()) {
                    item("empty") {
                        Text(
                            stringResource(
                                when {
                                    channel.ref in state.guideLoading -> R.string.tv_guide_loading
                                    channel.ref in state.guideErrors -> R.string.tv_guide_error
                                    else -> R.string.tv_guide_missing
                                },
                            ),
                            Modifier.padding(vertical = 20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(programmes, key = { it.id }) { programme ->
                    val active = now in programme.startUtc until programme.endUtc
                    Row(
                        Modifier.fillMaxWidth().background(
                            if (active) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            RoundedCornerShape(16.dp),
                        ).padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Column(Modifier.weight(.3f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(programmeTime(programme.startUtc), style = MaterialTheme.typography.labelLarge)
                            Text(
                                programmeTime(programme.endUtc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (active) {
                                Text(
                                    stringResource(R.string.tv_live),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                programme.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            programme.description?.takeIf {
                                it.isNotBlank()
                            }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Button(onClick = {
                onDismiss()
                onPlay()
            }, Modifier.fillMaxWidth().padding(bottom = 16.dp)) { Text(stringResource(actionLabel)) }
        }
    }
}

private fun programmeTime(utc: Long): String = Instant.ofEpochMilli(
    utc,
).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
