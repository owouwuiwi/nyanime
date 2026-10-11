package eu.kanade.tachiyomi.ui.player

import eu.kanade.tachiyomi.ui.player.settings.AudioChannels
import `is`.xyz.mpv.MPVLib

/** Own only the channel swap filter; user filters remain in the audio chain. */
internal fun applyAudioChannels(
    channels: AudioChannels,
    previous: AudioChannels? = null,
    setProperty: (String, String) -> Unit = { name, value -> MPVLib.setPropertyString(name, value) },
    command: (Array<String>) -> Unit = { MPVLib.command(it) },
) {
    if (channels == previous) return
    if (previous == AudioChannels.ReverseStereo) {
        command(arrayOf("af", "remove", "@ultrayomi_reverse_stereo"))
    }
    setProperty("audio-channels", if (channels == AudioChannels.ReverseStereo) "stereo" else channels.value)
    if (channels == AudioChannels.ReverseStereo) {
        command(arrayOf("af", "add", "@ultrayomi_reverse_stereo:${channels.value}"))
    }
}
