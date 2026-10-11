package nyanime.privacy.display

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.view.View
import android.view.ViewGroup

/**
 * A window-owned, noninteractive render node. Vendor positioning must never touch the content/decor.
 * ViewGroupOverlay does not measure or lay out the host, and does not receive input events.
 */
class AndroidPrivacyDisplayTarget(private var host: ViewGroup?) : AutoCloseable {
    private class Anchor(host: ViewGroup) : View(host.context) {
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            // A render node must participate in drawing; transparent pixels do not alter content.
            canvas.drawColor(Color.TRANSPARENT)
        }
    }

    private var anchor: View? = null
    internal val currentView: View? get() = anchor

    internal fun place(
        region: PrivacyRegion,
        expansion: PrivacyPanelExpansion,
        edgeClearancePx: Int,
    ): PrivacyViewPlacement? {
        val owner = checkNotNull(host)
        check(owner.isAttachedToWindow)
        val display = checkNotNull(owner.display)
        check(display.displayId == region.displayId)
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        val origin = IntArray(2)
        owner.getLocationOnScreen(origin)
        require(edgeClearancePx >= 0)
        val placement = PrivacyViewPlacement.resolve(
            region,
            PrivacyBounds(edgeClearancePx, edgeClearancePx, size.x - edgeClearancePx, size.y - edgeClearancePx),
            expansion,
            origin[0],
            origin[1],
        ) ?: return null
        val view = anchor ?: Anchor(owner).also {
            anchor = it
            owner.overlay.add(it)
        }
        with(placement.localBounds) { view.layout(left, top, right, bottom) }
        return placement
    }

    internal fun detach() {
        anchor?.let { host?.overlay?.remove(it) }
        anchor = null
    }

    override fun close() {
        detach()
        host = null
    }
}
