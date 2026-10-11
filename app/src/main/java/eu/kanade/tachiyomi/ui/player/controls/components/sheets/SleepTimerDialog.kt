package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

internal fun sleepTimerDurationSeconds(minutes: String): Int? = minutes.toIntOrNull()
    ?.takeIf { it in 1..1439 }
    ?.times(60)

internal fun formatSleepTimerRemaining(seconds: Int): String {
    val time = seconds.coerceAtLeast(0)
    val minutes = (time / 60 % 60).toString().padStart(2, '0')
    val remainder = (time % 60).toString().padStart(2, '0')
    return if (time >= 3600) "${time / 3600}:$minutes:$remainder" else "$minutes:$remainder"
}

@Composable
fun SleepTimerDialog(
    remainingTime: Int,
    onStartTimer: (Int) -> Unit,
    onExtendTimer: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    reduceMotion: Boolean,
    atEpisodeEnd: Boolean,
    initialCustomMinutes: Int,
    onStartCustomTimer: (Int) -> Unit,
    onEndTimer: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest,
                    )
                    .clearAndSetSemantics {},
            )
            SleepTimerContent(
                remainingTime = remainingTime,
                onStartTimer = {
                    onStartTimer(it)
                    onDismissRequest()
                },
                onExtendTimer = {
                    onExtendTimer(it)
                    onDismissRequest()
                },
                onStartCustomTimer = {
                    onStartCustomTimer(it)
                    onDismissRequest()
                },
                onEndTimer = {
                    onEndTimer()
                    onDismissRequest()
                },
                atEpisodeEnd = atEpisodeEnd,
                initialCustomMinutes = initialCustomMinutes,
                onDismissRequest = onDismissRequest,
                reduceMotion = reduceMotion,
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            )
        }
    }
}

