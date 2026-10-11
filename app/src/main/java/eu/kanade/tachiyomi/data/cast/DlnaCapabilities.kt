package eu.kanade.tachiyomi.data.cast

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import kotlin.math.roundToInt

data class DlnaLevelControl(val minimum: Int, val maximum: Int, val step: Int) {
    fun fromFraction(value: Float): Int {
        val bounded = value.coerceIn(0f, 1f)
        val steps = ((maximum - minimum) * bounded / step).roundToInt()
        return (minimum + steps * step).coerceIn(minimum, maximum)
    }
    fun toFraction(value: Int): Float = ((value - minimum).toFloat() / (maximum - minimum)).coerceIn(0f, 1f)
}

data class DlnaCapabilities(val volume: DlnaLevelControl?, val brightness: DlnaLevelControl?) {
    companion object {
        fun parse(document: String): DlnaCapabilities {
            require(document.length <= 512 * 1024 && !document.contains("<!DOCTYPE", true))
            val doc = Jsoup.parse(document, "", Parser.xmlParser())
            val actions = doc.getElementsByTag("action").map { it.getElementsByTag("name").text() }.toSet()
            fun level(name: String): DlnaLevelControl? {
                if ("Get$name" !in actions || "Set$name" !in actions) return null
                val variable = doc.getElementsByTag("stateVariable")
                    .firstOrNull { it.getElementsByTag("name").text() == name } ?: return null
                val minimum = variable.getElementsByTag("minimum").text().toIntOrNull() ?: return null
                val maximum = variable.getElementsByTag("maximum").text().toIntOrNull() ?: return null
                val step = variable.getElementsByTag("step").text().toIntOrNull() ?: 1
                if (minimum < 0 || maximum > 65535 || maximum <= minimum || step < 1) return null
                return DlnaLevelControl(minimum, maximum, step)
            }
            return DlnaCapabilities(level("Volume"), level("Brightness"))
        }
    }
}
