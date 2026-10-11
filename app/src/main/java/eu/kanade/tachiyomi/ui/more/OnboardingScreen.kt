package eu.kanade.tachiyomi.ui.more

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.ThemeController
import eu.kanade.domain.ui.ThemeSettingsRepository
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.presentation.more.onboarding.OnboardingScreen
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.data.BackupBrowserScreen
import eu.kanade.presentation.theme.LocalDarkTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.ui.cloud.CloudScreen
import eu.kanade.tachiyomi.ui.cloud.LocalCloudSetupExit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.release.model.UpdateChannel
import tachiyomi.domain.release.service.AppUpdatePreferences
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OnboardingScreen(private val initialSetup: Boolean = false) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { WelcomeScreenModel(context.applicationContext, initialSetup) }
        val state by model.state.collectAsState()
        LaunchedEffect(state.completed) {
            // Initial setup exits through its gate; a settings review must leave its navigator explicitly.
            if (state.completed && !initialSetup) navigator.pop()
        }
        val themeRepository = remember { Injekt.get<ThemeSettingsRepository>() }
        val theme by themeRepository.settings.collectAsState(themeRepository.current())
        val storage = remember { Injekt.get<StoragePreferences>().baseStorageDirectory() }
        val selectedStorage by storage.collectAsState()
        LaunchedEffect(selectedStorage) { model.prepareStorage() }
        val pickStorage = SettingsDataScreen.storageLocationPicker(storage)
        val works by remember(context) { BackupRestoreJob.observe(context) }.collectAsState(initial = emptyList())
        val restore = works.firstOrNull { !it.state.isFinished } ?: works.lastOrNull()
        val restoring = restore?.state?.isFinished == false
        val restoreResult = restore?.takeIf { it.state.isFinished }?.let {
            if (it.state == androidx.work.WorkInfo.State.SUCCEEDED) it.outputData.getInt("error_count", 0) else -1
        }
        val activity = context as? ComponentActivity
        val dark = LocalDarkTheme.current
        LaunchedEffect(dark) {
            val style = if (dark) {
                SystemBarStyle.dark(
                    android.graphics.Color.TRANSPARENT,
                )
            } else {
                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK)
            }
            activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
        OnboardingScreen(
            step = state.step,
            storageReady = state.storageReady,
            managedStorage = state.managedStorage,
            storageError = state.storageError,
            saving = state.saving,
            error = state.error,
            theme = theme.mode,
            channel = state.channel,
            restoring = restoring,
            restoreResult = restoreResult,
            onBack = {
                if (state.step > 0) {
                    model.back()
                } else if (initialSetup) {
                    activity?.finish()
                } else {
                    navigator.pop()
                }
            },
            onNext = { if (!restoring) model.next(theme.mode) },
            onRetryStorage = model::prepareStorage,
            onRepairStorage = {
                try {
                    pickStorage.launch(null)
                } catch (
                    _: android.content.ActivityNotFoundException,
                ) {
                    model.storageFailure()
                }
            },
            onTheme = model::theme,
            onChannel = model::channel,
            onRestoreBackup = { navigator.push(BackupBrowserScreen(onboarding = true)) },
            onRestoreCloud = { navigator.push(WelcomeCloudScreen()) },
        )
    }
}

private class WelcomeCloudScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        CompositionLocalProvider(LocalCloudSetupExit provides { navigator.pop() }) {
            CloudScreen(initialSetup = true).Content()
        }
    }
}

private data class WelcomeState(
    val step: Int,
    val channel: UpdateChannel,
    val storageReady: Boolean = false,
    val managedStorage: Boolean = true,
    val storageError: Boolean = false,
    val saving: Boolean = false,
    val error: Boolean = false,
    val completed: Boolean = false,
)

private class WelcomeScreenModel(
    private val context: android.content.Context,
    private val initialSetup: Boolean,
) : StateScreenModel<WelcomeState>(
    WelcomeState(
        if (initialSetup) WelcomeSetup(Injekt.get()).currentStep() else 0,
        AppUpdatePreferences(Injekt.get()).channel(),
    ),
) {
    private val setup = WelcomeSetup(Injekt.get())
    private val themes = Injekt.get<ThemeSettingsRepository>()
    private val controller = Injekt.get<ThemeController>()
    private var preparing = false

    fun prepareStorage() {
        if (preparing) return
        preparing = true
        screenModelScope.launch {
            try {
                val result = WelcomeStorage.prepare(context, Injekt.get())
                mutableState.update {
                    it.copy(
                        storageReady = result.isSuccess,
                        managedStorage = result.getOrDefault(true),
                        storageError = result.isFailure,
                    )
                }
            } finally {
                preparing = false
            }
        }
    }

    fun storageFailure() {
        mutableState.update { it.copy(storageError = true) }
    }

    fun back() {
        if (state.value.saving) return
        moveTo(state.value.step - 1)
    }

    private fun moveTo(step: Int) {
        val nextStep = step.coerceIn(0, WelcomeSetup.LAST_STEP)
        if (initialSetup) setup.moveTo(nextStep)
        mutableState.update { it.copy(step = nextStep, error = false) }
    }

    fun channel(value: UpdateChannel) {
        if (!state.value.saving) mutableState.update { it.copy(channel = value) }
    }

    fun theme(value: ThemeMode) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true, error = false) }
        screenModelScope.launch {
            try {
                val result = controller.select(value)
                mutableState.update { it.copy(error = result.isFailure) }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    fun next(theme: ThemeMode) {
        if (state.value.saving || state.value.completed || !state.value.storageReady) return
        mutableState.update { it.copy(saving = true, error = false) }
        screenModelScope.launch {
            try {
                if (state.value.step == WelcomeSetup.LAST_STEP) {
                    setup.complete(Injekt.get<BasePreferences>(), themes, theme).getOrThrow()
                    mutableState.update { it.copy(completed = true) }
                } else {
                    if (state.value.step == 3) AppUpdatePreferences(Injekt.get()).confirm(state.value.channel)
                    moveTo(state.value.step + 1)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableState.update { it.copy(error = true) }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }
}