@Composable
fun SleepTimerContent(
    remainingTime: Int,
    onStartTimer: (Int) -> Unit,
    onExtendTimer: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    initiallyCustom: Boolean = false,
    initialCustomMinutes: Int = 30,
    atEpisodeEnd: Boolean = false,
    onStartCustomTimer: (Int) -> Unit = onStartTimer,
    onEndTimer: () -> Unit = {},
) {
    var custom by rememberSaveable { mutableStateOf(initiallyCustom) }
    var minutes by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val initial = (initialCustomMinutes.takeIf { it in 1..1439 } ?: 30).toString()
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val seconds = sleepTimerDurationSeconds(minutes.text)
    val active = remainingTime > 0 || atEpisodeEnd
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BoxWithConstraints(modifier) {
        val compact = maxHeight < 320.dp
        val wide = maxWidth >= 600.dp && LocalDensity.current.fontScale <= 1.3f
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                Modifier.padding(if (compact) 12.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (custom) {
                        IconButton(onClick = {
                            focus.clearFocus()
                            keyboard?.hide()
                            custom = false
                        }) {
                            Icon(
                                Icons.AutoMirrored.Default.ArrowBack,
                                stringResource(AYMR.strings.timer_quick_durations),
                            )
                        }
                    } else {
                        Icon(
                            Icons.Outlined.Bedtime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp).size(24.dp),
                        )
                    }
                    Text(
                        stringResource(AYMR.strings.timer_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, stringResource(MR.strings.action_close))
                    }
                }
                AnimatedContent(
                    targetState = custom,
                    transitionSpec = {
                        if (reduceMotion) {
                            (EnterTransition.None togetherWith ExitTransition.None).using(null)
                        } else {
                            fadeIn(tween(160)) togetherWith fadeOut(tween(90))
                        }
                    },
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    label = "timer_duration_input",
                ) { showCustom ->
                    if (showCustom) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            val focusRequester = remember { FocusRequester() }
                            OutlinedTextField(
                                value = minutes,
                                onValueChange = { value ->
                                    if (value.text.length <= 4 && value.text.all { it in '0'..'9' }) minutes = value
                                },
                                label = { Text(stringResource(AYMR.strings.timer_custom_minutes)) },
                                supportingText = { Text(stringResource(AYMR.strings.timer_duration_range)) },
                                singleLine = true,
                                isError = seconds == null,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { seconds?.let(onStartCustomTimer) }),
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            )
                            LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        }
                    } else {
                        val overview: @Composable () -> Unit = {
                            if (active) {
                                SleepTimerCountdown(
                                    remainingTime,
                                    onExtendTimer,
                                    onCancel = { onStartTimer(0) },
                                    atEpisodeEnd = atEpisodeEnd,
                                )
                            } else {
                                Text(
                                    stringResource(AYMR.strings.timer_pause_description),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (wide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { overview() }
                                Column(Modifier.weight(1.25f).verticalScroll(rememberScrollState())) {
                                    SleepTimerDurations(
                                        active,
                                        onStartTimer,
                                        onCustom = { custom = true },
                                        onEndTimer = onEndTimer,
                                    )
                                }
                            }
                        } else {
                            Column(
                                Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                overview()
                                SleepTimerDurations(
                                    active,
                                    onStartTimer,
                                    onCustom = { custom = true },
                                    onEndTimer = onEndTimer,
                                )
                            }
                        }
                    }
                }
                if (custom) {
                    Button(
                        onClick = { seconds?.let(onStartCustomTimer) },
                        enabled = seconds != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(if (active) AYMR.strings.timer_update else AYMR.strings.timer_start))
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepTimerDurations(
    active: Boolean,
    onStartTimer: (Int) -> Unit,
    onCustom: () -> Unit,
    onEndTimer: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (active) AYMR.strings.timer_restart_hint else AYMR.strings.timer_quick_hint),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(15, 30, 45, 60, 90, 120).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { value ->
                    Surface(
                        onClick = { onStartTimer(value * 60) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            Modifier.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
                            Text(
                                stringResource(AYMR.strings.timer_minutes_unit),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        val endButton: @Composable (Modifier) -> Unit = { buttonModifier ->
            TextButton(onClick = onEndTimer, modifier = buttonModifier.heightIn(min = 48.dp)) {
                Text(stringResource(AYMR.strings.timer_at_episode_end))
            }
        }
        val customButton: @Composable (Modifier) -> Unit = { buttonModifier ->
            TextButton(onClick = onCustom, modifier = buttonModifier.heightIn(min = 48.dp)) {
                Text(stringResource(AYMR.strings.timer_custom_duration))
            }
        }
        if (LocalDensity.current.fontScale > 1.3f) {
            endButton(Modifier.fillMaxWidth())
            customButton(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                endButton(Modifier.weight(1f))
                customButton(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SleepTimerCountdown(
    remainingTime: Int,
    onExtendTimer: (Int) -> Unit,
    onCancel: () -> Unit,
    atEpisodeEnd: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.12f), colors.surfaceContainerHigh)),
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (atEpisodeEnd) {
                Icon(Icons.Outlined.Bedtime, null, tint = colors.primary, modifier = Modifier.size(28.dp))
                Text(stringResource(AYMR.strings.timer_at_episode_end), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(AYMR.strings.timer_episode_end_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(stringResource(AYMR.strings.timer_pause_in), style = MaterialTheme.typography.labelLarge)
                Text(
                    formatSleepTimerRemaining(remainingTime),
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                )
                FilledTonalButton(onClick = { onExtendTimer(15 * 60) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(AYMR.strings.timer_add_fifteen))
                }
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(AYMR.strings.timer_stop))
            }
        }
    }
}

@Composable
fun SleepTimerEntry(remainingTime: Int, onClick: () -> Unit, atEpisodeEnd: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Outlined.Timer, null, Modifier.padding(10.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(AYMR.strings.timer_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (atEpisodeEnd) {
                        stringResource(AYMR.strings.timer_at_episode_end)
                    } else if (remainingTime > 0) {
                        stringResource(AYMR.strings.timer_remaining, formatSleepTimerRemaining(remainingTime))
                    } else {
                        stringResource(AYMR.strings.timer_entry_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Default.ArrowForward, null)
        }
    }
}
