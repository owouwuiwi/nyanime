package eu.kanade.presentation.discovery

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow

/** The Home and search pages share one header outside their content transition. */
class DiscoveryHeaderHost {
    data class State(val logo: SourceHomeLogo? = null, val refreshKey: Int = 0, val hasUpdates: Boolean = false)

    val state = MutableStateFlow(State())
    var onUpdates: (() -> Unit)? = null
        private set

    fun update(logo: SourceHomeLogo?, refreshKey: Int, hasUpdates: Boolean, onUpdates: (() -> Unit)?) {
        this.onUpdates = onUpdates
        state.value = State(logo, refreshKey, hasUpdates)
    }
}

val LocalDiscoveryHeaderHost = compositionLocalOf<DiscoveryHeaderHost?> { null }
