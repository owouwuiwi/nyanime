package eu.kanade.tachiyomi.ui.more

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.data.updater.AppUpdateRepository
import eu.kanade.tachiyomi.data.updater.UpdateInstallGate
import eu.kanade.tachiyomi.data.updater.UpdateScreenPhase
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** The same completed WorkManager download stays actionable after leaving the release notes. */
@Composable
fun ReadyAppUpdateSurface(allowDismiss: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember { Injekt.get<UiPreferences>() }
    val dismissed by preferences.dismissedReadyUpdate().changes().collectAsState(
        initial = preferences.dismissedReadyUpdate().get(),
    )
    val repository = remember { AppUpdateRepository.get(context) }
    val snapshot by repository.state.collectAsState()
    val ready = snapshot.latest?.ready
    val visible = ready != null && (!allowDismiss || dismissed != ready.id)
    val motion = appMotionEnabled()
    val model = remember { UpdateInstallScreenModel() }
    val gate by model.state.collectAsState()
    val error by model.installError.collectAsState()
    // Keep the permission round trip alive while a foreground file recheck is pending.
    UpdateInstallationEffect(model, ready, true, snapshot.latest?.phase == UpdateScreenPhase.READY)

    AnimatedVisibility(
        visible = visible,
        enter = if (motion) ModernMotion.enter() else EnterTransition.None,
        exit = if (motion) ModernMotion.exit() else ExitTransition.None,
        modifier = modifier,
    ) {
        val update = ready ?: return@AnimatedVisibility
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Outlined.NewReleases,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            stringResource(AYMR.strings.ready_update_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(AYMR.strings.ready_update_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (error != null) {
                    Text(
                        error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = gate.stage == UpdateInstallGate.Stage.IDLE,
                        onClick = {
                            model.request(update.id)
                            repository.refresh()
                        },
                    ) {
                        Text(stringResource(AYMR.strings.ready_update_install))
                    }
                    if (allowDismiss) {
                        OutlinedButton(onClick = { preferences.dismissedReadyUpdate().set(update.id) }) {
                            Text(stringResource(MR.strings.action_not_now))
                        }
                    }
                }
            }
        }
    }
}
