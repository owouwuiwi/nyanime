package it.lorelore.nyanime.cloud.sync

import nyanime.telegram.sync.SyncChange
import nyanime.telegram.sync.SyncRegister

/** No time/position heuristic can prove that a change is safely represented by a checkpoint. */
object SyncMaintenancePolicy {
    const val VERSION = 1
    const val MIN_AGE_SECONDS = 7 * 86_400L
    const val PROOF_LIFETIME_SECONDS = 86_400L
    const val BATCH_SIZE = 20

    /** Suppress only an unsent revision already causally replaced in this exact field. */
    fun superseded(change: SyncChange, frontier: SyncRegister?): Boolean = frontier != null &&
        frontier.versions.none { it.dot == change.dot } &&
        covered(change, frontier)

    fun covered(change: SyncChange, checkpoint: SyncRegister?): Boolean {
        change.validate()
        if (checkpoint == null ||
            checkpoint.versions.any { it.ref != change.ref || it.field != change.field }
        ) {
            return false
        }
        checkpoint.versions.forEach(SyncChange::validate)
        return checkpoint.merge(change) == checkpoint
    }

    fun eligible(message: Long, date: Long, now: Long, through: Long, pinned: Boolean): Boolean =
        message > 0 &&
            message <= through &&
            date > 0 &&
            now >= date &&
            now - date >= MIN_AGE_SECONDS &&
            !pinned

    /** Membership is part of the proof; a newly paired or long-offline old client blocks checkpoint removal. */
    fun canRemoveCheckpoints(members: Set<String>, capabilities: Map<String, Int>): Boolean =
        members.isNotEmpty() && members.all { (capabilities[it] ?: 0) >= VERSION }
}
