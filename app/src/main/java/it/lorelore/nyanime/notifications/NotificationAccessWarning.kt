package it.lorelore.nyanime.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.releases.ReleasePreferences
import eu.kanade.tachiyomi.data.releases.ReleaseReminders

internal data class NotificationAccessState(
    val appEnabled: Boolean = true,
    val blockedChannels: Set<String> = emptySet(),
) {
    val blocked: Boolean get() = !appEnabled || blockedChannels.isNotEmpty()

    fun settings(context: Context): Intent = if (appEnabled && blockedChannels.size == 1) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, blockedChannels.single())
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
}

internal val LocalNotificationAccess = compositionLocalOf<NotificationAccessState?> { null }
private val WarningGold = Color(0xFFFFC74A)

/** One system check on entry/resume; no polling, shader blur or running animation while idle. */
@Composable
internal fun rememberNotificationAccess(active: Boolean = true): State<NotificationAccessState> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember(context) { mutableStateOf(NotificationAccessState()) }
    DisposableEffect(context, owner, active) {
        fun refresh() {
            val manager = context.getSystemService(NotificationManager::class.java)
            val preferences = ReleasePreferences()
            val channels = buildSet {
                if (preferences.enabled.get() && preferences.availability.get()) {
                    add(Notifications.CHANNEL_NEW_CHAPTERS_EPISODES)
                }
                if (preferences.enabled.get() && preferences.reminders.get()) add(ReleaseReminders.CHANNEL)
            }
            state.value = NotificationAccessState(
                NotificationManagerCompat.from(context).areNotificationsEnabled(),
                channels.filterTo(mutableSetOf()) {
                    manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE
                },
            )
        }
        if (active) refresh() else state.value = NotificationAccessState()
        val observer = LifecycleEventObserver { _, event ->
            if (active &&
                event == Lifecycle.Event.ON_RESUME
            ) {
                refresh()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

@Composable
internal fun NotificationAccessWarningButton() {
    val fallback = if (LocalNotificationAccess.current == null) rememberNotificationAccess().value else null
    val access = LocalNotificationAccess.current ?: fallback ?: return
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val motion = appMotionEnabled()
    val warningTint = if (MaterialTheme.colorScheme.surface.luminance() > .5f) Color(0xFF8A5900) else WarningGold
    AnimatedVisibility(
        access.blocked,
        enter = ModernMotion.enter(if (motion) ModernMotion.PAGE_MILLIS else 0),
        exit = androidx.compose.animation.fadeOut(tween(if (motion) ModernMotion.EXIT_MILLIS else 0)),
    ) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.WarningAmber, stringResource(R.string.release_home_warning_action), tint = warningTint)
        }
    }
    if (open && access.blocked) {
        AlertDialog(
            onDismissRequest = { open = false },
            icon = { Icon(Icons.Outlined.WarningAmber, null, tint = warningTint) },
            title = { Text(stringResource(R.string.release_home_warning_title)) },
            text = {
                Text(
                    stringResource(
                        if (access.appEnabled) {
                            R.string.release_home_channel_warning
                        } else {
                            R.string.release_home_app_warning
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    context.startActivity(access.settings(context))
                }) { Text(stringResource(R.string.release_home_warning_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.release_home_warning_later)) }
            },
        )
    }
}

/** Decorative overlay: drawing only, so Home cards and horizontal swipes remain touchable. */
@Composable
internal fun NotificationAccessEdgeGlow(visible: Boolean, modifier: Modifier = Modifier) {
    val motion = appMotionEnabled()
    val opacity by animateFloatAsState(
        if (visible) 1f else 0f,
        tween(if (motion) ModernMotion.PAGE_MILLIS else 0),
        label = "notification-access-edge",
    )
    if (opacity <= 0f) return
    Canvas(modifier.alpha(opacity)) {
        val edge = 28.dp.toPx().coerceAtMost(size.width / 5)
        val colors = listOf(WarningGold.copy(alpha = .42f), WarningGold.copy(alpha = .12f), Color.Transparent)
        drawRect(Brush.horizontalGradient(colors, endX = edge), size = Size(edge, size.height))
        drawRect(
            Brush.horizontalGradient(colors.reversed(), startX = size.width - edge, endX = size.width),
            topLeft = Offset(size.width - edge, 0f),
            size = Size(edge, size.height),
        )
    }
}
