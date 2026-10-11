package eu.kanade.tachiyomi.ui.privacy

import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import nyanime.privacy.display.AndroidPrivacyDisplayTarget
import nyanime.privacy.display.PrivacyBounds
import nyanime.privacy.display.PrivacyDisplayCapability
import nyanime.privacy.display.PrivacyDisplaySession
import nyanime.privacy.display.PrivacyDisplayState
import nyanime.privacy.display.PrivacyRegion
import nyanime.privacy.display.PrivacyUnavailableReason
import nyanime.privacy.display.PrivacyViewGeometry
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.aniyomi.AYMR

/** Lifecycle/geometry bridge. It never touches the native player or its playback state. */
class PrivacyDisplayController(
    private val activity: BaseActivity,
    private val preferences: PrivacyDisplayPreferences,
    globalIncognito: Preference<Boolean>,
) : DefaultLifecycleObserver, ViewTreeObserver.OnPreDrawListener {
    private data class Declaration(
        val area: PrivacyArea,
        val scope: PrivacyArea,
        val incognito: Boolean?,
        val rootView: () -> View,
        val boundsInWindow: () -> PrivacyBounds?,
    )
    private val declarations = linkedMapOf<Any, Declaration>()
    private val contextJobs = mutableMapOf<PrivacyArea, Job>()
    private val contextOwners = mutableMapOf<PrivacyArea, Any?>()
    private var host: View? = null
    private var observedTree: ViewTreeObserver? = null
    private var session: PrivacyDisplaySession<AndroidPrivacyDisplayTarget>? = null
    private var target: AndroidPrivacyDisplayTarget? = null
    private var started = false
    private var closed = false
    private var policy = PrivacyDisplayPolicy(
        enabled = preferences.enabled().get(),
        selectedAreas = PrivacyArea.entries.filterTo(mutableSetOf()) { preferences.area(it).get() },
        onlyInIncognito = preferences.onlyInIncognito().get(),
        incognito = globalIncognito.get(),
    )
    private val mutableConfiguration = MutableStateFlow(policy)
    val configuration: StateFlow<PrivacyDisplayPolicy> = mutableConfiguration
    private val mutableState = MutableStateFlow<PrivacyDisplayState>(PrivacyDisplayState.Disabled)
    val state: StateFlow<PrivacyDisplayState> = mutableState
    private val mutableEligibleAreas = MutableStateFlow(eligibleAreasForPolicy())
    val eligibleAreas: StateFlow<Set<PrivacyArea>> = mutableEligibleAreas

    init {
        activity.lifecycle.addObserver(this)
        combine(
            listOf(
                preferences.enabled().changes(),
                preferences.onlyInIncognito().changes(),
                globalIncognito.changes(),
            ) +
                PrivacyArea.entries.map {
                    preferences.area(it).changes()
                },
        ) { values ->
            values
        }.onEach { values ->
            policy = policy.copy(
                enabled = values[0],
                onlyInIncognito = values[1],
                incognito = values[2],
                selectedAreas = PrivacyArea.entries.filterIndexed { index, _ -> values[index + 3] }.toSet(),
            )
            updatePolicyState()
            reconcileListener()
        }.launchIn(activity.lifecycleScope)
        PrivacyDisplayRuntime.failure.onEach {
            updatePolicyState()
            reconcileListener()
        }.launchIn(activity.lifecycleScope)
    }

    fun setTemporaryOverride(scope: PrivacyArea, enabled: Boolean?) {
        if (closed) return
        policy = policy.withTemporaryOverride(scope, enabled)
        updatePolicyState()
        reconcileListener()
    }

    /** Context observations are dormant unless automatic incognito protection can use them. */
    fun observeIncognito(scope: PrivacyArea, states: Flow<Boolean>, owner: Any? = null) {
        if (closed) return
        contextJobs.remove(scope)?.cancel()
        contextOwners[scope] = owner
        contextJobs[scope] = combine(configuration, PrivacyDisplayRuntime.failure) { config, _ ->
            PrivacyDisplayRuntime.capability() == PrivacyDisplayCapability.Available &&
                config.enabled &&
                config.onlyInIncognito &&
                config.temporaryOverrides[scope] == null &&
                (config.canRequest(scope) || config.canRequest(PrivacyArea.NSFW, scope))
        }.distinctUntilChanged().flatMapLatest { needed ->
            if (needed) states.map<Boolean, Boolean?> { it } else flowOf(null)
        }.onEach { incognito ->
            val next = policy.withIncognitoContext(scope, incognito)
            if (next != policy) {
                policy = next
                updatePolicyState()
                reconcileListener()
            }
        }.launchIn(activity.lifecycleScope)
    }

    /** A disposable media host can outlive its Activity without retaining a closed content flow. */
    fun stopObservingIncognito(scope: PrivacyArea, owner: Any) {
        if (contextOwners[scope] !== owner) return
        contextOwners.remove(scope)
        contextJobs.remove(scope)?.cancel()
        policy = policy.withIncognitoContext(scope, null)
        updatePolicyState()
        reconcileListener()
    }

    /** Window-relative pixels; the controller alone translates them to display pixels. */
    fun register(
        key: Any,
        area: PrivacyArea,
        scope: PrivacyArea = area,
        incognito: Boolean? = null,
        rootView: () -> View,
        boundsInWindow: () -> PrivacyBounds?,
    ) {
        if (closed) return
        declarations[key] = Declaration(area, scope, incognito, rootView, boundsInWindow)
        reconcileListener()
    }

    fun unregister(key: Any) {
        declarations.remove(key)
        reconcileListener()
    }

    fun registerView(view: View, area: PrivacyArea, scope: PrivacyArea = area, eligible: () -> Boolean = { true }) {
        register(view to area, area, scope, rootView = { view.rootView }) {
            if (eligible()) PrivacyViewGeometry.boundsInWindow(view) else null
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        started = true
        reconcileListener()
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        reconcileListener()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        closed = true
        detachListener()
        session?.close()
        session = null
        target?.close()
        target = null
        host = null
        declarations.clear()
        contextJobs.values.forEach { it.cancel() }
        contextJobs.clear()
        contextOwners.clear()
        owner.lifecycle.removeObserver(this)
    }

    private fun selected(declaration: Declaration) =
        policy.permits(declaration.area, declaration.scope, declaration.incognito)

    private fun updatePolicyState() {
        mutableConfiguration.value = policy
        mutableEligibleAreas.value = eligibleAreasForPolicy()
    }

    private fun eligibleAreasForPolicy(): Set<PrivacyArea> = PrivacyArea.entries.filterTo(mutableSetOf()) {
        policy.canRequest(it) && PrivacyDisplayRuntime.capability() == PrivacyDisplayCapability.Available
    }

    private fun reconcileListener() {
        if (closed) return
        val wanted = started && declarations.values.any { selected(it) }
        if (!wanted) {
            detachListener()
            session?.update(null, false)
            return
        }
        val capability = PrivacyDisplayRuntime.capability()
        if (capability is PrivacyDisplayCapability.Unavailable) {
            session?.update(null, false)
            acceptState(PrivacyDisplayState.Unavailable(capability.reason))
            detachListener()
            return
        }
        val decor = activity.window.decorView
        if (host !== decor) {
            detachListener()
            session?.close()
            target?.close()
            val parent = decor as? ViewGroup ?: run {
                acceptState(PrivacyDisplayState.Unavailable(PrivacyUnavailableReason.WINDOW_MODE))
                return
            }
            host = decor
            val surface = AndroidPrivacyDisplayTarget(parent).also { target = it }
            session = PrivacyDisplaySession(surface, PrivacyDisplayRuntime.backend, ::acceptState)
        }
        if (observedTree?.isAlive != true) {
            observedTree = decor.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }
        decor.invalidate()
    }

    override fun onPreDraw(): Boolean {
        val decor = host ?: return true
        val displayId = decor.display?.displayId ?: return true
        val unavailable = when {
            displayId != Display.DEFAULT_DISPLAY -> PrivacyUnavailableReason.DISPLAY
            activity.isInPictureInPictureMode || activity.isInMultiWindowMode -> PrivacyUnavailableReason.WINDOW_MODE
            declarations.values.any { selected(it) && it.rootView() !== decor } -> PrivacyUnavailableReason.WINDOW_MODE
            else -> null
        }
        val visible = PrivacyViewGeometry.boundsInWindow(decor)
        if (visible == null) {
            session?.update(null, false)
            return true
        }
        val viewport = PrivacyRegion(PrivacyViewGeometry.windowToDisplay(visible, decor), displayId)
        val screen = IntArray(2)
        val window = IntArray(2)
        decor.getLocationOnScreen(screen)
        decor.getLocationInWindow(window)
        val dx = screen[0] - window[0]
        val dy = screen[1] - window[1]
        val regions = declarations.values.mapNotNull { declaration ->
            if (!selected(declaration) ||
                PrivacyDisplayRuntime.capability() != PrivacyDisplayCapability.Available
            ) {
                return@mapNotNull null
            }
            declaration.boundsInWindow()?.takeUnless { it.empty }?.let { bounds ->
                PrivacyRegion(
                    PrivacyBounds(bounds.left + dx, bounds.top + dy, bounds.right + dx, bounds.bottom + dy),
                    displayId,
                )
            }
        }
        session?.update(PrivacyRegion.enclosing(regions, viewport), true, unavailable)
        return true
    }

    private fun acceptState(next: PrivacyDisplayState) {
        mutableState.value = next
        if (next is PrivacyDisplayState.Applied && !preferences.everApplied().get()) preferences.everApplied().set(true)
        if (next is PrivacyDisplayState.Failed || next is PrivacyDisplayState.Unavailable) {
            if (preferences.claimFailureWarning()) {
                Toast.makeText(
                    activity,
                    activity.stringResource(AYMR.strings.privacy_display_interrupted),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        if (next is PrivacyDisplayState.Failed) {
            PrivacyDisplayRuntime.recordFailure(next)
            updatePolicyState()
            detachListener()
        }
    }

    private fun detachListener() {
        observedTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observedTree = null
    }
}
