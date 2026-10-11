package it.lorelore.nyanime.tracking

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.MangaTracker
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.core.common.i18n.stringResource as mokoString

class TrackingQueueScreen : Screen() {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { TrackingQueueModel() }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val motion = appMotionEnabled()
        var filter by rememberSaveable { mutableStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }
        var remove by remember { mutableStateOf<TrackingQueueRow?>(null) }
        var confirm by remember { mutableStateOf<TrackingQueueRow?>(null) }
        var legacy by remember { mutableStateOf<TrackingLegacyRow?>(null) }
        var removeLegacy by remember { mutableStateOf<TrackingLegacyRow?>(null) }
        fun open(row: TrackingQueueRow) {
            val screen = if (row.edit.subject.medium == TrackingMedium.ANIME) {
                AnimeScreen(row.edit.entryId)
            } else {
                MangaScreen(row.edit.entryId)
            }
            navigator.push(screen)
        }
        fun review(row: TrackingQueueRow) = row.edit.delivery == TrackingDelivery.BLOCKED ||
            !row.accountMatches ||
            row.needsAccountConfirmation
        val pending = state.pending.filter {
            (filter != 2 || review(it)) && it.title.title.orEmpty().contains(query.trim(), ignoreCase = true)
        }
        val recent = if (filter != 0) {
            emptyList()
        } else {
            state.recent.filter {
                it.title.title.orEmpty().contains(query.trim(), ignoreCase = true)
            }
        }
        Scaffold(topBar = {
            AppBar(
                title = stringResource(R.string.tracking_queue_title),
                navigateUp = navigator::pop,
            )
        }) { padding ->
            LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("summary") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(
                                    R.string.tracking_queue_summary,
                                    state.pending.size,
                                    state.pending.count(::review) + state.legacy.size,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.tracking_queue_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (state.incognito) {
                    item("private") {
                        Text(
                            stringResource(R.string.tracking_queue_privacy),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
                item("filters") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            R.string.tracking_queue_all,
                            R.string.tracking_queue_pending,
                            R.string.tracking_queue_review,
                        ).forEachIndexed {
                                index,
                                label,
                            ->
                            FilterChip(
                                selected = filter == index,
                                onClick = { filter = index },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                }
                if (state.pending.size + state.recent.size > 5) {
                    item("search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.tracking_queue_search)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.large,
                        )
                    }
                }
                if (state.error) {
                    item("error") {
                        TextButton(onClick = model::refresh) {
                            Text(stringResource(R.string.tracking_queue_local_error))
                        }
                    }
                }
                if (state.loading) {
                    items(3) {
                        Surface(
                            Modifier.fillMaxWidth().height(116.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {}
                    }
                } else {
                    if (filter != 1 && query.isBlank()) {
                        items(
                            state.legacy,
                            key = { "legacy:${it.request.id}" },
                        ) { old ->
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.tracking_queue_legacy_title),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        stringResource(R.string.tracking_queue_legacy_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = { legacy = old },
                                            enabled = !state.incognito && old.choices.isNotEmpty(),
                                        ) {
                                            Text(stringResource(R.string.tracking_queue_review))
                                        }
                                        TextButton(onClick = { removeLegacy = old }) {
                                            Text(stringResource(R.string.tracking_queue_remove))
                                        }
                                    }
                                    if (old.choices.isEmpty()) {
                                        Text(
                                            stringResource(R.string.tracking_queue_legacy_missing),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    items(pending, key = { it.id }) { row ->
                        QueueCard(
                            row,
                            state.incognito,
                            row.id in state.busy,
                            Modifier.animateItem(
                                fadeInSpec = tween(if (motion) ModernMotion.RESIZE_MILLIS else 0),
                                fadeOutSpec = tween(if (motion) ModernMotion.RESIZE_MILLIS else 0),
                                placementSpec = tween(if (motion) ModernMotion.RESIZE_MILLIS else 0),
                            ),
                            onOpen = { open(row) },
                            onRetry = {
                                when {
                                    row.needsAccountConfirmation &&
                                        row.service?.let(TrackingAccountBinding::enhanced) == true -> {
                                        if (row.title.available) open(row)
                                    }
                                    row.needsAccountConfirmation -> confirm = row
                                    !row.accountMatches || row.edit.problem == TrackingQueueProblem.AUTHENTICATION ->
                                        navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                                    row.edit.problem == TrackingQueueProblem.CONFLICT -> {
                                        if (row.title.available) open(row)
                                    }
                                    else -> model.retry(row)
                                }
                            },
                            onUndo = { model.undo(row) },
                            onRemove = { remove = row },
                        )
                    }
                    if (recent.isNotEmpty()) {
                        item("recent-heading") {
                            Text(
                                stringResource(R.string.tracking_queue_recent),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                    }
                    items(recent, key = { it.id }) { row ->
                        QueueCard(
                            row,
                            state.incognito,
                            row.id in state.busy,
                            Modifier,
                            onOpen = { open(row) },
                            onRetry = {},
                            onUndo = { model.undo(row) },
                            onRemove = {},
                        )
                    }
                    if (pending.isEmpty() &&
                        recent.isEmpty() &&
                        (
                            state.legacy.isEmpty() ||
                                filter == 1 ||
                                query.isNotBlank()
                            )
                    ) {
                        item("empty") {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    null,
                                    Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    stringResource(R.string.tracking_queue_empty),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(R.string.tracking_queue_empty_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        remove?.let { row ->
            QueueConfirm(
                stringResource(R.string.tracking_queue_remove),
                stringResource(R.string.tracking_discard_message),
                stringResource(R.string.tracking_queue_remove),
                onDismiss = { remove = null },
            ) {
                model.discard(row)
                remove = null
            }
        }
        removeLegacy?.let { row ->
            QueueConfirm(
                stringResource(R.string.tracking_queue_remove),
                stringResource(R.string.tracking_discard_message),
                stringResource(R.string.tracking_queue_remove),
                onDismiss = { removeLegacy = null },
            ) {
                model.discardLegacy(row)
                removeLegacy = null
            }
        }
        confirm?.let { row ->
            QueueConfirm(
                stringResource(R.string.tracking_queue_confirm_account),
                stringResource(
                    R.string.tracking_queue_confirm_account_hint,
                    row.service?.name.orEmpty(),
                    row.edit.account,
                ),
                stringResource(R.string.tracking_queue_confirm),
                onDismiss = { confirm = null },
            ) {
                model.confirmAccount(row)
                confirm = null
            }
        }
        legacy?.let { old ->
            var choice by remember(old.request.id) { mutableStateOf<LegacyTrackingChoice?>(null) }
            AlertDialog(
                onDismissRequest = { legacy = null },
                title = { Text(stringResource(R.string.tracking_queue_legacy_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.tracking_queue_legacy_hint))
                        old.choices.forEach { candidate ->
                            FilterChip(selected = choice == candidate, onClick = { choice = candidate }, label = {
                                Column {
                                    Text(
                                        candidate.title.title.orEmpty(),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        stringResource(
                                            if (candidate.edit.subject.medium == TrackingMedium.ANIME) {
                                                R.string.tracking_queue_anime
                                            } else {
                                                R.string.tracking_queue_manga
                                            },
                                        ) +
                                            " · " +
                                            candidate.service.name +
                                            " · " +
                                            candidate.edit.account,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            })
                        }
                        Text(
                            stringResource(
                                R.string.tracking_queue_progress_value,
                                choice?.let { selected ->
                                    val locale = java.util.Locale.getDefault()
                                    trackingNumber(selected.edit.base!!.progress, locale) +
                                        " → " +
                                        trackingNumber(old.request.progress, locale)
                                } ?: trackingNumber(old.request.progress, java.util.Locale.getDefault()),
                            ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = choice != null,
                        onClick = {
                            choice?.let {
                                model.acceptLegacy(
                                    old,
                                    it,
                                )
                            }
                            legacy = null
                        },
                    ) {
                        Text(stringResource(R.string.tracking_queue_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { legacy = null }) { Text(stringResource(R.string.tracking_cancel)) }
                },
            )
        }
    }
}

@Composable
internal fun QueueCard(
    row: TrackingQueueRow,
    incognito: Boolean,
    busy: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onUndo: () -> Unit,
    onRemove: () -> Unit,
) {
    val motion = appMotionEnabled()
    val blocked = !row.accountMatches || row.needsAccountConfirmation || row.edit.delivery == TrackingDelivery.BLOCKED
    val sending = row.active && row.edit.delivery == TrackingDelivery.SENDING
    val color = if (row.active && blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val borderColor = if (row.active && blocked) {
        color.copy(alpha = .35f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
    }
    val statusIcon = when {
        row.active && blocked -> Icons.Outlined.ErrorOutline
        row.active -> Icons.Outlined.Schedule
        else -> Icons.Outlined.CheckCircle
    }
    val serviceName = row.service?.name ?: stringResource(R.string.tracking_queue_missing_service)
    Surface(
        modifier.fillMaxWidth().animateContentSize(tween(if (motion) ModernMotion.RESIZE_MILLIS else 0)),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            borderColor,
        ),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ItemCover.Book(row.title.cover, Modifier.width(50.dp), shape = MaterialTheme.shapes.small)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        row.title.title ?: stringResource(R.string.tracking_queue_missing_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$serviceName · ${row.accountLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (sending) {
                            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                        } else {
                            Icon(
                                statusIcon,
                                null,
                                Modifier.size(14.dp),
                                tint = color,
                            )
                        }
                        Text(
                            stringResource(queueStatus(row)),
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                        )
                    }
                }
                if (row.active) {
                    IconButton(onClick = onRemove, enabled = !busy && !sending) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            stringResource(R.string.tracking_queue_remove),
                            Modifier.size(20.dp),
                        )
                    }
                }
            }
            Text(
                queueChanges(row),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.active && row.edit.problem == TrackingQueueProblem.CONFLICT) {
                Text(
                    stringResource(R.string.tracking_queue_conflict_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.title.available) {
                    TextButton(onClick = onOpen) {
                        Text(stringResource(R.string.tracking_queue_open))
                    }
                }
                if (row.active) {
                    TextButton(onClick = onRetry, enabled = !incognito && !busy && !sending) {
                        Text(
                            stringResource(
                                when {
                                    row.needsAccountConfirmation &&
                                        row.service?.let(TrackingAccountBinding::enhanced) == true ->
                                        R.string.tracking_queue_review_source
                                    row.needsAccountConfirmation -> R.string.tracking_queue_confirm_account
                                    row.edit.problem == TrackingQueueProblem.CONFLICT ->
                                        R.string.tracking_queue_review_values
                                    !row.accountMatches ||
                                        row.edit.problem == TrackingQueueProblem.AUTHENTICATION ->
                                        R.string.tracking_queue_login
                                    else -> R.string.tracking_retry
                                },
                            ),
                        )
                    }
                }
                if (row.canUndo) {
                    TextButton(onClick = onUndo, enabled = !incognito && !busy) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, null, Modifier.size(16.dp))
                        Text(stringResource(R.string.tracking_undo), Modifier.padding(start = 5.dp))
                    }
                }
            }
            if (busy) Text(stringResource(R.string.tracking_saving), style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun queueStatus(row: TrackingQueueRow): Int = when {
    !row.active && row.receipt?.undone == true -> R.string.tracking_queue_undone
    !row.active && row.receipt?.discarded == true -> R.string.tracking_queue_removed
    !row.active && row.receipt?.noEffect == true -> R.string.tracking_queue_already_current
    !row.active -> R.string.tracking_queue_sent
    row.needsAccountConfirmation -> R.string.tracking_queue_account
    !row.accountMatches -> R.string.tracking_queue_login
    row.edit.delivery == TrackingDelivery.SENDING -> R.string.tracking_queue_sending
    row.edit.problem == TrackingQueueProblem.ACCOUNT -> R.string.tracking_queue_account
    row.edit.problem == TrackingQueueProblem.AUTHENTICATION -> R.string.tracking_queue_login
    row.edit.problem == TrackingQueueProblem.ASSOCIATION -> R.string.tracking_queue_association
    row.edit.problem == TrackingQueueProblem.CONFLICT -> R.string.tracking_queue_conflict
    row.edit.problem == TrackingQueueProblem.REVIEW -> R.string.tracking_queue_review
    row.edit.problem == TrackingQueueProblem.REJECTED -> R.string.tracking_queue_rejected
    row.edit.problem == TrackingQueueProblem.EXHAUSTED -> R.string.tracking_queue_exhausted
    row.edit.problem == TrackingQueueProblem.PRIVACY -> R.string.tracking_queue_privacy
    row.edit.problem == TrackingQueueProblem.RATE_LIMIT ||
        row.cooldown > System.currentTimeMillis() -> R.string.tracking_queue_rate_limit
    row.edit.delivery == TrackingDelivery.UNCERTAIN -> R.string.tracking_queue_uncertain
    else -> R.string.tracking_queue_waiting
}

@Composable
private fun queueChanges(row: TrackingQueueRow): String {
    val context = LocalContext.current
    val values = row.edit.patch.values
    val locale = java.util.Locale.getDefault()
    return row.edit.patch.fields.sortedBy { it.ordinal }.joinToString(" · ") { field ->
        when (field) {
            TrackingField.PROGRESS -> context.getString(
                R.string.tracking_queue_progress_value,
                trackingNumber(
                    values.progress,
                    locale,
                ),
            )
            TrackingField.SCORE -> context.getString(
                R.string.tracking_queue_score_value,
                trackingNumber(
                    values.score,
                    locale,
                ),
            )
            TrackingField.STATUS -> {
                val resource = if (row.edit.subject.medium == TrackingMedium.ANIME) {
                    (row.service as? AnimeTracker)?.getStatusForAnime(values.status)
                } else {
                    (row.service as? MangaTracker)?.getStatusForManga(values.status)
                }
                resource?.let(context::mokoString) ?: context.getString(R.string.tracking_queue_status_value)
            }
            TrackingField.START -> context.getString(R.string.tracking_started)
            TrackingField.FINISH -> context.getString(R.string.tracking_finished)
            TrackingField.PRIVATE -> context.getString(R.string.tracking_private)
            else -> context.getString(R.string.tracking_heading)
        }
    }
}

@Composable
private fun QueueConfirm(title: String, message: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tracking_cancel)) } },
    )
}
