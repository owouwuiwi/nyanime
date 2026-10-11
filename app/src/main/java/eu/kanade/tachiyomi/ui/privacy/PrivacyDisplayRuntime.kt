package eu.kanade.tachiyomi.ui.privacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nyanime.privacy.display.AndroidPrivacyDisplayBackends
import nyanime.privacy.display.PrivacyDisplayCapability
import nyanime.privacy.display.PrivacyDisplayState
import nyanime.privacy.display.PrivacyUnavailableReason

/** Only device/API facts live here; never a Window, Context, Activity or View. */
object PrivacyDisplayRuntime {
    private val mutableFailure = MutableStateFlow<PrivacyDisplayState.Failed?>(null)
    val failure: StateFlow<PrivacyDisplayState.Failed?> = mutableFailure

    fun recordFailure(state: PrivacyDisplayState.Failed) {
        mutableFailure.value = state
    }
    val backend by lazy { AndroidPrivacyDisplayBackends.create() }

    fun capability(): PrivacyDisplayCapability {
        if (mutableFailure.value != null) return PrivacyDisplayCapability.Unavailable(PrivacyUnavailableReason.FIRMWARE)
        return backend.capability
    }
}
