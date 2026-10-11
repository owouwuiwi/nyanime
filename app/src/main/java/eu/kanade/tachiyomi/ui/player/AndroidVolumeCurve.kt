package eu.kanade.tachiyomi.ui.player

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build

/** Read once at the start of an adjustment, never in the per-frame gesture path. */
internal fun AudioManager.mediaVolumeCurve(): FineVolumeCurve {
    val maximum = getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    // Earlier platforms cannot reliably identify the active media route with the public API.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return FineVolumeCurve.linear(maximum)
    return runCatching {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val type = getAudioDevicesForAttributes(attributes).map { it.type }.distinct().singleOrNull()
            ?: return FineVolumeCurve.linear(maximum)
        FineVolumeCurve.fromDecibels(
            DoubleArray(maximum + 1) { getStreamVolumeDb(AudioManager.STREAM_MUSIC, it, type).toDouble() },
        )
    }.getOrElse { FineVolumeCurve.linear(maximum) }
}
