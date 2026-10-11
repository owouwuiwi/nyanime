package eu.kanade.tachiyomi.ui.more

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.AppUpdateRepository
import eu.kanade.tachiyomi.data.updater.ReadyAppUpdate
import eu.kanade.tachiyomi.data.updater.UpdateInstallGate
import eu.kanade.tachiyomi.data.updater.appUpdatePermissionIntent
import eu.kanade.tachiyomi.data.updater.canInstallAppUpdates
import eu.kanade.tachiyomi.data.updater.installReadyAppUpdate

@Composable
internal fun UpdateInstallationEffect(
    model: UpdateInstallScreenModel,
    ready: ReadyAppUpdate?,
    visible: Boolean,
    validationFinished: Boolean,
) {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val gate by model.state.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        AppUpdateRepository.get(context).refresh()
        model.permissionReturned(
            context.canInstallAppUpdates(),
            context.getString(R.string.app_update_install_permission),
        )
    }
    val resumed = visible && lifecycle == Lifecycle.State.RESUMED
    LaunchedEffect(resumed, visible) {
        if (!visible) model.cancel()
        model.lifecycle(resumed)
        if (resumed) AppUpdateRepository.get(context).refresh()
    }
    LaunchedEffect(gate, ready?.id, resumed, validationFinished) {
        val verified = validationFinished && ready != null && AppUpdateRepository.get(context).isValidated(ready)
        when (model.claim(verified, resumed, context.canInstallAppUpdates(), ready?.id)) {
            UpdateInstallGate.Action.PERMISSION -> try {
                permission.launch(context.appUpdatePermissionIntent())
            } catch (_: Exception) {
                model.failed(context.getString(R.string.app_update_install_error))
            }
            UpdateInstallGate.Action.INSTALLER -> {
                val error = installReadyAppUpdate(context, checkNotNull(ready).apk)
                if (error != null) model.failed(error)
            }
            null -> Unit
        }
    }
}
