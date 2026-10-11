package eu.kanade.domain.extension

/** The host interprets a small declarative protocol; it never evaluates addon scripts. */
data class AddonActivation(val gesture: String, val holdMillis: Long) {
    val isMoreAction get() = gesture == "more_action"
    companion object {
        fun parse(gesture: String?, holdMillis: Long, api: Int = 1): AddonActivation? =
            if (api in 1..2 && gesture == "hold_logo" && holdMillis in 500L..3_000L) {
                AddonActivation(gesture, holdMillis)
            } else if (api == 2 && gesture == "more_action" && holdMillis == 0L) {
                AddonActivation(gesture, 0L)
            } else {
                null
            }

        fun trusted(
            approvedSigners: Set<String>,
            currentSigners: Set<String>,
            signerHistory: Set<String>,
            approvedDistribution: String?,
            currentDistribution: String?,
        ): Boolean = approvedSigners.isNotEmpty() &&
            currentSigners.isNotEmpty() &&
            approvedDistribution != null &&
            approvedDistribution == currentDistribution &&
            (
                approvedSigners == currentSigners ||
                    approvedSigners.size == 1 &&
                    currentSigners.size == 1 &&
                    signerHistory.containsAll(approvedSigners)
                )
    }
}
