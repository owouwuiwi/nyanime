package eu.kanade.tachiyomi.ui.privacy

import nyanime.privacy.display.PrivacyDisplayCapability
import nyanime.privacy.display.PrivacyDisplayState

enum class PrivacyDisplayStatus {
    DISABLED,
    WAITING_FOR_INCOGNITO,
    WAITING_FOR_AREA,
    REQUESTED,
    SUSPENDED,
    UNAVAILABLE,
    FAILED,
}

/** Shared presentation model; requested intent is never inferred from a previous hardware state. */
data class PrivacyDisplayPresentation(
    val requested: Boolean,
    val canToggle: Boolean,
    val status: PrivacyDisplayStatus,
) {
    companion object {
        fun from(
            policy: PrivacyDisplayPolicy,
            capability: PrivacyDisplayCapability,
            state: PrivacyDisplayState,
            area: PrivacyArea? = null,
            failed: Boolean = false,
        ): PrivacyDisplayPresentation {
            val requested = if (area == null) {
                PrivacyArea.entries.any { policy.permits(it) }
            } else {
                policy.permits(area) ||
                    (state is PrivacyDisplayState.Applied && policy.permits(PrivacyArea.NSFW, area))
            }
            val eligible = if (area == null) {
                PrivacyArea.entries.any { policy.canRequest(it) }
            } else {
                policy.canRequest(area) || policy.canRequest(PrivacyArea.NSFW, area)
            }
            val available = capability == PrivacyDisplayCapability.Available
            val status = when {
                failed || state is PrivacyDisplayState.Failed -> PrivacyDisplayStatus.FAILED
                !available -> PrivacyDisplayStatus.UNAVAILABLE
                !requested && eligible && policy.onlyInIncognito -> PrivacyDisplayStatus.WAITING_FOR_INCOGNITO
                !requested -> PrivacyDisplayStatus.DISABLED
                state is PrivacyDisplayState.Unavailable -> PrivacyDisplayStatus.SUSPENDED
                state is PrivacyDisplayState.Applied -> PrivacyDisplayStatus.REQUESTED
                else -> PrivacyDisplayStatus.WAITING_FOR_AREA
            }
            return PrivacyDisplayPresentation(requested, available && status != PrivacyDisplayStatus.FAILED, status)
        }
    }
}
