package eu.kanade.tachiyomi.ui.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.session.PlaybackSessionService
import eu.kanade.tachiyomi.ui.player.session.PlaybackSessions
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvLiveAdjustment
import nyanime.tv.api.TvLiveObservation
import nyanime.tv.api.TvRules

/** Preserves existing channel entry intents while the common playback service owns the renderer. */
class TvPlayerActivity : BaseActivity() {
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
        startActivity(
            Intent(this, MainActivity::class.java).setAction(PlaybackSessionService.OPEN_START)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
    fun openLive(ref: TvChannelRef) {
        PlaybackSessions.current.value?.openLive(ref)
    }
    fun liveObservation(): TvLiveObservation? = PlaybackSessions.current.value?.live?.value?.observation()
    fun liveAdjustment(value: TvLiveAdjustment) {
        PlaybackSessions.current.value?.live?.value?.adjustment(value)
    }
    companion object {
        fun intent(context: Context, ref: TvChannelRef): Intent {
            require(TvRules.valid(ref))
            return Intent(
                context,
                TvPlayerActivity::class.java,
            ).putExtra("source", ref.sourceId).putExtra("channel", ref.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
