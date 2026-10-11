package eu.kanade.tachiyomi.ui.addon

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.data.addon.AddonIntroduction
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Keep deep links intact until the explanation has actually been acknowledged. */
@Composable
fun InitialAddonIntroductionGate(onReady: () -> Unit, content: @Composable () -> Unit) {
    val introduction = remember { AddonIntroduction(Injekt.get()) }
    val complete by introduction.complete.collectAsState()
    if (complete) {
        content()
        return
    }
    val activity = LocalContext.current as? ComponentActivity
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    BackHandler { activity?.finish() }
    LaunchedEffect(dark) {
        onReady()
        val style = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    AddonIntroductionContent(onContinue = introduction::confirm)
}
