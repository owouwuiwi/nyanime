package eu.kanade.tachiyomi.ui.player.session

import androidx.compose.runtime.staticCompositionLocalOf
import eu.kanade.tachiyomi.ui.player.PlayerSession

@Suppress("CompositionLocalAllowlist")
internal val LocalPlayerSession = staticCompositionLocalOf<PlayerSession> {
    error("Player tools require the service-owned playback session")
}
