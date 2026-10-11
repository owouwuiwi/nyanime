package nyanime.privacy.display

import kotlin.math.ceil
import kotlin.math.floor

/** Android display-coordinate pixels, with exclusive right and bottom edges. */
data class PrivacyBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val empty: Boolean get() = left >= right || top >= bottom

    fun translate(dx: Int, dy: Int) = PrivacyBounds(left + dx, top + dy, right + dx, bottom + dy)

    fun intersect(other: PrivacyBounds): PrivacyBounds? = PrivacyBounds(
        maxOf(left, other.left),
        maxOf(top, other.top),
        minOf(right, other.right),
        minOf(bottom, other.bottom),
    ).takeUnless { it.empty }

    fun union(other: PrivacyBounds) = PrivacyBounds(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )

    companion object {
        fun enclosing(left: Float, top: Float, right: Float, bottom: Float): PrivacyBounds? {
            if (!listOf(left, top, right, bottom).all { it.isFinite() } || left >= right || top >= bottom) return null
            return PrivacyBounds(floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt())
        }
    }
}

data class PrivacyRegion(val bounds: PrivacyBounds, val displayId: Int, val cornerRadiusPx: Float = 0f) {
    init {
        require(!bounds.empty)
        require(cornerRadiusPx.isFinite() && cornerRadiusPx >= 0f)
    }

    companion object {
        /** A single conservative rectangle also covers overlapping outgoing/incoming content. */
        fun enclosing(regions: List<PrivacyRegion>, viewport: PrivacyRegion): PrivacyRegion? {
            val visible = regions.filter { it.displayId == viewport.displayId }
                .mapNotNull { it.bounds.intersect(viewport.bounds) }
            return visible.reduceOrNull(PrivacyBounds::union)?.let { PrivacyRegion(it, viewport.displayId) }
        }
    }
}
