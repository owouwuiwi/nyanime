package eu.kanade.tachiyomi.ui.player

import kotlin.math.ceil
import kotlin.math.pow

/** Combines Android's discrete output curve with MPV's continuous cubic software volume. */
internal class FineVolumeCurve private constructor(private val amplitudes: DoubleArray) {
    val steps: Int get() = amplitudes.lastIndex

    data class Setting(val streamIndex: Int, val softwareVolume: Double)

    fun setting(level: Float, boostCap: Int): Setting {
        val bounded = level.takeIf { it.isFinite() }?.coerceIn(0f, 1f + boostCap.coerceAtLeast(0) / 100f) ?: 0f
        if (bounded == 0f) return Setting(0, 100.0)
        if (bounded >= 1f) return Setting(steps, bounded.toDouble() * 100)
        val position = bounded.toDouble() * steps
        val upper = ceil(position).toInt().coerceIn(1, steps)
        val lower = upper - 1
        val amplitude = amplitudes[lower] + (amplitudes[upper] - amplitudes[lower]) * (position - lower)
        // MPV's pinned native mixer applies (volume / 100)^3, not a linear percentage.
        return Setting(upper, (amplitude / amplitudes[upper]).pow(1.0 / 3.0) * 100)
    }

    fun level(streamIndex: Int, softwareVolume: Double): Float {
        val index = streamIndex.coerceIn(0, steps)
        val software = softwareVolume.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        if (index == 0 || software == 0.0) return 0f
        if (index == steps && software >= 100.0) return (software / 100).toFloat()
        val amplitude = amplitudes[index] * (software / 100).pow(3)
        if (amplitude >= 1) return amplitude.pow(1.0 / 3.0).toFloat()
        val upper = (1..steps).first { amplitudes[it] >= amplitude }
        val lower = upper - 1
        val fraction = (amplitude - amplitudes[lower]) / (amplitudes[upper] - amplitudes[lower])
        return ((lower + fraction) / steps).toFloat()
    }

    companion object {
        fun linear(steps: Int): FineVolumeCurve {
            val count = steps.coerceAtLeast(1)
            return FineVolumeCurve(DoubleArray(count + 1) { it.toDouble() / count })
        }

        /** Invalid or unavailable vendor curves retain fine software adjustment on a linear scale. */
        fun fromDecibels(decibels: DoubleArray): FineVolumeCurve {
            val fallback = linear(decibels.lastIndex)
            if (decibels.size < 2 || !decibels.last().isFinite()) return fallback
            val amplitudes = DoubleArray(decibels.size) { index ->
                if (index == 0) 0.0 else 10.0.pow((decibels[index] - decibels.last()) / 20)
            }
            if (amplitudes.any { !it.isFinite() } ||
                (1..amplitudes.lastIndex).any { amplitudes[it] <= amplitudes[it - 1] }
            ) {
                return fallback
            }
            return FineVolumeCurve(amplitudes)
        }
    }
}
