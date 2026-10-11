package nyanime.privacy.display

/** Serial, window-scoped operations. Applied means API acceptance, never an optical guarantee. */
class PrivacyDisplaySession<T : Any>(
    private var target: T?,
    private val backend: PrivacyDisplayBackend<T>,
    private val onState: (PrivacyDisplayState) -> Unit = {},
) : AutoCloseable {
    var state: PrivacyDisplayState = PrivacyDisplayState.Disabled
        private set
    private var applied: PrivacyRegion? = null
    private var requested: PrivacyRegion? = null
    private var faulted = false
    private var closed = false

    fun update(region: PrivacyRegion?, permitted: Boolean, unavailable: PrivacyUnavailableReason? = null) {
        if (closed || faulted) return
        val owner = target ?: return
        val reason = unavailable ?: (backend.capability as? PrivacyDisplayCapability.Unavailable)?.reason
        if (!permitted || region == null || reason != null) {
            if (applied != null) {
                if (backend.clear(owner).isFailure) {
                    faulted = true
                    publish(PrivacyDisplayState.Failed(PrivacyDisplayState.Operation.CLEAR))
                    return
                }
                applied = null
            }
            requested = null
            publish(
                if (permitted &&
                    reason != null
                ) {
                    PrivacyDisplayState.Unavailable(reason)
                } else {
                    PrivacyDisplayState.Disabled
                },
            )
            return
        }
        if (requested == region) return
        // Mark before calling: an enable that succeeds before position fails still needs cleanup.
        val previous = applied
        applied = region
        requested = region
        val result = backend.apply(owner, region, previous)
        if (result.isFailure) {
            faulted = true
            val cleanup = backend.clear(owner)
            if (cleanup.isSuccess) applied = null
            publish(
                PrivacyDisplayState.Failed(
                    if (cleanup.isFailure) PrivacyDisplayState.Operation.CLEAR else PrivacyDisplayState.Operation.APPLY,
                ),
            )
        } else {
            applied = result.getOrThrow()
            if (applied == null) {
                if (previous != null && backend.clear(owner).isFailure) {
                    applied = previous
                    faulted = true
                    publish(PrivacyDisplayState.Failed(PrivacyDisplayState.Operation.CLEAR))
                } else {
                    publish(PrivacyDisplayState.Disabled)
                }
            } else {
                publish(PrivacyDisplayState.Applied(checkNotNull(applied)))
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val owner = target
        target = null
        if (owner != null && applied != null && backend.clear(owner).isFailure) {
            publish(PrivacyDisplayState.Failed(PrivacyDisplayState.Operation.CLEAR))
        } else if (!faulted) {
            publish(PrivacyDisplayState.Disabled)
        }
        applied = null
        requested = null
    }

    private fun publish(next: PrivacyDisplayState) {
        if (state == next) return
        state = next
        onState(next)
    }
}
