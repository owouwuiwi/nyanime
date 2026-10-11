package eu.kanade.tachiyomi.ui.community

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun CommunityNotificationCard(background: Boolean, settings: () -> Unit) {
    val context = LocalContext.current
    val notifications = remember(context) { context.getSystemService(NotificationManager::class.java) }
    fun allowedNow() = notifications.areNotificationsEnabled() &&
        notifications.getNotificationChannel("community_messages")?.importance != NotificationManager.IMPORTANCE_NONE
    var allowed by remember { mutableStateOf(allowedNow()) }
    var denied by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = allowedNow()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        denied = !granted
        if (granted) settings()
    }
    if (allowed && background) return
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Non perderti un «guardiamo insieme?»", fontWeight = FontWeight.SemiBold)
            Text(
                if (!allowed) {
                    "Attiva gli avvisi per messaggi, amicizie e inviti."
                } else {
                    "Per ricevere messaggi anche fuori dall’app, puoi mantenere la connessione attiva con una notifica Android."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                when {
                    allowed -> settings()
                    Build.VERSION.SDK_INT >= 33 &&
                        !denied &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED -> permission.launch(
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                    else -> context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            }) { Text(if (allowed) "Configura la ricezione" else "Attiva notifiche") }
        }
    }
}
