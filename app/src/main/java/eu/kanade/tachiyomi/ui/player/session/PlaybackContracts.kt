package eu.kanade.tachiyomi.ui.player.session

import android.content.Intent
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.serialize
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import kotlinx.coroutines.flow.StateFlow

data class PlaybackRequest(
    val animeId: Long,
    val episodeId: Long,
    val hosters: List<Hoster>? = null,
    val hosterIndex: Int = -1,
    val videoIndex: Int = -1,
    val startPositionMs: Long? = null,
) {
    internal fun intent(): Intent = Intent().apply {
        putExtra("animeId", animeId)
        putExtra("episodeId", episodeId)
        hosters?.let { putExtra("hostList", it.serialize()) }
        putExtra("hostIndex", hosterIndex)
        putExtra("vidIndex", videoIndex)
        startPositionMs?.let { putExtra(PlayerActivity.SHARED_START, it) }
    }
}

enum class PlaybackPresentation { Page, Mini, Fullscreen }

/** Starting a selection never inherits a minimized presentation from the previous session. */
enum class PlaybackStartMode(val presentation: PlaybackPresentation) {
    Page(PlaybackPresentation.Page),
    Fullscreen(PlaybackPresentation.Fullscreen),
}

data class PlaybackSnapshot(
    val sessionId: Long,
    val animeId: Long? = null,
    val episodeId: Long? = null,
    val title: String = "",
    val episode: String = "",
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val paused: Boolean = true,
    val buffering: Boolean = false,
    val aspect: Double = 16.0 / 9.0,
    val preparingSelection: Boolean = false,
    val live: Boolean = false,
    val seekable: Boolean = true,
)

interface PlaybackCommands {
    val snapshot: StateFlow<PlaybackSnapshot>
    fun playPause()
    fun seek(seconds: Double)
    fun next()
    fun close()
}
