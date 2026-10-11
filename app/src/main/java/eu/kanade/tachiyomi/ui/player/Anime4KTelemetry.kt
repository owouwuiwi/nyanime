package eu.kanade.tachiyomi.ui.player

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Bounded native-property snapshot from the bundled Lua adapter, sampled only while Smart is active. */
@Serializable
data class Anime4KTelemetry(
    val version: Int,
    val token: String,
    val sequence: Long,
    val playing: Boolean,
    val outputDrops: Long? = null,
    val decoderDrops: Long? = null,
    val delayedFrames: Long? = null,
    val mistimedFrames: Long? = null,
    val renderTimeMillis: Double? = null,
    val redrawTimeMillis: Double? = null,
) {
    fun sample(nowMillis: Long) = Anime4KPerformanceSample(
        timestampMillis = nowMillis,
        outputDroppedFrames = outputDrops?.takeIf { it >= 0 },
        decoderDroppedFrames = decoderDrops?.takeIf { it >= 0 },
        delayedFrames = delayedFrames?.takeIf { it >= 0 },
        mistimedFrames = mistimedFrames?.takeIf { it >= 0 },
        renderTimeMillis = renderTimeMillis?.takeIf { it.isFinite() && it > 0 && it < 10_000 },
        redrawTimeMillis = redrawTimeMillis?.takeIf { it.isFinite() && it >= 0 && it < 10_000 },
        playing = playing,
    )

    companion object {
        const val SCRIPT_NAME = "aniyomi_anime4k"
        const val PROPERTY = "user-data/aniyomi-anime4k/telemetry"
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(value: String): Anime4KTelemetry? = value.takeIf { it.length <= 4096 }?.let {
            runCatching { json.decodeFromString<Anime4KTelemetry>(it) }.getOrNull()
                ?.takeIf { it.version == 1 && it.token.length <= 32 && it.sequence >= 0 }
        }
    }
}

/** Rejects old episode/mode packets and throttles the JNI fallback before any property reads. */
class Anime4KTelemetrySession(val token: String, startedAtMillis: Long) {
    private var sequence = -1L
    private var lastNativeAtMillis = startedAtMillis
    private var lastFallbackAtMillis = startedAtMillis

    fun accept(telemetry: Anime4KTelemetry, nowMillis: Long): Anime4KPerformanceSample? {
        if (telemetry.token != token || telemetry.sequence <= sequence) return null
        sequence = telemetry.sequence
        lastNativeAtMillis = nowMillis
        return telemetry.sample(nowMillis)
    }

    fun shouldSampleFallback(nowMillis: Long): Boolean {
        if (nowMillis - lastNativeAtMillis < 2500L || nowMillis - lastFallbackAtMillis < 1000L) return false
        lastFallbackAtMillis = nowMillis
        return true
    }
}
