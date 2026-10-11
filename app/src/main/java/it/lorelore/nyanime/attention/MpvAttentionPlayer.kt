package it.lorelore.nyanime.attention

import eu.kanade.tachiyomi.ui.player.PlayerSession
import eu.kanade.tachiyomi.ui.player.session.PlaybackPresentation
import it.lorelore.nyanime.attention.core.AttentionControl
import it.lorelore.nyanime.attention.core.AttentionPlayback
import it.lorelore.nyanime.attention.core.AttentionPlayer

internal class MpvAttentionPlayer(private val session: PlayerSession) : AttentionPlayer {
    private var mediaIdentity: Triple<Long, String, String>? = null
    private var stableKey: String? = null
    private var lastCounterAt = 0L
    private var renderDrops: Long? = null
    override fun snapshot(): AttentionPlayback? {
        if (session.isDestroyed) return null
        val state = session.snapshot.value
        val model = session.viewModel
        val anime = model.currentAnime.value ?: return null
        val episode = model.currentEpisode.value ?: return null
        val identity = Triple(anime.source, anime.url, episode.url)
        if (mediaIdentity != identity) {
            mediaIdentity = identity
            stableKey = AttentionPreferences.key("native", anime.source.toString(), anime.url, episode.url)
        }
        val cast = session.castController.state.value
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCounterAt >= 1_000 && session.available) {
            lastCounterAt = now
            renderDrops =
                runCatching { `is`.xyz.mpv.MPVLib.getPropertyString("frame-drop-count")?.toLongOrNull() }.getOrNull()
        }
        return AttentionPlayback(
            key = checkNotNull(stableKey), positionMs = (state.positionSeconds * 1_000).toLong().coerceAtLeast(0),
            playing = !state.paused, buffering = state.buffering || state.preparingSelection,
            allowed = session.available &&
                session.player.surfaceReady.value &&
                !state.live &&
                !session.pip.value &&
                session.presentation.value != PlaybackPresentation.Mini &&
                !cast.active &&
                !cast.connecting &&
                !model.watchTogether.active,
            seekable = state.seekable, incognito = model.incognitoMode, revision = AttentionControl.revision(),
            renderDrops = renderDrops, playbackSpeed = model.playbackSpeed.value,
        )
    }
    override fun pause() {
        session.viewModel.pause()
    }
    override fun resume() {
        session.viewModel.unpause()
    }
    override fun seek(positionMs: Long) {
        session.seek(positionMs / 1_000.0)
    }
}
