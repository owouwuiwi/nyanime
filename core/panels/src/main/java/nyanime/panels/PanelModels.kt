package nyanime.panels

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

const val PANEL_ANALYZER_VERSION = 1
const val GUIDED_READING_FLAG = 0x40L
const val PANEL_INPUT_SIZE = 224

@Serializable
data class PanelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width * height
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun valid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
        width >= 0.002f &&
        height >= 0.002f
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun padded(amount: Float = 0.025f) = PanelRect(
        max(0f, left - width * amount),
        max(0f, top - height * amount),
        min(1f, right + width * amount),
        min(1f, bottom + height * amount),
    )
    fun intersection(other: PanelRect) =
        max(0f, min(right, other.right) - max(left, other.left)) *
            max(0f, min(bottom, other.bottom) - max(top, other.top))
    companion object {
        val PAGE = PanelRect(0f, 0f, 1f, 1f)
    }
}

enum class PanelDirection { LTR, RTL, VERTICAL }

@Serializable
data class PanelPageRef(val source: Long, val title: String, val chapter: String, val page: Int) {
    fun valid() = title.isNotBlank() &&
        chapter.isNotBlank() &&
        title.length <= 4096 &&
        chapter.length <= 4096 &&
        page in 0..100_000
}

@Serializable
data class GuidedLocation(
    val panel: Int = 0,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val zoom: Float = 1f,
    val overview: Boolean = false,
    /** Relative to the fitted focus, so rotation cannot turn automatic focus into manual zoom. */
    val focusZoom: Float? = null,
    /** Identifies the focused geometry even when its sequence number changes. Append for old backups. */
    val focusedPanel: PanelRect? = null,
) {
    fun valid() = panel in 0..255 &&
        centerX.isFinite() &&
        centerY.isFinite() &&
        centerX in 0f..1f &&
        centerY in 0f..1f &&
        zoom.isFinite() &&
        zoom in 0.1f..100f &&
        (focusZoom == null || focusZoom.isFinite() && focusZoom in 0.1f..100f) &&
        (focusedPanel == null || focusedPanel.valid())
}

@Serializable
data class GuidedPageRecord(
    val ref: PanelPageRef,
    val imageHash: String,
    val analyzerVersion: Int = PANEL_ANALYZER_VERSION,
    val corrections: List<PanelRect>? = null,
    val location: GuidedLocation? = null,
    val changedAt: Long = 0,
) {
    fun valid() = ref.valid() &&
        imageHash.matches(Regex("[a-f0-9]{64}")) &&
        analyzerVersion > 0 &&
        (
            corrections == null ||
                corrections.size in 1..256 &&
                corrections.all { it.valid() }
            ) &&
        (location == null || location.valid())
}

data class PanelAnalysis(val imageHash: String, val panels: List<PanelRect>, val width: Int, val height: Int)

/** Coordinates are always in the original page, independent of the selected reader or viewport. */
data class PanelTile(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun map(rect: PanelRect, imageWidth: Int, imageHeight: Int) = PanelRect(
        (x + rect.left * width) / imageWidth,
        (y + rect.top * height) / imageHeight,
        (x + rect.right * width) / imageWidth,
        (y + rect.bottom * height) / imageHeight,
    )
}
