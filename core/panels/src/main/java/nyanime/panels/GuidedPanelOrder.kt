package nyanime.panels

/** Independent of page scrolling and orientation; zero marks an uninitialized legacy title. */
object GuidedPanelOrder {
    const val MASK = 0x180L

    fun stored(flags: Long): PanelDirection? = when (flags and MASK) {
        0x80L -> PanelDirection.RTL
        0x100L -> PanelDirection.LTR
        0x180L -> PanelDirection.VERTICAL
        else -> null
    }

    fun resolve(flags: Long, explicitlyLeftToRight: Boolean = false): PanelDirection =
        stored(flags) ?: if (explicitlyLeftToRight) PanelDirection.LTR else PanelDirection.RTL

    fun set(flags: Long, direction: PanelDirection): Long = (flags and MASK.inv()) or when (direction) {
        PanelDirection.RTL -> 0x80L
        PanelDirection.LTR -> 0x100L
        PanelDirection.VERTICAL -> 0x180L
    }

    fun initialize(flags: Long, explicitlyLeftToRight: Boolean): Long =
        if (stored(flags) != null) flags else set(flags, resolve(flags, explicitlyLeftToRight))

    /** The axis belongs to the page viewer; only horizontal navigation follows panel handedness. */
    fun swipeDelta(distance: Float, verticalAxis: Boolean, direction: PanelDirection): Int = when {
        !distance.isFinite() || distance == 0f -> 0
        !verticalAxis && direction == PanelDirection.RTL -> if (distance > 0f) 1 else -1
        else -> if (distance < 0f) 1 else -1
    }

    /** Panel numbers are not portable across orders. Restore geometry, or a unique legacy center. */
    fun restore(location: GuidedLocation?, panels: List<PanelRect>): GuidedLocation? {
        if (location == null || !location.valid() || panels.isEmpty()) return null
        if (location.overview) return location.copy(panel = 0, focusedPanel = null)
        val anchor = location.focusedPanel
        val exact = anchor?.let { panels.indexOf(it).takeIf { index -> index >= 0 } }
        val candidates = panels.indices.filter {
            panels[it].contains(anchor?.centerX ?: location.centerX, anchor?.centerY ?: location.centerY)
        }
        val index = exact ?: candidates.singleOrNull()
        return if (index != null) {
            location.copy(panel = index, focusedPanel = panels[index])
        } else {
            location.copy(panel = 0, centerX = .5f, centerY = .5f, overview = true, focusZoom = 1f, focusedPanel = null)
        }
    }
}
