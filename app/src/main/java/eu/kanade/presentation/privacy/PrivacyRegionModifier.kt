package eu.kanade.presentation.privacy

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import eu.kanade.presentation.motion.posterNavigationRunning
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.ui.privacy.PrivacyContentReference
import eu.kanade.tachiyomi.ui.privacy.PrivacyIncognitoResolver
import nyanime.privacy.display.PrivacyBounds
import nyanime.privacy.display.PrivacyViewGeometry
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private class RegionCoordinates(var coordinates: LayoutCoordinates? = null, var transitionRunning: Boolean = false)

/** Declarative only: coordinates are sampled before drawing, including parent transforms/clipping. */
fun Modifier.privacyRegion(
    area: PrivacyArea?,
    enabled: Boolean = true,
    reference: PrivacyContentReference? = null,
): Modifier = composed {
    val view = LocalView.current
    val controller = remember(view) { view.context.baseActivity()?.privacyDisplayController }
    if (controller == null || !enabled || area == null) return@composed this
    val eligibleAreas by controller.eligibleAreas.collectAsState()
    if (area !in eligibleAreas) return@composed this
    val policy by controller.configuration.collectAsState()
    val incognito = if (policy.onlyInIncognito && reference != null && policy.temporaryOverrides[area] == null) {
        val resolver = remember { Injekt.get<PrivacyIncognitoResolver>() }
        key(reference) {
            val changes = remember(reference, resolver) { resolver.subscribe(reference.media, reference.sourceId) }
            val privateContent by changes.collectAsState(initial = resolver.current(reference))
            privateContent
        }
    } else {
        null
    }
    if (!policy.permits(area, incognitoContext = incognito)) return@composed this
    val coordinates = remember { RegionCoordinates() }
    val transitionRunning = posterNavigationRunning()
    SideEffect { coordinates.transitionRunning = transitionRunning }
    val key = remember { Any() }
    DisposableEffect(controller, key, area, incognito) {
        controller.register(key, area, incognito = incognito, rootView = { view.rootView }) {
            if (coordinates.transitionRunning) return@register PrivacyViewGeometry.boundsInWindow(view.rootView)
            coordinates.coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.let {
                PrivacyBounds.enclosing(it.left, it.top, it.right, it.bottom)
            }
        }
        onDispose { controller.unregister(key) }
    }
    this.onGloballyPositioned { coordinates.coordinates = it }
}

internal fun Context.baseActivity(): BaseActivity? = when (this) {
    is BaseActivity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.baseActivity()
    else -> null
}
