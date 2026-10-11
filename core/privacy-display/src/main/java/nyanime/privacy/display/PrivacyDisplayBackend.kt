package nyanime.privacy.display

enum class PrivacyUnavailableReason { HARDWARE, FIRMWARE, DISPLAY, WINDOW_MODE }

sealed interface PrivacyDisplayCapability {
    data object Available : PrivacyDisplayCapability
    data class Unavailable(val reason: PrivacyUnavailableReason) : PrivacyDisplayCapability
}

/** The target is platform-owned; implementations must not retain it after an operation. */
interface PrivacyDisplayBackend<T : Any> {
    val capability: PrivacyDisplayCapability

    /** Returns the fitted region, or null when clipping leaves no applicable pixels. */
    fun apply(target: T, region: PrivacyRegion, previous: PrivacyRegion?): Result<PrivacyRegion?>
    fun clear(target: T): Result<Unit>
}

sealed interface PrivacyDisplayState {
    data object Disabled : PrivacyDisplayState
    data class Applied(val region: PrivacyRegion) : PrivacyDisplayState
    data class Unavailable(val reason: PrivacyUnavailableReason) : PrivacyDisplayState
    data class Failed(val operation: Operation) : PrivacyDisplayState
    enum class Operation { APPLY, CLEAR }
}
