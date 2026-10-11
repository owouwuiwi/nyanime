package eu.kanade.tachiyomi.ui.discovery

import android.content.Context
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import tachiyomi.domain.items.episode.model.Episode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Home, catalogue details and local resume use the same existing player entry point. */
suspend fun Context.playDiscoveryEpisode(episode: Episode, startPositionMs: Long? = null) {
    MainActivity.startPlayerActivity(
        this,
        episode.animeId,
        episode.id,
        Injekt.get<PlayerPreferences>().alwaysUseExternalPlayer().get(),
        startPositionMs = startPositionMs,
    )
}
