package eu.kanade.tachiyomi.data.watch

/** Caches ordinary samples so activity destruction never queries an already released native player. */
internal class WatchPlayerAttachment {
    var player: WatchPlayer? = null
        private set
    private var last = emptyPlayback()

    fun attach(player: WatchPlayer) {
        last = emptyPlayback()
        this.player = player
    }

    fun sample(): WatchPlayback = player?.sample()?.also { last = it } ?: last

    fun detach() {
        player = null
        last = last.copy(
            paused = true,
            ready = false,
            buffering = false,
            ended = false,
            upcoming = null,
            canAdvance = false,
            preparedNextKey = null,
            nextProblem = WatchProblem.None,
        )
    }

    fun forgetDetached() {
        if (player == null) last = emptyPlayback()
    }

    private fun emptyPlayback() = WatchPlayback(null, 0.0, true, false, false, 1.0, canAdvance = false)
}
