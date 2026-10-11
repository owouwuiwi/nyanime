package eu.kanade.presentation.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import eu.kanade.presentation.theme.LocalNyanimeStyle
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val LocalMotionEnabled = compositionLocalOf<Boolean?> { null }

/** Observe once per themed UI rather than once for every image, badge and animation. */
@Composable
internal fun ProvideMotionPreferences(content: @Composable () -> Unit) {
    if (LocalMotionEnabled.current != null) {
        content()
        return
    }
    val enabled = if (LocalInspectionMode.current) {
        true
    } else {
        val preference = remember { Injekt.get<PlayerPreferences>().reduceMotion() }
        !preference.collectAsState().value
    }
    CompositionLocalProvider(LocalMotionEnabled provides enabled, content = content)
}

@Composable
internal fun modernMotionEnabled(): Boolean {
    if (!LocalNyanimeStyle.current) return false
    return appMotionEnabled()
}

@Composable
internal fun appMotionEnabled(): Boolean {
    return LocalMotionEnabled.current ?: (
        LocalInspectionMode.current || !Injekt.get<PlayerPreferences>().reduceMotion().collectAsState().value
        )
}
