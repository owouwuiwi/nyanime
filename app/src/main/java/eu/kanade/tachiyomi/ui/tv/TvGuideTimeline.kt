package eu.kanade.tachiyomi.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset
import nyanime.tv.api.TvChannel
import nyanime.tv.api.TvProgramme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only visible channel rows/cells render; real UTC offsets preserve alignment across DST days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvGuideTimeline(state: TvHomeState, list: LazyListState, now: Long, onPlay: (TvChannel) -> Unit) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val zone = ZoneId.systemDefault()
    val start = state.day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = state.day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    var detail by remember { mutableStateOf<Pair<TvChannel, TvProgramme>?>(null) }
    val pixelsPerMinute = with(density) { 2.dp.toPx() }
    val rowHeight = 92.dp * density.fontScale.coerceAtLeast(1f)
    LaunchedEffect(start) {
        val minutes = ((now - start) / 60_000.0 - 30).coerceIn(0.0, (end - start) / 60_000.0)
        scroll.scrollTo((minutes * pixelsPerMinute).toInt())
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Spacer(Modifier.width(86.dp))
            Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                Box(Modifier.width(((end - start) / 60_000f * 2).dp).height(30.dp)) {
                    var hour = start
                    while (hour < end) {
                        Text(
                            clock(hour),
                            Modifier.offset(x = ((hour - start) / 60_000f * 2).dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        hour += 3_600_000
                    }
                }
            }
        }
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom =
                LocalFloatingNavigationInset.current + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.channels, key = { "channel:${it.ref.sourceId}:${it.ref.id}" }) { channel ->
                Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.width(78.dp).fillMaxHeight().clickable {
                            onPlay(channel)
                        },
                    ) {
                        Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                            Text(
                                channel.name,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    val programmes = state.programmes[channel.ref].orEmpty()
                    if (programmes.isEmpty()) {
                        Box(
                            Modifier.weight(
                                1f,
                            ).fillMaxHeight().background(
                                MaterialTheme.colorScheme.surfaceContainerLow,
                                RoundedCornerShape(12.dp),
                            ).padding(12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                stringResource(
                                    when {
                                        channel.ref in state.guideLoading -> R.string.tv_guide_loading
                                        channel.ref in state.guideErrors -> R.string.tv_guide_error
                                        else -> R.string.tv_guide_missing
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).horizontalScroll(scroll)) {
                            val leftMinute = scroll.value / pixelsPerMinute
                            val viewportMinutes = scroll.viewportSize / pixelsPerMinute
                            Box(Modifier.width(((end - start) / 60_000f * 2).dp).fillMaxHeight()) {
                                programmes.filter {
                                    (it.endUtc - start) / 60_000f >= leftMinute - 30 &&
                                        (it.startUtc - start) / 60_000f <= leftMinute + viewportMinutes + 30
                                }.forEach { programme ->
                                    val from = maxOf(programme.startUtc, start)
                                    val to = minOf(programme.endUtc, end)
                                    if (to > from) {
                                        val cellWidth = ((to - from) / 60_000f * 2).dp
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (now in
                                                programme.startUtc until programme.endUtc
                                            ) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerLow
                                            },
                                            modifier = Modifier.offset(x = ((from - start) / 60_000f * 2).dp)
                                                .width(cellWidth).fillMaxHeight().padding(end = 3.dp).clickable {
                                                    detail =
                                                        channel to programme
                                                },
                                        ) {
                                            Column(
                                                Modifier.padding(if (cellWidth < 72.dp) 6.dp else 10.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Text(
                                                    clock(programme.startUtc),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    programme.title,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.tv_end),
                    Modifier.padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    detail?.let { (channel, programme) ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(programme.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${clock(programme.startUtc)} – ${clock(programme.endUtc)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                programme.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = {
                    detail = null
                    onPlay(channel)
                }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.tv_play)) }
            }
        }
    }
}

private fun clock(utc: Long) = Instant.ofEpochMilli(
    utc,
).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
