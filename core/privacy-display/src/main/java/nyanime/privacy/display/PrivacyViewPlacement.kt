package nyanime.privacy.display

/** Insets compensate a vendor's expansion before it validates a region against the panel. */
data class PrivacyPanelExpansion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(listOf(left, top, right, bottom).all { it >= 0 })
    }
}

data class PrivacyViewPlacement(val displayRegion: PrivacyRegion, val localBounds: PrivacyBounds) {
    companion object {
        fun resolve(
            requested: PrivacyRegion,
            panel: PrivacyBounds,
            expansion: PrivacyPanelExpansion,
            hostScreenX: Int,
            hostScreenY: Int,
        ): PrivacyViewPlacement? {
            val safePanel = PrivacyBounds(
                panel.left + expansion.left,
                panel.top + expansion.top,
                panel.right - expansion.right,
                panel.bottom - expansion.bottom,
            )
            if (safePanel.empty) return null
            val bounds = requested.bounds.intersect(safePanel) ?: return null
            return PrivacyViewPlacement(
                requested.copy(bounds = bounds),
                bounds.translate(-hostScreenX, -hostScreenY),
            )
        }
    }
}
