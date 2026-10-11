package eu.kanade.tachiyomi.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.serialize
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.session.PlaybackSessionService
import eu.kanade.tachiyomi.ui.player.session.PlaybackSessions
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Compatibility entry point for existing episode links and notifications. Playback belongs to the service. */
class PlayerActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    private fun route(request: Intent) {
        PlaybackSessionService.play(this, request)
        PlaybackSessions.current.value?.present(Injekt.get<PlayerPreferences>().startPresentation().get().presentation)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = PlaybackSessionService.OPEN_START
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    companion object {
        internal const val SHARED_START = "nyanime.shared.start_ms"
        internal const val MPV_DIR = "mpv"

        fun newIntent(
            context: Context,
            animeId: Long?,
            episodeId: Long?,
            hostList: List<Hoster>? = null,
            hostIndex: Int? = null,
            vidIndex: Int? = null,
            startPositionMs: Long? = null,
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("animeId", animeId)
            putExtra("episodeId", episodeId)
            startPositionMs?.let { putExtra(SHARED_START, it) }
            hostIndex?.let { putExtra("hostIndex", it) }
            vidIndex?.let { putExtra("vidIndex", it) }
            hostList?.let { putExtra("hostList", it.serialize()) }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
