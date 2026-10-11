package it.lorelore.nyanime.cloud.sync

import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncSession
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncTransferDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import nyanime.telegram.TelegramException
import nyanime.telegram.TelegramFailure
import nyanime.telegram.sync.SyncBatchSignature
import nyanime.telegram.sync.SyncCipher
import nyanime.telegram.sync.SyncDelivery
import nyanime.telegram.sync.SyncEnvelope
import nyanime.telegram.sync.SyncSignedBatch
import nyanime.telegram.sync.syncJson
import kotlin.coroutines.coroutineContext

internal enum class SyncMaintenancePhase { WAITING, VERIFYING, CLEANING, CURRENT, COMPATIBILITY, RETRY }
internal data class SyncMaintenanceStatus(
    val phase: SyncMaintenancePhase = SyncMaintenancePhase.WAITING,
    val removed: Long = 0,
    val lastCompleted: Long = 0,
)

/** A bounded cooperative state machine. Every deletion has two independently verified causal proofs. */
internal class CloudSyncMaintenance(
    private val session: CloudSyncSession,
    private val index: SyncMaintenanceIndex,
    private val now: () -> Long,
    private val allowed: () -> Boolean,
) {
    var status = SyncMaintenanceStatus()
        private set

    /** Fixed protocol codes/class names only; no messages, content, account identifiers or keys. */
    var lastFailureCode: String? = null
        private set

    suspend fun step(idleBatch: Boolean = false) {
        if (!allowed()) return
        try {
            if (now() < (index.value("retry")?.toLongOrNull() ?: 0)) return
            if (!idleBatch && now() < (index.value("next")?.toLongOrNull() ?: 0)) return
            // Persist before network I/O, so process death cannot produce a tight maintenance loop.
            index.put("next", (now() + 30).toString())
            lastFailureCode = null
            guard()
            val keys = session.keys
            var plan = index.plan()
            if (plan == null ||
                plan.control != keys.membership.control ||
                now() < plan.preparedAt ||
                (plan.verifiedAt > 0 && now() - plan.verifiedAt >= SyncMaintenancePolicy.PROOF_LIFETIME_SECONDS)
            ) {
                session.transport.verifyArchive()
                val proofs = session.store.messagesOfKind("commit", limit = 2).map { id ->
                    guard()
                    val delivery = session.transport.maintenanceMessage(id)?.delivery ?: error("Checkpoint missing")
                    MaintenanceProof(delivery, requireNotNull(session.checkpoints.verifiedCommit(delivery, keys)))
                }
                if (proofs.size < 2) {
                    index.put("next", (now() + 6 * 3600).toString())
                    publish(SyncMaintenancePhase.WAITING)
                    return
                }
                require(proofs.map { it.commit.id }.distinct().size == 2)
                val anchors = keys.membership.devices.keys.mapNotNull { id ->
                    session.store.state("maintenance-capability/${keys.epoch}/$id")?.toLongOrNull()
                }.distinct()
                val compatible = anchors.size == keys.membership.devices.size
                plan = MaintenancePlan(
                    keys.membership.control,
                    now(),
                    proofs,
                    anchors,
                    compatible,
                    before = proofs.minOf { it.delivery.receipt.messageId },
                    candidate = plan?.takeIf { it.control == keys.membership.control }?.candidate?.let {
                        if (it.deleting) it else it.copy(part = 0)
                    },
                )
                index.reset(plan)
            }
            if (plan.complete) {
                index.put("next", (plan.verifiedAt + SyncMaintenancePolicy.PROOF_LIFETIME_SECONDS).toString())
                publish(
                    if (plan.removeCheckpoints) SyncMaintenancePhase.CURRENT else SyncMaintenancePhase.COMPATIBILITY,
                )
                return
            }
            // Author capabilities are signed and scoped to the current membership epoch. Retain
            // their complete checkpoints as anchors so a new phone can establish the same proof.
            if (plan.checkedAnchors < plan.anchors.size) {
                guard()
                val id = plan.anchors[plan.checkedAnchors]
                val delivery = requireNotNull(session.transport.maintenanceMessage(id)).delivery
                val commit = requireNotNull(session.checkpoints.verifiedCommit(delivery, keys))
                require(commit.epoch == keys.epoch && commit.maintenanceVersion >= SyncMaintenancePolicy.VERSION)
                require(commit.author in keys.membership.devices)
                index.save(plan.copy(checkedAnchors = plan.checkedAnchors + 1))
                publish(SyncMaintenancePhase.VERIFYING)
                return
            }
            val next = plan.proofs.indexOfFirst { it.part < it.commit.parts.size }
            if (next >= 0) {
                guard()
                val proof = plan.proofs[next]
                val part = proof.commit.parts[proof.part]
                val delivery = requireNotNull(session.transport.maintenanceMessage(part.messageId)).delivery
                val chunk = session.checkpoints.verifiedChunk(delivery, part, proof.commit, keys)
                val advanced = plan.copy(
                    proofs = plan.proofs.toMutableList().also {
                        it[next] = proof.copy(part = proof.part + 1)
                    },
                )
                index.addPart(proof.commit.id, chunk.changes, advanced)
                publish(SyncMaintenancePhase.VERIFYING)
                return
            }
            guard()
            if (plan.verifiedAt == 0L) {
                plan = plan.copy(verifiedAt = now())
                index.save(plan)
            }
            if (plan.candidate != null) {
                advanceCandidate(plan)
                return
            }
            publish(SyncMaintenancePhase.CLEANING)
            val page = session.transport.maintenancePage(
                plan.before,
                plan.proofs.minOf { it.commit.throughMessage },
                now() - SyncMaintenancePolicy.MIN_AGE_SECONDS,
            )
            // One candidate per pass, not twenty document downloads in one sync tick.
            val id = page.ids.firstOrNull()
            if (id == null) {
                index.save(plan.copy(before = page.next, complete = page.complete))
                if (page.complete) index.put("completed", now().toString())
                return
            }
            guard()
            val remote = session.transport.maintenanceMessage(id)
            if (remote != null &&
                SyncMaintenancePolicy.eligible(
                    id,
                    remote.delivery.receipt.serverDate,
                    now(),
                    plan.proofs.minOf { it.commit.throughMessage },
                    remote.pinned,
                )
            ) {
                val delivery = remote.delivery
                when (delivery.packet.kind) {
                    "data" -> if (coveredData(plan, delivery)) {
                        guard()
                        val removed = session.transport.maintenanceDelete(listOf(delivery))
                        index.advance(plan.copy(before = id), removed)
                        return
                    }
                    "commit" -> if (plan.removeCheckpoints && id !in plan.anchors) {
                        val commit = requireNotNull(session.checkpoints.verifiedCommit(delivery, keys))
                        // Young parts and explicitly pinned parts protect the whole checkpoint.
                        if (commit.parts.all { it.messageId < id }) {
                            index.save(plan.copy(candidate = MaintenanceCandidate(delivery, commit)))
                            return
                        }
                    }
                }
            }
            index.save(plan.copy(before = id))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: CloudSyncTransferDeferred) {
            publish(SyncMaintenancePhase.WAITING)
        } catch (error: TelegramException) {
            lastFailureCode = "${error.failure.name}/${error.code}/${error.diagnostic}"
            val wait = if (error.failure ==
                TelegramFailure.RATE_LIMIT
            ) {
                error.retryAfterSeconds.coerceAtLeast(60)
            } else {
                300
            }
            runCatching {
                index.put("next", (now() + wait).toString())
                index.put("retry", (now() + wait).toString())
            }
            status = status.copy(phase = SyncMaintenancePhase.RETRY)
        } catch (error: Exception) {
            lastFailureCode = error.javaClass.simpleName
            // A rejected proof must not turn successful synchronization into an error or erase history.
            runCatching {
                index.put("next", (now() + 15 * 60).toString())
                index.put("retry", (now() + 15 * 60).toString())
            }
            status = status.copy(phase = SyncMaintenancePhase.RETRY)
        }
    }

    private fun coveredData(plan: MaintenancePlan, delivery: SyncDelivery): Boolean {
        val keys = session.keys
        val envelope = syncJson.decodeFromString<SyncEnvelope>(delivery.packet.payload)
        require(envelope.id == delivery.packet.id && envelope.space == keys.space)
        val key = keys.key(envelope.epoch)
        try {
            val signed = syncJson.decodeFromString<SyncSignedBatch>(
                SyncCipher.decrypt(keys.account, keys.space, key, envelope).toString(Charsets.UTF_8),
            )
            val members = keys.history.getValue(envelope.epoch)
            val batch = SyncBatchSignature.verify(signed, keys.account, envelope.epoch, members)
            require(batch.id == delivery.packet.id && batch.space == keys.space)
            if (keys.membershipAt(delivery.receipt.messageId).devices[batch.device] != members.devices[batch.device]) {
                return false
            }
            return index.covers(plan, batch.changes)
        } finally {
            key.fill(0)
        }
    }

    private suspend fun advanceCandidate(plan: MaintenancePlan) {
        val candidate = requireNotNull(plan.candidate)
        guard()
        // If the commit disappeared after another cleaner, the kept proofs still stand. A
        // locally persisted candidate retains the signed part list and resumes orphan cleanup.
        if (!candidate.deleting && candidate.part < candidate.commit.parts.size) {
            val part = candidate.commit.parts[candidate.part]
            val remote = session.transport.maintenanceMessage(part.messageId)
            if (remote == null ||
                !SyncMaintenancePolicy.eligible(
                    part.messageId,
                    remote.delivery.receipt.serverDate,
                    now(),
                    plan.proofs.minOf { it.commit.throughMessage },
                    remote.pinned,
                )
            ) {
                skip(plan)
                return
            }
            val chunk = session.checkpoints.verifiedChunk(remote.delivery, part, candidate.commit, session.keys)
            if (!index.covers(plan, chunk.changes)) {
                skip(plan)
                return
            }
            index.save(plan.copy(candidate = candidate.copy(part = candidate.part + 1)))
            return
        }
        if (!candidate.deleting) {
            guard()
            session.transport.enableCheckpointMaintenance()
            guard()
            val removed = session.transport.maintenanceDelete(listOf(candidate.delivery))
            index.advance(plan.copy(candidate = candidate.copy(deleting = true)), removed)
            return
        }
        if (candidate.deletedParts < candidate.commit.parts.size) {
            val part = candidate.commit.parts[candidate.deletedParts]
            val remote = session.transport.maintenanceMessage(part.messageId)
            val removed = if (remote != null) {
                // Recheck the hash, encryption and causal coverage after interruptions.
                val chunk = session.checkpoints.verifiedChunk(remote.delivery, part, candidate.commit, session.keys)
                require(index.covers(plan, chunk.changes))
                guard()
                session.transport.maintenanceDelete(listOf(remote.delivery))
            } else {
                0
            }
            index.advance(plan.copy(candidate = candidate.copy(deletedParts = candidate.deletedParts + 1)), removed)
        } else {
            skip(plan)
        }
    }

    private fun skip(plan: MaintenancePlan) {
        index.save(plan.copy(before = requireNotNull(plan.candidate).delivery.receipt.messageId, candidate = null))
    }

    private suspend fun guard() {
        coroutineContext.ensureActive()
        if (!allowed()) throw CloudSyncTransferDeferred()
        require(session.keys.contains(session.device.public))
    }

    private fun publish(phase: SyncMaintenancePhase) {
        status = SyncMaintenanceStatus(
            phase,
            index.value("removed")?.toLongOrNull() ?: 0,
            index.value("completed")?.toLongOrNull() ?: 0,
        )
    }
}
