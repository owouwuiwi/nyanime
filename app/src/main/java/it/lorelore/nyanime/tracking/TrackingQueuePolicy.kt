package it.lorelore.nyanime.tracking

import kotlinx.serialization.Serializable

@Serializable
enum class TrackingEditOrigin { MANUAL, AUTOMATIC, LEGACY, UNDO }

@Serializable
enum class TrackingDelivery { WAITING, SENDING, UNCERTAIN, BLOCKED }

@Serializable
enum class TrackingQueueProblem {
    NONE,
    NETWORK,
    RATE_LIMIT,
    SERVICE,
    AUTHENTICATION,
    ACCOUNT,
    ASSOCIATION,
    REVIEW,
    CONFLICT,
    REJECTED,
    EXHAUSTED,
    PRIVACY,
}

@Serializable
data class TrackingEditReceipt(
    val id: String,
    val edit: PendingTrackingEdit,
    val before: TrackingValues,
    val revision: Long,
    val sentAt: Long = 0,
    val undone: Boolean = false,
    val discarded: Boolean = false,
    val noEffect: Boolean = false,
)

data class TrackingConfirmation(val before: TrackingValues, val after: TrackingValues)

@Serializable
data class LegacyTrackingRequest(val id: String, val trackId: Long, val progress: Double)

/** Provider values stay in their original units. No score/status conversion belongs in retry policy. */
object TrackingQueuePolicy {
    const val MAX_ATTEMPTS = 6
    const val MAX_PASS = 48
    const val MAX_HISTORY = 128
    const val MAX_DELAY = 6 * 60 * 60 * 1000L
    const val MAX_RETRY_AFTER = 7 * 24 * 60 * 60 * 1000L

    /** An older manual intent must not silently replace a conflicting change made on another device. */
    fun prepare(edit: PendingTrackingEdit, before: TrackingValues, flight: PendingTrackingEdit?): PendingTrackingEdit {
        if (edit.origin !in setOf(TrackingEditOrigin.MANUAL, TrackingEditOrigin.LEGACY)) return edit
        val fields = edit.patch.fields.intersect(TrackingPatch.REMOTE_FIELDS)
        return edit.copy(
            expectedRemote = edit.expectedRemote ?: edit.base ?: before,
            expectedFields = fields,
            alternativeExpectedRemote = edit.alternativeExpectedRemote
                ?: flight?.takeIf { it.sameIdentity(edit) }?.patch?.values,
        )
    }

    fun acceptsAutomatic(pending: PendingTrackingEdit?, incoming: PendingTrackingEdit): Boolean =
        pending == null ||
            pending.sameIdentity(incoming) &&
            TrackingField.STATUS !in pending.patch.fields &&
            (pending.automaticProgress || TrackingField.PROGRESS !in pending.patch.fields)

    /** Provider side effects must not override an explicit status or date chosen by the user. */
    fun automaticTransitions(edit: PendingTrackingEdit) = edit.automaticProgress &&
        edit.patch.fields.none { it == TrackingField.STATUS || it == TrackingField.START || it == TrackingField.FINISH }

    fun ready(edit: PendingTrackingEdit, now: Long) =
        edit.delivery in setOf(TrackingDelivery.WAITING, TrackingDelivery.UNCERTAIN) && edit.notBefore <= now

    fun retryable(problem: TrackingQueueProblem) = problem in setOf(
        TrackingQueueProblem.NETWORK,
        TrackingQueueProblem.RATE_LIMIT,
        TrackingQueueProblem.SERVICE,
    )

    fun failed(
        edit: PendingTrackingEdit,
        problem: TrackingQueueProblem,
        now: Long,
        retryAfter: Long? = null,
    ): PendingTrackingEdit {
        val exhausted = edit.attempts >= MAX_ATTEMPTS && retryable(problem)
        val automatic = retryable(problem) && !exhausted
        val delay = maxOf(
            (30_000L * (1L shl (edit.attempts - 1).coerceIn(0, 8))).coerceAtMost(MAX_DELAY),
            retryAfter?.coerceIn(0, MAX_RETRY_AFTER) ?: 0,
        )
        return edit.copy(
            failed = true,
            delivery = if (automatic) TrackingDelivery.UNCERTAIN else TrackingDelivery.BLOCKED,
            problem = if (exhausted) TrackingQueueProblem.EXHAUSTED else problem,
            notBefore = if (automatic) now + delay else 0,
        )
    }

    /** Revision ownership, not equality alone, prevents Undo from reverting a later A -> B -> A edit. */
    fun undoPatch(receipt: TrackingEditReceipt, owners: Map<TrackingField, Long>): TrackingPatch = TrackingPatch(
        receipt.before,
        if (receipt.undone ||
            receipt.discarded ||
            receipt.noEffect
        ) {
            emptySet()
        } else {
            receipt.edit.patch.fields.filterTo(mutableSetOf()) {
                owners[it] == receipt.revision
            }
        },
    )

    fun sameField(a: TrackingValues, b: TrackingValues, field: TrackingField) =
        TrackingPatch(a, setOf(field)).applyTo(b) == b
}
