package eu.kanade.tachiyomi.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.AppUpdateRepository
import eu.kanade.tachiyomi.data.updater.UpdateInstallGate
import eu.kanade.tachiyomi.data.updater.UpdateScreenPhase
import eu.kanade.tachiyomi.util.system.openInBrowser

class NewUpdateScreen(
    private val versionName: String,
    private val changelogInfo: String,
    private val releaseLink: String,
    private val downloadLink: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val repository = remember { AppUpdateRepository.get(context) }
        val model = rememberScreenModel { UpdateInstallScreenModel() }
        val historyModel = rememberScreenModel { UpdateHistoryScreenModel() }
        val history by historyModel.state.collectAsState()
        LaunchedEffect(historyModel) { historyModel.load(versionName, releaseLink) }
        val snapshot by repository.state.collectAsState()
        val download = snapshot.forRelease(downloadLink)
        val gate by model.state.collectAsState()
        val installError by model.installError.collectAsState()
        val visible = navigator.lastItem.key == key
        UpdateInstallationEffect(model, download.ready, visible, download.phase == UpdateScreenPhase.READY)
        LaunchedEffect(download.phase) {
            if (download.phase == UpdateScreenPhase.FAILED ||
                download.phase == UpdateScreenPhase.CANCELLED ||
                download.phase == UpdateScreenPhase.UNAVAILABLE
            ) {
                model.cancel()
            }
        }
        val close = {
            model.cancel()
            navigator.pop()
            Unit
        }
        BackHandler(enabled = visible, onBack = close)
        NewUpdateScreen(
            versionName = versionName,
            installedVersion = BuildConfig.VERSION_NAME,
            changelogInfo = changelogInfo,
            releaseHistory = history.releases,
            historyLoading = history.loading,
            historyUnavailable = history.unavailable,
            onOpenHistory = { navigator.push(UpdateHistoryScreen()) },
            phase = download.phase,
            downloadProgress = download.progress,
            installError = installError,
            openingInstaller = gate.stage == UpdateInstallGate.Stage.PERMISSION ||
                gate.stage == UpdateInstallGate.Stage.INSTALLER,
            onCancelDownload = {
                model.cancel()
                repository.stop()
            },
            onOpenInBrowser = { context.openInBrowser(releaseLink) },
            onRejectUpdate = close,
            onAcceptUpdate = {
                if (!download.phase.busy && gate.stage == UpdateInstallGate.Stage.IDLE) {
                    model.request(download.ready?.id)
                    if (download.ready != null) {
                        repository.refresh()
                    } else {
                        try {
                            repository.start(downloadLink, versionName)
                        } catch (_: Exception) {
                            model.failed(context.getString(R.string.app_update_failed_description))
                        }
                    }
                }
            },
        )
    }
}
