package eu.kanade.tachiyomi.ui.addon

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.addon.AddonRepository
import kotlinx.coroutines.launch

@Composable
fun AddonModeGesture(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { AddonRepository.get(context) }
    val installed by repository.registry.state.collectAsState()
    val rin = remember(context) { eu.kanade.tachiyomi.data.rin.RinRepository.get(context) }
    val bundles by rin.installed.collectAsState()
    data class Mode(val packageName: String, val label: String, val hold: Long, val hosted: Boolean)
    val addons = installed.filter { it.usable && it.activation?.isMoreAction == false }.map {
        Mode(it.packageName, it.label, requireNotNull(it.activation).holdMillis, false)
    } +
        bundles.filter {
            it.enabled &&
                it.trusted &&
                it.manifest.medium == "addon" &&
                it.manifest.addon?.isLogoHold == true &&
                eu.kanade.tachiyomi.data.rin.RinRepository.compatible(it.manifest)
        }.map {
            Mode(it.manifest.packageName, it.manifest.name, requireNotNull(it.manifest.addon).holdMillis, true)
        }
    LifecycleResumeEffect(repository) {
        repository.refreshInstalled()
        onPauseOrDispose { }
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val configuration = LocalViewConfiguration.current
    val hold = addons.singleOrNull()?.hold ?: 1_500L
    val held = remember(configuration, hold) {
        object : ViewConfiguration by configuration {
            override val longPressTimeoutMillis = hold
        }
    }
    var choosing by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalViewConfiguration provides held) {
        Box(
            if (addons.isEmpty()) {
                modifier
            } else {
                modifier.combinedClickable(
                    interactionSource = null,
                    indication = null,
                    onClick = {},
                    onLongClickLabel = stringResource(R.string.addon_open),
                    hapticFeedbackEnabled = false,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val addon = addons.singleOrNull()
                        if (addon ==
                            null
                        ) {
                            choosing = true
                        } else {
                            scope.launch {
                                if (addon.hosted) {
                                    eu.kanade.tachiyomi.data.addon.RinAddonRuntime.open(context, addon.packageName)
                                } else {
                                    repository.open(addon.packageName, context)
                                }
                            }
                        }
                    },
                )
            },
        ) { content() }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.addon_choose)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    addons.forEach { addon ->
                        TextButton(onClick = {
                            choosing = false
                            scope.launch {
                                if (addon.hosted) {
                                    eu.kanade.tachiyomi.data.addon.RinAddonRuntime.open(context, addon.packageName)
                                } else {
                                    repository.open(addon.packageName, context)
                                }
                            }
                        }) { Text(addon.label) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.store_close)) }
            },
        )
    }
}
