package eu.kanade.presentation.components.releases

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.coroutines.launch
import mihon.feature.upcoming.components.calendar.Calendar
import tachiyomi.presentation.core.components.material.Scaffold
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ReleaseAgendaItem(
    val key: String,
    val entryId: Long,
    val title: String,
    val cover: Any?,
    val at: Long,
    val label: String,
    val itemId: Long? = null,
    val medium: ReleaseMedium = ReleaseMedium.ANIME,
    val dismissalKey: String? = null,
    val number: Double? = null,
    val sourceLabel: String = "",
    val choices: List<ReleaseAgendaItem> = emptyList(),
    val broadcasts: List<eu.kanade.tachiyomi.data.releases.ScheduleBroadcast> = emptyList(),
    val relatedEntryIds: Set<Long> = emptySet(),
    val agendaKeys: Set<String> = emptySet(),
    val plannedAgendaKeys: Set<String> = emptySet(),
) {
    val date: LocalDate get() = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
}

@Composable
fun ReleaseCalendarContent(
    month: YearMonth,
    selectedDate: LocalDate?,
    events: ImmutableMap<LocalDate, Int>,
    items: List<ReleaseAgendaItem>,
    loading: Boolean,
    warning: String?,
    onMonth: (YearMonth) -> Unit,
    onDate: (LocalDate?) -> Unit,
    onRefresh: () -> Unit,
    onItem: (ReleaseAgendaItem) -> Unit,
    initialCalendar: Boolean = false,
    showBack: Boolean = true,
    showMediaFilter: Boolean = false,
    medium: ReleaseMedium? = null,
    onMedium: (ReleaseMedium?) -> Unit = {},
    allowAllMedia: Boolean = true,
    today: LocalDate = LocalDate.now(),
    onStatus: (() -> Unit)? = null,
    onSchedule: (() -> Unit)? = null,
    onLongItem: (ReleaseAgendaItem) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val navigator = LocalNavigator.currentOrThrow
    val locale = LocalConfiguration.current.locales[0]
    var calendarVisible by rememberSaveable { mutableStateOf(initialCalendar) }
    val motion = appMotionEnabled()
    val scope = rememberCoroutineScope()
    val focusDate = selectedDate ?: if (calendarVisible && month != YearMonth.from(today)) month.atDay(1) else today
    val timeline = remember(items, today, focusDate) { ReleaseAgendaTimeline(items, today, focusDate) }
    Scaffold(topBar = {
        AppBar(
            title = stringResource(R.string.release_title),
            navigateUp = if (showBack) {
                {
                    navigator.pop()
                    Unit
                }
            } else {
                null
            },
            actions = {
                if (onSchedule != null) {
                    IconButton(onClick = onSchedule) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.Tune, "AnimeSchedule")
                    }
                }
                IconButton(enabled = !loading, onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.release_check_now))
                }
            },
        )
    }, snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showMediaFilter) ReleaseMediaFilters(medium, allowAllMedia, onMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SegmentedButton(
                    selected = !calendarVisible,
                    onClick = {
                        if (calendarVisible && selectedDate == null) onDate(focusDate)
                        calendarVisible = false
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.release_agenda)) }
                SegmentedButton(
                    selected = calendarVisible,
                    onClick = { calendarVisible = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.release_calendar_title)) }
            }
            Box(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 16.dp)) {
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            // Compose the saved scroll state only after the first local snapshot. Its initial
            // index is today, so a long history never flashes before jumping to the anchor.
            // Live data updates retain keyed rows and do not recreate this scroll state.
            if (!loading) {
                key(medium, calendarVisible, selectedDate) {
                    val listState = rememberLazyListState(
                        initialFirstVisibleItemIndex = if (calendarVisible) 0 else timeline.indexOf(focusDate),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (calendarVisible) {
                                relativeDateText(
                                    focusDate,
                                )
                            } else {
                                stringResource(R.string.release_timeline)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            if (selectedDate != null || calendarVisible) {
                                onMonth(YearMonth.from(today))
                                onDate(null)
                                calendarVisible = false
                            } else {
                                scope.launch {
                                    if (motion) {
                                        listState.animateScrollToItem(timeline.indexOf(today))
                                    } else {
                                        listState.scrollToItem(timeline.indexOf(today))
                                    }
                                }
                            }
                        }) { Text(stringResource(R.string.release_today)) }
                    }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        state = listState,
                        contentPadding = PaddingValues(
                            bottom = eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset.current,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (calendarVisible) {
                            item(key = "calendar") {
                                Calendar(month, events, onMonth, { onDate(it) }, selectedDate = focusDate)
                            }
                        }
                        val rows = if (calendarVisible) {
                            timeline.rows.filter { row ->
                                when (row) {
                                    is ReleaseAgendaTimeline.Row.Day -> row.date == focusDate
                                    is ReleaseAgendaTimeline.Row.Empty -> row.date == focusDate
                                    is ReleaseAgendaTimeline.Row.Release -> row.item.date == focusDate
                                }
                            }
                        } else {
                            timeline.rows
                        }
                        val terminal = timeline.terminalEmptyDay(focusDate).takeUnless { calendarVisible }
                        val regularRows = if (terminal == null) rows else rows.dropLast(2)
                        items(regularRows, key = { it.key }, contentType = {
                            when (it) {
                                is ReleaseAgendaTimeline.Row.Day -> "day"
                                is ReleaseAgendaTimeline.Row.Empty -> "empty"
                                is ReleaseAgendaTimeline.Row.Release -> "release"
                            }
                        }) { row ->
                            when (row) {
                                is ReleaseAgendaTimeline.Row.Day -> ReleaseDayHeading(row.date)
                                is ReleaseAgendaTimeline.Row.Empty -> EmptyReleaseDay(items.isEmpty())
                                is ReleaseAgendaTimeline.Row.Release -> ReleaseCard(
                                    row.item,
                                    showMediaFilter,
                                    motion,
                                    locale,
                                    onItem,
                                    onLongItem,
                                )
                            }
                        }
                        if (terminal != null) {
                            // One meaningful last-day screen keeps the anchor at the top;
                            // it does not append an empty viewport beyond the final release.
                            item(key = "day-$terminal", contentType = "terminal-day") {
                                Column(
                                    Modifier.fillParentMaxHeight(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ReleaseDayHeading(terminal)
                                    EmptyReleaseDay(items.isEmpty())
                                    if (warning != null) AiringStatusNotice(warning, onStatus)
                                    Spacer(Modifier.weight(1f))
                                    ReleaseTimelineEnd()
                                }
                            }
                        } else if (warning != null) {
                            item(key = "warning") {
                                AiringStatusNotice(warning, onStatus)
                            }
                        }
                        if (!calendarVisible && terminal == null) {
                            item(key = "timeline-end", contentType = "timeline-end") { ReleaseTimelineEnd() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseDayHeading(date: LocalDate) {
    Text(relativeDateText(date), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun EmptyReleaseDay(emptyTimeline: Boolean) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            stringResource(if (emptyTimeline) R.string.release_timeline_empty else R.string.release_calendar_empty),
            Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AiringStatusNotice(warning: String, onStatus: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onStatus != null) {
            TextButton(onClick = onStatus) { Text(stringResource(R.string.release_airing_details)) }
        }
    }
}

@Composable
private fun ReleaseTimelineEnd() {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        Text(
            stringResource(R.string.release_timeline_end),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            stringResource(R.string.release_timeline_end_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun LazyItemScope.ReleaseCard(
    item: ReleaseAgendaItem,
    showMediaFilter: Boolean,
    motion: Boolean,
    locale: Locale,
    onItem: (ReleaseAgendaItem) -> Unit,
    onLongItem: (ReleaseAgendaItem) -> Unit,
) {
    val cue = releaseColor(item.medium)
    val haptics = LocalHapticFeedback.current
    val actionsLabel = stringResource(R.string.release_entry_actions)
    val shape = RoundedCornerShape(16.dp)
    Card(
        (if (motion) Modifier.animateItem() else Modifier).fillMaxWidth().padding(
            horizontal = 16.dp,
        ).combinedClickable(
            onClick = { onItem(item) },
            onLongClickLabel = actionsLabel,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongItem(item)
            },
        ),
        shape = shape,
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(cue.copy(alpha = .65f), cue.copy(alpha = .18f), cue.copy(alpha = .4f)),
            ),
        ),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ItemCover.Book(
                data = item.cover,
                modifier = Modifier.width(64.dp).height(96.dp),
                shape = RoundedCornerShape(8.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showMediaFilter) {
                    Text(
                        stringResource(
                            if (item.medium == ReleaseMedium.ANIME) {
                                R.string.release_anime
                            } else {
                                R.string.release_manga
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = cue,
                    )
                }
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.broadcasts.isNotEmpty()) ScheduleBroadcastRows(item.broadcasts)
                if (item.choices.isNotEmpty()) {
                    Text(
                        stringResource(R.string.release_source_choices, item.choices.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    Instant.ofEpochMilli(
                        item.at,
                    ).atZone(
                        ZoneId.systemDefault(),
                    ).format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", locale)),
                    style = MaterialTheme.typography.labelLarge,
                    color = cue,
                )
            }
        }
    }
}

@Composable
internal fun releaseColor(medium: ReleaseMedium): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when (medium) {
        ReleaseMedium.ANIME -> if (dark) Color(0xFFFFAB62) else Color(0xFFB65308)
        ReleaseMedium.MANGA -> if (dark) Color(0xFF80D8FF) else Color(0xFF006C85)
    }
}
