package nyanime.panels

import kotlin.math.min

/** A fitted focus is navigation, not a user zoom, even when it enlarges a small panel. */
data class GuidedViewport(val scale: Float, val centerX: Float, val centerY: Float) {
    fun isEnlarged(currentScale: Float): Boolean = currentScale > scale * 1.02f

    fun restore(location: GuidedLocation?, pageScale: Float): GuidedViewport {
        if (location == null || !location.valid()) return this
        val restoredScale = location.focusZoom?.times(scale) ?: (location.zoom * pageScale)
        if (!isEnlarged(restoredScale)) return this
        return GuidedViewport(restoredScale, location.centerX, location.centerY)
    }

    companion object {
        fun fit(
            rect: PanelRect,
            imageWidth: Int,
            imageHeight: Int,
            width: Int,
            height: Int,
            pageScale: Float,
        ): GuidedViewport {
            require(rect.valid() && imageWidth > 0 && imageHeight > 0 && width > 0 && height > 0)
            require(pageScale.isFinite() && pageScale > 0)
            val bounds = rect.padded()
            val fitted = min(width / (bounds.width * imageWidth), height / (bounds.height * imageHeight)) * .96f
            return GuidedViewport(fitted.coerceAtLeast(pageScale), bounds.centerX, bounds.centerY)
        }
    }
}
