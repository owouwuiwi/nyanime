package eu.kanade.tachiyomi.data.watch

/** A room timeline without a decoder. It never claims that local video is ready or buffered. */
internal class WatchObserverPlayer(initial: WatchPlayback, private val now: () -> Long) : WatchPlayer {
    private var playback = initial.copy(
        ready = false,
        buffering = false,
        problem = WatchProblem.None,
        canAdvance = false,
        preparedNextKey = null,
    )
    private var sampledAt = now()

    override fun sample(): WatchPlayback {
        val time = now()
        val elapsed = ((time - sampledAt).coerceAtLeast(0) / 1000.0)
        val position = playback.position + if (playback.paused) 0.0 else elapsed * playback.speed
        return playback.copy(
            position = position.coerceIn(0.0, playback.media?.duration?.coerceAtLeast(0.0) ?: 0.0),
            ended = false,
        )
    }

    private fun checkpoint() {
        playback = sample()
        sampledAt = now()
    }

    override fun pause(paused: Boolean) {
        checkpoint()
        playback = playback.copy(paused = paused)
    }
    override fun seek(seconds: Double) {
        checkpoint()
        playback = playback.copy(position = seconds.coerceAtLeast(0.0))
    }
    override fun speed(value: Double) {
        checkpoint()
        playback = playback.copy(speed = value.coerceIn(0.25, 3.0))
    }

    fun follow(media: WatchMedia?) {
        if (media?.key == playback.media?.key) return
        checkpoint()
        playback = playback.copy(media = media, position = 0.0)
    }
}
