package nyanime.privacy.display

import android.graphics.Rect
import android.view.View

/** Android global-visible rectangles are root-relative, not physical display coordinates. */
object PrivacyViewGeometry {
    fun boundsInWindow(view: View): PrivacyBounds? {
        if (!view.isAttachedToWindow || !view.isShown || view.alpha <= 0f) return null
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible)) return null
        val origin = IntArray(2)
        view.rootView.getLocationInWindow(origin)
        return PrivacyBounds(visible.left, visible.top, visible.right, visible.bottom).translate(origin[0], origin[1])
    }

    fun windowToDisplay(bounds: PrivacyBounds, root: View): PrivacyBounds {
        val screen = IntArray(2)
        val window = IntArray(2)
        root.getLocationOnScreen(screen)
        root.getLocationInWindow(window)
        return bounds.translate(screen[0] - window[0], screen[1] - window[1])
    }
}
