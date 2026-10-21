package it.lorelore.nyanime.tracking

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TrackingPanel(
    model: TrackingPanelModel,
    manga: Boolean,
    entryId: Long,
    title: String,
    sourceId: Long,
    onManageServices: () -> Unit,
    onConnect: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val state by model.state.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    val focusManager = LocalFocusManager.current
    val motion = appMotionEnabled()
    var expanded by remember { mutableStateOf(false) }
    var numberDialog by remember { mutableStateOf<TrackingField?>(null) }
    var scoreDialog by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf<TrackingField?>(null) }
    var consentDialog by remember { mutableStateOf(false) }
    var transferDialog by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }
    val target = state.targets.find { it.tracker.id == state.selected }
    val editable = !state.loading && !state.accountChanged && (target == null || !state.incognito)
    val statusLabels = listOf(
        if (manga) R.string.tracking_reading else R.string.tracking_watching,
        if (manga) R.string.tracking_rereading else R.string.tracking_rewatching,
        R.string.tracking_completed,
        R.string.tracking_paused,
        R.string.tracking_dropped,
        R.string.tracking_planned,
    ).map { stringResource(it) }
    val statuses = target?.statuses ?: PersonalTrackingStatus.entries.map {
        TrackingStatusChoice(it.ordinal.toLong(), statusLabels[it.ordinal], it)
    }
    val scores =
        target?.scores ?: (0..100).map { TrackingScoreChoice(it / 10.0, trackingNumber(it / 10.0, locale), it / 10.0) }
    val selectedScore = scores.minByOrNull { abs(it.value - state.values.score) }
    val scoreLabel = if (state.values.score == 0.0) {
        "—"
    } else if (target != null && state.values.score == target.values.score) {
        target.formattedScore
    } else {
        selectedScore?.label ?: trackingNumber(state.values.score, locale)
    }
    val privateValues = state.local?.values ?: TrackingValues()

    fun change(field: TrackingField, value: TrackingValues) = model.edit(TrackingPatch(value, setOf(field)))

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 22.dp, vertical = 12.dp)
            .animateContentSize(tween(if (motion) ModernMotion.RESIZE_MILLIS else 0)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.tracking_heading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = if (state.hasAccounts) onManageServices else onConnect) {
                Text(stringResource(if (state.hasAccounts) R.string.tracking_services else R.string.tracking_connect))
            }
        }
        if (state.loading) {
            TrackingSkeleton()
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.selected == null,
                    onClick = { model.select(null) },
                    label = { Text(stringResource(R.string.tracking_device)) },
                    leadingIcon = if (state.selected == null) {
                        { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                )
                state.targets.forEach { item ->
                    FilterChip(
                        selected = state.selected == item.tracker.id,
                        onClick = { model.select(item.tracker.id) },
                        label = { Text(item.tracker.name) },
                    )
                }
            }
            if (state.incognito) {
                Text(
                    stringResource(R.string.tracking_incognito),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BoxWithConstraints {
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                val widestWord = remember(statuses, style, density) {
                    statuses.flatMap { it.label.split(Regex("\\s+")) }
                        .maxOfOrNull { measurer.measure(it, style, softWrap = false).size.width } ?: 0
                }
                val minimum = with(density) { widestWord.toDp() } + 34.dp
                val columns = when {
                    (maxWidth - 16.dp) / 3 >= minimum -> 3
                    (maxWidth - 8.dp) / 2 >= minimum -> 2
                    else -> 1
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    statuses.chunked(columns).forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            group.forEach { status ->
                                StatusTile(status, state.values.status == status.value, editable, Modifier.weight(1f)) {
                                    model.status(status.value)
                                }
                            }
                            repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelLabel(stringResource(if (manga) R.string.tracking_chapters else R.string.tracking_episodes))
                CounterRow(
                    value = trackingNumber(state.values.progress, locale),
                    total = state.total.takeIf { it > 0 }?.let { trackingNumber(it.toDouble(), locale) },
                    enabled = editable,
                    onDecrease = { model.progress((state.values.progress - 1).coerceAtLeast(0.0)) },
                    onIncrease = { model.progress((state.values.progress + 1).coerceAtMost(1_000_000.0)) },
                    onEdit = { numberDialog = TrackingField.PROGRESS },
                    canDecrease = state.values.progress > 0,
                )
            }
            if (scores.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PanelLabel(stringResource(R.string.tracking_my_score), Modifier.weight(1f))
                        Text(
                            scoreLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = editable) {
                                scoreDialog =
                                    true
                            }.padding(8.dp),
                        )
                    }
                    RatingHistogram(
                        state.ratings,
                        selectedScore?.tenPoint?.takeIf {
                            state.values.score > 0
                        },
                        editable,
                        locale,
                    ) { score ->
                        val choice = scores.filter { it.value != 0.0 }.minByOrNull { abs(it.tenPoint - score) }
                        choice?.let { change(TrackingField.SCORE, state.values.copy(score = it.value)) }
                    }
                    when {
                        state.ratingsLoading -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Text(
                                stringResource(R.string.tracking_ratings_loading),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        state.ratings != null && state.ratings!!.total > 0 -> Text(
                            stringResource(
                                R.string.tracking_ratings_summary,
                                state.ratings!!.average?.let { trackingNumber(it, locale) } ?: "—",
                                NumberFormat.getIntegerInstance(locale).format(state.ratings!!.total),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.canLoadRatings && !state.ratingsEnabled -> TextButton(onClick = {
                            consentDialog = true
                        }) {
                            Text(stringResource(R.string.tracking_ratings_enable))
                        }
                        state.ratingsFailed -> TextButton(onClick = model::loadRatings) {
                            Text(stringResource(R.string.tracking_ratings_retry))
                        }
                        else -> Text(
                            stringResource(R.string.tracking_ratings_missing),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
                onClick = { expanded = !expanded },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(if (manga) R.string.tracking_more_manga else R.string.tracking_more),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(if (expanded) 180f else 0f))
                }
            }
            AnimatedVisibility(
                expanded,
                enter = if (motion) ModernMotion.enter() else androidx.compose.animation.EnterTransition.None,
                exit = if (motion) ModernMotion.exit() else androidx.compose.animation.ExitTransition.None,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(R.string.tracking_personal_options),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CounterRow(
                        trackingNumber(privateValues.repeats.toDouble(), locale),
                        null,
                        true,
                        onDecrease = {
                            model.editPersonal(
                                TrackingPatch(
                                    privateValues.copy(
                                        repeats = (
                                            privateValues.repeats -
                                                1
                                            ).coerceAtLeast(0),
                                    ),
                                    setOf(TrackingField.REPEATS),
                                ),
                            )
                        },
                        onIncrease = {
                            model.editPersonal(
                                TrackingPatch(
                                    privateValues.copy(
                                        repeats = (
                                            privateValues.repeats +
                                                1
                                            ).coerceAtMost(100_000),
                                    ),
                                    setOf(TrackingField.REPEATS),
                                ),
                            )
                        },
                        onEdit = { numberDialog = TrackingField.REPEATS },
                        canDecrease = privateValues.repeats > 0,
                        label = stringResource(if (manga) R.string.tracking_rereads else R.string.tracking_rewatches),
                    )
                    if (manga) {
                        CounterRow(
                            trackingNumber(privateValues.volumes, locale),
                            null,
                            true,
                            onDecrease = {
                                model.editPersonal(
                                    TrackingPatch(
                                        privateValues.copy(
                                            volumes = (
                                                privateValues.volumes -
                                                    1
                                                ).coerceAtLeast(0.0),
                                        ),
                                        setOf(TrackingField.VOLUMES),
                                    ),
                                )
                            },
                            onIncrease = {
                                model.editPersonal(
                                    TrackingPatch(
                                        privateValues.copy(
                                            volumes = (
                                                privateValues.volumes +
                                                    1
                                                ).coerceAtMost(1_000_000.0),
                                        ),
                                        setOf(TrackingField.VOLUMES),
                                    ),
                                )
                            },
                            onEdit = { numberDialog = TrackingField.VOLUMES },
                            canDecrease = privateValues.volumes > 0,
                            label = stringResource(R.string.tracking_volumes),
                        )
                    }
                    if (target == null || target.tracker.supportsReadingDates) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DateButton(
                                stringResource(R.string.tracking_started),
                                state.values.startDate,
                                editable,
                                Modifier.weight(1f),
                            ) {
                                dateDialog =
                                    TrackingField.START
                            }
                            DateButton(
                                stringResource(R.string.tracking_finished),
                                state.values.finishDate,
                                editable,
                                Modifier.weight(1f),
                            ) {
                                dateDialog =
                                    TrackingField.FINISH
                            }
                        }
                    }
                    if (target == null || target.tracker.supportsPrivateTracking) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.tracking_private),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Switch(checked = state.values.private, onCheckedChange = {
                                change(TrackingField.PRIVATE, state.values.copy(private = it))
                            }, enabled = editable)
                        }
                    }
                    PersonalTextField(privateValues.notes, stringResource(R.string.tracking_notes), maxLines = 4) {
                        model.editPersonal(
                            TrackingPatch(privateValues.copy(notes = it.take(32_768)), setOf(TrackingField.NOTES)),
                        )
                    }
                    PersonalTextField(privateValues.tags.joinToString(", "), stringResource(R.string.tracking_tags)) {
                        val tags = it.split(
                            ',',
                        ).map(String::trim).filter(String::isNotBlank).distinct().take(32).map { tag ->
                            tag.take(80)
                        }
                        model.editPersonal(TrackingPatch(privateValues.copy(tags = tags), setOf(TrackingField.TAGS)))
                    }
                    if (target != null &&
                        !state.incognito &&
                        (state.local?.changedAt ?: 0) > 0 &&
                        target.statuses.any { it.tone?.ordinal?.toLong() == privateValues.status }
                    ) {
                        TextButton(onClick = { transferDialog = true }) {
                            Text(stringResource(R.string.tracking_copy_local, target.tracker.name))
                        }
                    }
                    Text(
                        stringResource(R.string.tracking_tracking_only),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                val message = when {
                    state.accountChanged -> R.string.tracking_account_changed
                    state.error -> R.string.tracking_save_error
                    state.saving -> R.string.tracking_saving
                    state.pending?.failed == true -> R.string.tracking_sync_error
                    state.pending != null -> R.string.tracking_sync_pending
                    state.incognito -> R.string.tracking_session_saved
                    target != null -> R.string.tracking_saved_remote
                    else -> R.string.tracking_saved_local
                }
                Icon(
                    if (state.pending != null) Icons.Outlined.Sync else Icons.Outlined.Check,
                    null,
                    Modifier.size(16.dp),
                    tint = if (state.error ||
                        state.pending?.failed == true
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    stringResource(message),
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.undo != null) {
                    TextButton(onClick = {
                        focusManager.clearFocus()
                        model.undo()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.tracking_undo))
                    }
                }
            }
            if (target != null || state.pending != null) {
                TextButton(onClick = onOpenQueue, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Sync, null, Modifier.size(17.dp))
                    Text(stringResource(R.string.tracking_queue_title), Modifier.padding(start = 7.dp))
                }
            }
            if (state.error || state.pending?.failed == true || state.accountChanged) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (state.pending !=
                        null
                    ) {
                        TextButton(onClick = {
                            discardDialog = true
                        }) { Text(stringResource(R.string.tracking_discard)) }
                    }
                    if (!state.accountChanged) {
                        TextButton(onClick = model::retry) {
                            Text(stringResource(R.string.tracking_retry))
                        }
                    }
                }
            }
        }
    }

    numberDialog?.let { field ->
        val current = when (field) {
            TrackingField.REPEATS -> privateValues.repeats.toDouble()
            TrackingField.VOLUMES -> privateValues.volumes
            else -> state.values.progress
        }
        NumberDialog(current, field == TrackingField.PROGRESS && manga || field == TrackingField.VOLUMES, onDismiss = {
            numberDialog =
                null
        }) { number ->
            when (field) {
                TrackingField.REPEATS -> model.editPersonal(
                    TrackingPatch(privateValues.copy(repeats = number.toInt().coerceAtMost(100_000)), setOf(field)),
                )
                TrackingField.VOLUMES -> model.editPersonal(
                    TrackingPatch(privateValues.copy(volumes = number), setOf(field)),
                )
                else -> model.progress(number)
            }
            numberDialog = null
        }
    }
    if (scoreDialog) {
        AlertDialog(
            onDismissRequest = { scoreDialog = false },
            title = { Text(stringResource(R.string.tracking_my_score)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(scores) { choice ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).selectable(
                                selected = choice.value == state.values.score,
                                onClick = {
                                    change(TrackingField.SCORE, state.values.copy(score = choice.value))
                                    scoreDialog = false
                                },
                                role = Role.RadioButton,
                            ).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (choice.value ==
                                    0.0
                                ) {
                                    stringResource(R.string.tracking_unrated)
                                } else {
                                    choice.label
                                },
                                Modifier.weight(1f),
                            )
                            if (choice.value == state.values.score) Icon(Icons.Outlined.Check, null)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { scoreDialog = false }) { Text(stringResource(R.string.tracking_close)) }
            },
        )
    }
    dateDialog?.let { field ->
        val selected = if (field == TrackingField.START) state.values.startDate else state.values.finishDate
        val allowedDates = remember(field, state.values.startDate, state.values.finishDate) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= 0 &&
                    when (field) {
                        TrackingField.START -> state.values.finishDate == 0L || utcTimeMillis <= state.values.finishDate
                        else -> state.values.startDate == 0L || utcTimeMillis >= state.values.startDate
                    }
            }
        }
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis = selected.takeIf {
                    it > 0
                },
                selectableDates = allowedDates,
            )
        DatePickerDialog(
            onDismissRequest = { dateDialog = null },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis?.let(allowedDates::isSelectableDate) == true, onClick = {
                    val date = picker.selectedDateMillis ?: return@TextButton
                    change(
                        field,
                        if (field ==
                            TrackingField.START
                        ) {
                            state.values.copy(startDate = date)
                        } else {
                            state.values.copy(finishDate = date)
                        },
                    )
                    dateDialog = null
                }) { Text(stringResource(R.string.tracking_done)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    change(
                        field,
                        if (field ==
                            TrackingField.START
                        ) {
                            state.values.copy(startDate = 0)
                        } else {
                            state.values.copy(finishDate = 0)
                        },
                    )
                    dateDialog = null
                }) { Text(stringResource(R.string.tracking_clear)) }
            },
        ) { DatePicker(state = picker) }
    }
    if (consentDialog) {
        AlertDialog(
            onDismissRequest = { consentDialog = false },
            title = { Text(stringResource(R.string.tracking_ratings_enable)) },
            text = { Text(stringResource(R.string.tracking_ratings_consent)) },
            confirmButton = {
                TextButton(onClick = {
                    consentDialog = false
                    model.enableRatings()
                }) { Text(stringResource(R.string.tracking_enable)) }
            },
            dismissButton = {
                TextButton(onClick = { consentDialog = false }) { Text(stringResource(R.string.tracking_not_now)) }
            },
        )
    }
    if (transferDialog && target != null) {
        AlertDialog(
            onDismissRequest = { transferDialog = false },
            title = { Text(stringResource(R.string.tracking_copy_local, target.tracker.name)) },
            text = {
                Text(
                    stringResource(
                        R.string.tracking_transfer_preview,
                        statusLabels.getOrElse(privateValues.status.toInt()) { "—" },
                        trackingNumber(privateValues.progress, locale),
                        trackingNumber(privateValues.score, locale),
                        target.statuses.find { it.value == state.values.status }?.label ?: "—",
                        trackingNumber(state.values.progress, locale),
                        scoreLabel,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    transferDialog = false
                    model.copyLocalToSelected()
                }) { Text(stringResource(R.string.tracking_transfer_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { transferDialog = false }) { Text(stringResource(R.string.tracking_cancel)) }
            },
        )
    }
    if (discardDialog) {
        AlertDialog(
            onDismissRequest = { discardDialog = false },
            title = { Text(stringResource(R.string.tracking_discard)) },
            text = { Text(stringResource(R.string.tracking_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    discardDialog = false
                    model.discard()
                }) { Text(stringResource(R.string.tracking_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { discardDialog = false }) { Text(stringResource(R.string.tracking_cancel)) }
            },
        )
    }
}

@Composable
private fun StatusTile(
    status: TrackingStatusChoice,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tone = when (status.tone) {
        PersonalTrackingStatus.CURRENT, PersonalTrackingStatus.REPEATING -> Color(0xFF4CAA79)
        PersonalTrackingStatus.COMPLETED -> Color(0xFF61A3DF)
        PersonalTrackingStatus.PAUSED -> Color(0xFFD8A43B)
        PersonalTrackingStatus.DROPPED -> Color(0xFFD16674)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fill by animateColorAsState(
        if (selected) tone.copy(alpha = .17f) else MaterialTheme.colorScheme.surfaceContainerLow,
        tween(if (appMotionEnabled()) ModernMotion.EXIT_MILLIS else 0),
        label = "tracking-status",
    )
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(fill)
            .border(
                1.dp,
                if (selected) tone.copy(alpha = .55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f),
                RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).background(tone, CircleShape))
        Text(
            status.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PanelLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CounterRow(
    value: String,
    total: String?,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onEdit: () -> Unit,
    canDecrease: Boolean,
    label: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        label?.let { PanelLabel(it) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
            ) {
                IconButton(onClick = onDecrease, enabled = enabled && canDecrease, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Outlined.Remove, stringResource(R.string.tracking_decrease))
                }
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                enabled = enabled,
                onClick = onEdit,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
            ) {
                Row(
                    Modifier.heightIn(min = 52.dp).padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    total?.let {
                        Text(
                            " / $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .35f)),
            ) {
                IconButton(onClick = onIncrease, enabled = enabled, modifier = Modifier.size(52.dp)) {
                    Icon(
                        Icons.Outlined.Add,
                        stringResource(R.string.tracking_increase),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingHistogram(
    ratings: CommunityRatings?,
    personal: Double?,
    enabled: Boolean,
    locale: Locale,
    onScore: (Double) -> Unit,
) {
    val maximum = ratings?.counts?.maxOrNull()?.coerceAtLeast(1) ?: 1
    val selected = personal?.roundToInt()
    Row(
        Modifier.fillMaxWidth().height(132.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(10) { index ->
            val score = index + 1
            val votes = ratings?.counts?.getOrNull(index) ?: 0
            val description = if (ratings == null) {
                stringResource(R.string.tracking_score_only_accessibility, score)
            } else {
                stringResource(
                    R.string.tracking_score_accessibility,
                    score,
                    NumberFormat.getIntegerInstance(locale).format(votes),
                )
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled) {
                    onScore(score.toDouble())
                }
                    .semantics { contentDescription = description },
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.fillMaxWidth().height((8f + 86f * votes.toFloat() / maximum).dp)
                        .background(
                            if (selected ==
                                score
                            ) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .22f)
                            },
                            RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp),
                        ),
                )
                Text(
                    score.toString(),
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected ==
                        score
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Box(
                    Modifier.padding(top = 4.dp).size(4.dp).background(
                        if (selected ==
                            score
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                        CircleShape,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PersonalTextField(value: String, label: String, maxLines: Int = 1, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(value)) }
    var focused by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val commit by rememberUpdatedState(onCommit)
    fun flush() {
        if (dirty) {
            dirty = false
            commit(text.text)
        }
    }
    LaunchedEffect(value, focused) { if (!focused && !dirty && text.text != value) text = TextFieldValue(value) }
    LaunchedEffect(text.text) {
        if (dirty) {
            delay(350)
            flush()
        }
    }
    DisposableEffect(Unit) { onDispose { flush() } }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            dirty = true
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) flush()
        },
        minLines = if (maxLines > 1) 2 else 1,
        maxLines = maxLines,
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun DateButton(label: String, timestamp: Long, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            PanelLabel(label)
            Text(
                if (timestamp >
                    0
                ) {
                    DateFormat.getDateInstance(DateFormat.MEDIUM).apply {
                        timeZone =
                            java.util.TimeZone.getTimeZone("UTC")
                    }.format(Date(timestamp))
                } else {
                    "—"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun NumberDialog(initial: Double, decimal: Boolean, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var value by remember { mutableStateOf(if (decimal) initial.toString() else initial.toLong().toString()) }
    val parsed = value.replace(',', '.').toDoubleOrNull()?.takeIf {
        it.isFinite() &&
            it in 0.0..1_000_000.0 &&
            (decimal || it % 1.0 == 0.0)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracking_progress_edit)) },
        text = {
            OutlinedTextField(
                value,
                { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                parsed?.let(onConfirm)
            }, enabled = parsed != null) { Text(stringResource(R.string.tracking_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tracking_cancel)) } },
    )
}

@Composable
private fun TrackingSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) {
                    Box(
                        Modifier.weight(
                            1f,
                        ).height(
                            52.dp,
                        ).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(
                60.dp,
            ).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp)),
        )
        Box(
            Modifier.fillMaxWidth().height(
                150.dp,
            ).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp)),
        )
    }
}

internal fun trackingNumber(value: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    maximumFractionDigits = 2
}.format(value)
