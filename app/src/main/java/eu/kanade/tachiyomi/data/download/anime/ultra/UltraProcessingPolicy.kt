package eu.kanade.tachiyomi.data.download.anime.ultra

/** Allow ordinary warmth with paced GPU work; stop on severe pressure, with restart hysteresis. */
internal class UltraProcessingPolicy(private var cooling: Boolean = false) {
    data class Environment(
        val interactive: Boolean,
        val screenOffOnly: Boolean,
        val playerActive: Boolean,
        val charging: Boolean,
        val chargingOnly: Boolean,
        val batteryPercent: Int?,
        val batteryCelsius: Float?,
        val thermalStatus: Int,
        val thermalHeadroom: Float?,
        val powerSave: Boolean,
        val lowMemory: Boolean = false,
    )

    fun waitingReason(value: Environment): String? {
        val temperature = value.batteryCelsius
        val headroom = value.thermalHeadroom?.takeIf { it.isFinite() }
        if (value.thermalStatus >= 3 ||
            (temperature != null && temperature >= 45f) ||
            (headroom != null && headroom >= 1f)
        ) {
            cooling = true
        }
        if (cooling) {
            val cool = value.thermalStatus <= 2 &&
                (temperature == null || temperature <= 42f) &&
                (headroom == null || headroom <= 0.9f)
            if (!cool) return COOLING
            cooling = false
        }
        return when {
            value.playerActive -> PLAYER
            value.lowMemory -> MEMORY
            value.screenOffOnly && value.interactive -> SCREEN
            value.chargingOnly && !value.charging -> CHARGING
            !value.charging && (value.batteryPercent ?: 100) < 15 -> BATTERY
            else -> null
        }
    }

    companion object {
        const val COOLING = "In pausa per raffreddamento"
        const val PLAYER = "In attesa della fine della riproduzione"
        const val SCREEN = "In attesa dello schermo spento"
        const val CHARGING = "In attesa del caricatore"
        const val BATTERY = "In attesa di almeno il 15% di batteria o del caricatore"
        const val MEMORY = "In attesa di memoria libera sul telefono"

        /** Small strips keep individual GPU submissions bounded without changing texture coordinates. */
        fun stripHeight(width: Int): Int = (262_144 / width.coerceAtLeast(1)).coerceIn(1, 128)

        /** 20% duty at rest, 10% while the phone is in use or already warm. */
        fun restNanos(workNanos: Long, interactiveOrWarm: Boolean): Long =
            workNanos.coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE / 9) * if (interactiveOrWarm) 9 else 4
    }
}
