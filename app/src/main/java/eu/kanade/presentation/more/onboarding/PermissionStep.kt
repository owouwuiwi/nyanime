package eu.kanade.presentation.more.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.presentation.util.rememberRequestPackageInstallsPermissionState
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.launchRequestPackageInstallsPermission

@Composable
internal fun PermissionStep() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val installGranted = rememberRequestPackageInstallsPermissionState()
    var notificationGranted by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var batteryGranted by remember {
        mutableStateOf(
            context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) == true,
        )
    }
    var requestedNotifications by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationGranted = NotificationManagerCompat.from(context).areNotificationsEnabled()
                batteryGranted =
                    context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) ==
                    true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val requester = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationGranted = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    fun launch(intent: Intent) {
        error = false
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            error = true
        } catch (_: SecurityException) {
            error = true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PermissionCard(
            Icons.Outlined.Notifications,
            R.string.welcome_notifications,
            R.string.welcome_notifications_description,
            notificationGranted,
        ) {
            if (Build.VERSION.SDK_INT >= 33 && !requestedNotifications) {
                requestedNotifications = true
                requester.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                launch(
                    Intent(
                        Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                    ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        }
        PermissionCard(
            Icons.Outlined.SystemUpdate,
            R.string.welcome_install,
            R.string.welcome_install_description,
            installGranted,
        ) {
            try {
                context.launchRequestPackageInstallsPermission()
            } catch (_: Exception) {
                error = true
            }
        }
        PermissionCard(
            Icons.Outlined.BatteryChargingFull,
            R.string.welcome_battery,
            R.string.welcome_battery_description,
            batteryGranted,
        ) {
            @SuppressLint("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${context.packageName}".toUri()
            }
            launch(intent)
        }
        if (error) Text(stringResource(R.string.welcome_permission_error), color = MaterialTheme.colorScheme.error)
        Text(
            stringResource(R.string.welcome_permissions_later),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionCard(icon: ImageVector, title: Int, description: Int, granted: Boolean, onClick: () -> Unit) {
    WelcomePanel {
        Icon(
            if (granted) Icons.Outlined.CheckCircle else icon,
            null,
            Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onClick, enabled = !granted, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(if (granted) R.string.welcome_permission_granted else R.string.welcome_permission_allow),
            )
        }
    }
}
