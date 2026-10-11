package eu.kanade.tachiyomi.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.ui.ThemeController
import eu.kanade.domain.ui.ThemeSettingsRepository
import eu.kanade.domain.ui.resolveDarkTheme
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.theme.ThemeChoiceScreen
import eu.kanade.tachiyomi.data.theme.systemDarkTheme
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** The navigator is composed only after confirmation, so launch intents stay unconsumed. */
@Composable
fun InitialThemeChoiceGate(onReady: () -> Unit, content: @Composable () -> Unit) {
    val repository = remember { Injekt.get<ThemeSettingsRepository>() }
    val controller = remember { Injekt.get<ThemeController>() }
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState(repository.current())
    if (settings.initialChoiceComplete) {
        content()
        return
    }
    var selected by rememberSaveable { mutableStateOf(settings.mode) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? ComponentActivity
    // Read the unoverridden system configuration; an existing explicit dark choice must not
    // turn the System preview dark when Android itself is light.
    isSystemInDarkTheme()
    val dark = resolveDarkTheme(selected, systemDarkTheme())
    val animationsEnabled = appMotionEnabled()
    BackHandler { activity?.finish() }
    LaunchedEffect(Unit) { onReady() }
    TachiyomiPreviewTheme(darkTheme = dark) {
        LaunchedEffect(dark) {
            activity?.enableEdgeToEdge(
                statusBarStyle = if (dark) {
                    SystemBarStyle.dark(
                        Color.TRANSPARENT,
                    )
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
                },
                navigationBarStyle = if (dark) {
                    SystemBarStyle.dark(
                        Color.TRANSPARENT,
                    )
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
                },
            )
        }
        ThemeChoiceScreen(
            selected,
            saving,
            error,
            onSelect = {
                selected = it
                error = false
            },
            onContinue = {
                if (!saving) {
                    saving = true
                    scope.launch {
                        error = controller.select(selected, completeInitialChoice = true).isFailure
                        saving = false
                    }
                }
            },
            animationsEnabled = animationsEnabled,
        )
    }
}
