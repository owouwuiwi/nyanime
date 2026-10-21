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
internal enum class SyncMaintenanceWait { NONE, INTERVAL, PAUSED, COPIES, NETWORK, RATE_LIMIT, PROOF }
internal data class SyncMaintenanceStatus(
    val phase: SyncMaintenancePhase = SyncMaintenancePhase.WAITING,
    val removed: Long = 0,
    val lastCompleted: Long = 0,
    val active: Boolean = false,
    val wait: SyncMaintenanceWait = SyncMaintenanceWait.NONE,
    val retryAt: Long = 0,
    val verifiedParts: Int = 0,
    val totalParts: Int = 0,
    val checkedAnchors: Int = 0,
    val totalAnchors: Int = 0,
    val compatibleDevices: Int = 0,
    val totalDevices: Int = 0,
    val examined: Long = 0,
    val protected: Long = 0,
    val young: Long = 0,
    val uncovered: Long = 0,
    val removedInPass: Long = 0,
    val indexedChanges: Long = 0,
    val lastActivity: Long = 0,
)

/** A bounded cooperative state machine. Every deletion has two independently verified causal proofs. */
internal class CloudSyncMaintenance(
    private val session: CloudSyncSession,
    private val index: SyncMaintenanceIndex,
    private val now: () -> Long,
    private val allowed: () -> Boolean,
) {
    var status = snapshot()
        private set

    var onChanged: () -> Unit = {}

    /** Fixed protocol codes/class names only; no messages, content, account identifiers or keys. */
    var lastFailureCode: String? = null
        private set

    suspend fun step(idleBatch: Boolean = false) {
        if (!allowed()) {
            publish(SyncMaintenancePhase.WAITING, wait = SyncMaintenanceWait.PAUSED)
            return
        }
        try {
            if (now() < (index.value("retry")?.toLongOrNull() ?: 0)) {
                publish(SyncMaintenancePhase.RETRY, wait = retryReason())
                return
            }
            val capabilityChanged = index.plan()?.let { it.capabilities.keys != capabilityAnchors().keys } == true
            if (!idleBatch && !capabilityChanged && now() < (index.value("next")?.toLongOrNull() ?: 0)) {
                publish(status.phase, wait = SyncMaintenanceWait.INTERVAL)
                return
            }
            // Persist before network I/O, so process death cannot produce a tight maintenance loop.
            index.put("next", (now() + 3).toString())
            lastFailureCode = null
            publish(status.phase, active = true)
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
                    publish(SyncMaintenancePhase.WAITING, wait = SyncMaintenanceWait.COPIES)
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
                    before = plan?.takeIf { it.control == keys.membership.control && !it.complete }?.before
                        ?: proofs.minOf { it.delivery.receipt.messageId },
                    capabilities = capabilityAnchors(),
                    lastActivity = now(),
                    candidate = plan?.takeIf { it.control == keys.membership.control }?.candidate?.let {
                        if (it.deleting) it else it.copy(part = 0)
                    },
                )
                index.reset(plan)
            }
            val capabilities = capabilityAnchors()
            if (capabilities.keys != plan.capabilities.keys) {
                val added = capabilities.filterKeys { it !in plan.capabilities }.values
                    .filterNot { it in plan.anchors }
                val wasComplete = plan.complete
                plan = plan.copy(
                    capabilities = plan.capabilities + capabilities.filterKeys { it !in plan.capabilities },
                    anchors = plan.anchors + added,
                    removeCheckpoints = capabilities.size == keys.membership.devices.size,
                    complete = false,
                    before = if (wasComplete) plan.proofs.minOf { it.delivery.receipt.messageId } else plan.before,
                    lastActivity = now(),
                )
                index.save(plan)
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
                index.save(plan.copy(checkedAnchors = plan.checkedAnchors + 1, lastActivity = now()))
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
                publish(SyncMaintenancePhase.VERIFYING, active = true)
                val advanced = plan.copy(
                    indexedChanges = Math.addExact(plan.indexedChanges, chunk.changes.size.toLong()),
                    lastActivity = now(),
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
            publish(SyncMaintenancePhase.CLEANING, active = true)
            if (plan.pendingIds.isEmpty()) {
                val page = session.transport.maintenancePage(
                    plan.before,
                    plan.proofs.minOf { it.commit.throughMessage },
                    now() - SyncMaintenancePolicy.MIN_AGE_SECONDS,
                )
                require(page.ids.distinct().size == page.ids.size && page.ids.size <= 20)
                require(page.ids.zipWithNext().all { (a, b) -> a > b })
                plan = plan.copy(
                    pendingIds = page.ids,
                    pageNext = page.next,
                    pageComplete = page.complete,
                    examined = Math.addExact(plan.examined, page.examined.toLong()),
                    protected = Math.addExact(plan.protected, page.protected.toLong()),
                    young = Math.addExact(plan.young, page.young.toLong()),
                    lastActivity = now(),
                )
                if (page.ids.isEmpty()) plan = finishPage(plan)
                index.save(plan)
                if (plan.complete) index.put("completed", now().toString())
                if (page.ids.isEmpty()) return
            }
            val id = plan.pendingIds.first()
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
                        index.advance(consumeCandidate(plan, id), removed)
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
            index.save(
                consumeCandidate(plan, id).let {
                    if (remote == null) it else it.copy(uncovered = Math.addExact(it.uncovered, 1))
                },
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: CloudSyncTransferDeferred) {
            publish(SyncMaintenancePhase.WAITING, wait = SyncMaintenanceWait.PAUSED)
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
            index.put("retry-reason", if (error.failure == TelegramFailure.RATE_LIMIT) "rate" else "network")
            publish(SyncMaintenancePhase.RETRY, wait = retryReason())
        } catch (error: Exception) {
            lastFailureCode = error.javaClass.simpleName
            // A rejected proof must not turn successful synchronization into an error or erase history.
            runCatching {
                index.put("next", (now() + 15 * 60).toString())
                index.put("retry", (now() + 15 * 60).toString())
            }
            index.put("retry-reason", "proof")
            publish(SyncMaintenancePhase.RETRY, wait = SyncMaintenanceWait.PROOF)
        } finally {
            if (status.active) publish(status.phase)
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
            index.save(plan.copy(candidate = candidate.copy(part = candidate.part + 1), lastActivity = now()))
            return
        }
        if (!candidate.deleting) {
            guard()
            session.transport.enableCheckpointMaintenance()
            guard()
            val removed = session.transport.maintenanceDelete(listOf(candidate.delivery))
            index.advance(plan.copy(candidate = candidate.copy(deleting = true), lastActivity = now()), removed)
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
            index.advance(
                plan.copy(candidate = candidate.copy(deletedParts = candidate.deletedParts + 1), lastActivity = now()),
                removed,
            )
        } else {
            skip(plan, protected = false)
        }
    }

    private fun skip(plan: MaintenancePlan, protected: Boolean = true) {
        val id = requireNotNull(plan.candidate).delivery.receipt.messageId
        index.save(
            consumeCandidate(
                plan.copy(
                    candidate = null,
                    protected = plan.protected + if (protected) 1 else 0,
                ),
                id,
            ),
        )
    }

    private suspend fun guard() {
        coroutineContext.ensureActive()
        if (!allowed()) throw CloudSyncTransferDeferred()
        require(session.keys.contains(session.device.public))
    }

    private fun capabilityAnchors(): Map<String, Long> = session.keys.membership.devices.keys.mapNotNull { id ->
        session.store.state("maintenance-capability/${session.keys.epoch}/$id")?.toLongOrNull()
            ?.takeIf { it > 0 }?.let { id to it }
    }.toMap()

    private fun finishPage(plan: MaintenancePlan) = plan.copy(
        before = plan.pageNext,
        complete = plan.pageComplete,
        pendingIds = emptyList(),
        pageNext = 0,
        pageComplete = false,
    )

    private fun consumeCandidate(plan: MaintenancePlan, id: Long): MaintenancePlan {
        val next = plan.copy(pendingIds = plan.pendingIds.filterNot { it == id }, lastActivity = now())
        val result = if (plan.pendingIds.isEmpty()) {
            next.copy(before = id)
        } else if (next.pendingIds.isEmpty()) {
            finishPage(next)
        } else {
            next
        }
        if (result.complete) index.put("completed", now().toString())
        return result
    }

    private fun retryReason() = when (index.value("retry-reason")) {
        "rate" -> SyncMaintenanceWait.RATE_LIMIT
        "network" -> SyncMaintenanceWait.NETWORK
        else -> SyncMaintenanceWait.PROOF
    }

    private fun snapshot(
        phase: SyncMaintenancePhase? = null,
        active: Boolean = false,
        wait: SyncMaintenanceWait = SyncMaintenanceWait.NONE,
    ): SyncMaintenanceStatus {
        val plan = index.plan()
        val inferred = when {
            plan == null -> SyncMaintenancePhase.WAITING
            plan.complete -> if (plan.removeCheckpoints) {
                SyncMaintenancePhase.CURRENT
            } else {
                SyncMaintenancePhase.COMPATIBILITY
            }
            plan.proofs.any { it.part < it.commit.parts.size } || plan.checkedAnchors < plan.anchors.size ->
                SyncMaintenancePhase.VERIFYING
            else -> SyncMaintenancePhase.CLEANING
        }
        return SyncMaintenanceStatus(
            phase = phase ?: inferred,
            removed = index.value("removed")?.toLongOrNull() ?: 0,
            lastCompleted = index.value("completed")?.toLongOrNull() ?: 0,
            active = active, wait = wait,
            retryAt = if (wait in
                setOf(SyncMaintenanceWait.RATE_LIMIT, SyncMaintenanceWait.NETWORK, SyncMaintenanceWait.PROOF)
            ) {
                index.value("retry")?.toLongOrNull() ?: 0
            } else {
                index.value("next")?.toLongOrNull() ?: 0
            },
            verifiedParts = plan?.proofs?.sumOf { it.part } ?: 0,
            totalParts = plan?.proofs?.sumOf { it.commit.parts.size } ?: 0,
            checkedAnchors = plan?.checkedAnchors ?: 0, totalAnchors = plan?.anchors?.size ?: 0,
            compatibleDevices = plan?.capabilities?.size ?: 0,
            totalDevices = session.keys.membership.devices.size,
            examined = plan?.examined ?: 0, protected = plan?.protected ?: 0,
            young = plan?.young ?: 0, uncovered = plan?.uncovered ?: 0,
            removedInPass = plan?.removedInPass ?: 0, indexedChanges = plan?.indexedChanges ?: 0,
            lastActivity = plan?.lastActivity ?: 0,
        )
    }

    private fun publish(
        phase: SyncMaintenancePhase,
        active: Boolean = false,
        wait: SyncMaintenanceWait = SyncMaintenanceWait.NONE,
    ) {
        status = snapshot(phase, active, wait)
        onChanged()
    }
}
