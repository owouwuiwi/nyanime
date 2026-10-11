package eu.kanade.tachiyomi.ui.player

internal data class PlaybackChromeVisibility(val controls: Boolean, val waiting: Boolean)

/** Native cache/seek events do not express a request to open the transport controls. */
internal fun playbackChromeVisibility(
    controlsRequested: Boolean,
    loadingEpisode: Boolean,
    roomBusy: Boolean,
    load: PlaybackLoadMonitor.State,
): PlaybackChromeVisibility {
    val controls = controlsRequested || loadingEpisode || load.opening || roomBusy || load.failure != null
    return PlaybackChromeVisibility(controls = controls, waiting = load.loading && !controls)
}
