package it.lorelore.nyanime.attention

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import it.lorelore.nyanime.attention.core.AttentionMode
import it.lorelore.nyanime.attention.core.R
import it.lorelore.nyanime.presentation.player.VideoChromeButton

/** A toolbar control only: observing the preference never constructs the detector. */
@Composable
fun AttentionPlayerButton(onInteraction: () -> Unit = {}) {
    val preferences = remember { AttentionPreferences() }
    val enabled by preferences.enabled.changes().collectAsState(preferences.enabled.get())
    val mode by preferences.mode.changes().collectAsState(preferences.mode.get())
    val context = LocalContext.current
    VideoChromeButton(
        label = stringResource(R.string.attention_title),
        onClick = {
            onInteraction()
            if (enabled) {
                AttentionUi.verifying.value = false
                preferences.enabled.set(false)
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                AttentionUi.suspended.value = false
                preferences.enabled.set(true)
            } else {
                // Explain the local camera use before asking Android for first-time access.
                AttentionUi.openSettings()
            }
        },
        icon = if (enabled) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
        selected = enabled,
        selectedContentColor = Color(0xFF80D8FF),
        description = stringResource(
            when {
                !enabled -> R.string.attention_toggle_off
                mode == AttentionMode.ObserveOnly.ordinal -> R.string.attention_toggle_recording
                else -> R.string.attention_toggle_on
            },
        ),
    )
}
