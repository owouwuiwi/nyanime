package eu.kanade.tachiyomi.data.reading

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Host-authoritative choices; pages remain personal. No Android, source or player dependencies. */
class ReadingSessionController(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val hostClock: () -> Long?,
    private val send: (ReadingSessionEnvelope, String) -> Unit,
    private val selected: (ReadingSelection?) -> Unit,
) {
    private val mutable = MutableStateFlow(ReadingSessionState())
    val state = mutable.asStateFlow()
    private var context = ReadingSessionContext()
    private var revision = 0L
    private var request = 0L
    private var pending: ReadingSessionEnvelope? = null
    private var pendingSince = 0L
    private var pendingSent = -2000L
    private var lastSnapshot = -2000L
    private var lastReceipt: ReadingSessionEnvelope? = null
    private var lastReceiptSent = -2000L
    private var ticker: Job? = null
    private val received = mutableMapOf<String, Long>()
    private val receipts = mutableMapOf<String, Pair<Long, Boolean>>()

    fun roomChanged(value: ReadingSessionContext) {
        if (!value.active ||
            context.roomId != value.roomId ||
            context.localId != value.localId ||
            context.ownerId != value.ownerId
        ) {
            ticker?.cancel()
            ticker = null
            revision = 0
            request = 0
            pending = null
            lastReceipt = null
            received.clear()
            receipts.clear()
            mutable.value = ReadingSessionState()
        }
        context = value
        if (!value.active) return
        val compatible = value.members.filterValues { it >= 1 }.keys
        mutable.value = state.value.copy(
            supported = value.host || value.ownerId in compatible,
            legacyMembers = value.members.keys - compatible - value.localId,
            // Admission still gates every command. Keep grants while a member reconnects.
            permissions = state.value.permissions,
            openings = state.value.openings.filterKeys { it in value.members },
            proposals = state.value.proposals.filter { it.participant in value.members },
        )
        received.keys.retainAll(value.members.keys)
        receipts.keys.retainAll(value.members.keys)
        if (ticker == null) {
            lastSnapshot = -2000
            ticker = scope.launch {
                while (context.active) {
                    tick()
                    delay(250)
                }
            }
        }
    }

    fun canChoose(): Boolean = context.host || context.localId in state.value.permissions

    fun snapshot(): ReadingSessionEnvelope = ReadingSessionEnvelope(
        kind = ReadingSessionKind.Snapshot,
        revision = revision,
        selection = state.value.selection,
        permissions = state.value.permissions,
    )

    fun restore(value: ReadingSessionEnvelope) {
        if (!context.host || !value.valid() || value.kind != ReadingSessionKind.Snapshot) return
        revision = value.revision
        mutable.value = state.value.copy(selection = value.selection, permissions = value.permissions)
        selected(value.selection)
        publish()
    }

    /** Called once on chapter entry, never on page changes or opening a title's detail. */
    fun choose(chapter: ReadingChapter): Boolean {
        if (!context.active || !state.value.supported || !chapter.valid() || state.value.personal) return false
        if (state.value.selection?.chapter?.key == chapter.key) return true
        if (context.host) {
            accept(chapter)
            publish()
            return true
        }
        return if (canChoose()) command(ReadingSessionKind.Choose, chapter) else false
    }

    fun propose(chapter: ReadingChapter): Boolean = if (context.host) {
        choose(chapter)
    } else {
        command(ReadingSessionKind.Propose, chapter)
    }

    private fun command(kind: ReadingSessionKind, chapter: ReadingChapter): Boolean {
        if (!context.active || !state.value.supported || !chapter.valid() || pending != null) return false
        val clock = hostClock() ?: return false
        if (clock !in 0..Long.MAX_VALUE - 8000) return false
        // The host clock survives a guest app restart, unlike a counter reset to one.
        request = maxOf(request + 1, context.requestFloor + 1, clock + 1)
        pending = ReadingSessionEnvelope(
            kind = kind,
            chapter = chapter,
            revision = revision,
            request = request,
            expiresAt = clock + 8000,
        )
        pendingSince = now()
        pendingSent = now()
        mutable.value = state.value.copy(pending = true, commandFailed = false)
        send(pending!!, context.ownerId)
        return true
    }

    fun grant(participant: String, permitted: Boolean) {
        if (!context.host || participant == context.localId || context.members[participant] != 1) return
        val permissions = if (permitted) {
            (state.value.permissions + participant).toList().takeLast(7).toSet()
        } else {
            state.value.permissions -
                participant
        }
        if (permissions == state.value.permissions) return
        revision++
        mutable.value = state.value.copy(permissions = permissions)
        publish()
    }

    fun acceptProposal(proposal: ReadingProposal) {
        if (!context.host || proposal !in state.value.proposals) return
        mutable.value = state.value.copy(proposals = state.value.proposals - proposal)
        accept(proposal.chapter)
        publish()
    }

    fun dismissProposal(proposal: ReadingProposal) {
        if (context.host) mutable.value = state.value.copy(proposals = state.value.proposals - proposal)
    }

    fun personal(value: Boolean) {
        mutable.value = state.value.copy(personal = value)
        receipt(if (value) ReadingOpening.Personal else ReadingOpening.Received)
    }

    fun receipt(opening: ReadingOpening) {
        val choice = state.value.selection ?: return
        mutable.value = state.value.copy(openings = state.value.openings + (context.localId to opening))
        if (!context.host) {
            lastReceipt = ReadingSessionEnvelope(
                kind = ReadingSessionKind.Receipt,
                revision = choice.revision,
                opening = opening,
            )
            lastReceiptSent = now()
            send(lastReceipt!!, context.ownerId)
        } else {
            lastSnapshot = -2000
        }
    }

    /** Called when the host explicitly selects a different medium. */
    fun exitReading() {
        if (!context.host || state.value.selection == null) return
        revision++
        mutable.value = state.value.copy(selection = null, openings = emptyMap())
        selected(null)
        publish()
    }

    fun receive(sender: String, value: ReadingSessionEnvelope) {
        if (!context.active || !value.valid() || sender == context.localId || context.members[sender] != 1) return
        if (value.kind == ReadingSessionKind.Snapshot) {
            if (context.host || sender != context.ownerId || value.revision < revision) return
            if (value.revision == revision && value.selection != state.value.selection) return
            revision = value.revision
            val previous = state.value.selection
            mutable.value = state.value.copy(selection = value.selection, permissions = value.permissions)
            if (value.selection == previous) {
                val local = state.value.openings[context.localId]
                mutable.value = state.value.copy(
                    openings = value.openings +
                        listOfNotNull(local?.let { context.localId to it }).toMap(),
                )
                if (value.openings[context.localId] == lastReceipt?.opening &&
                    value.selection?.revision == lastReceipt?.revision
                ) {
                    lastReceipt = null
                }
            }
            if (pending?.request == value.acknowledgement) {
                pending = null
                mutable.value = state.value.copy(pending = false, commandFailed = value.rejected)
            }
            if (value.selection != previous) {
                mutable.value = state.value.copy(openings = emptyMap())
                receipt(if (state.value.personal) ReadingOpening.Personal else ReadingOpening.Received)
                selected(value.selection)
            }
            return
        }
        if (!context.host) return
        when (value.kind) {
            ReadingSessionKind.Choose, ReadingSessionKind.Propose -> {
                if (value.request <= (received[sender] ?: 0)) {
                    publish(sender)
                    return
                }
                received[sender] = value.request
                val authorized = value.kind == ReadingSessionKind.Propose || sender in state.value.permissions
                val fresh = value.revision == revision && value.expiresAt - now() in 0..8000
                receipts[sender] = value.request to !(authorized && fresh)
                if (authorized && fresh) {
                    if (value.kind == ReadingSessionKind.Choose) {
                        accept(value.chapter!!)
                    } else {
                        val proposal = ReadingProposal(sender, value.request, value.chapter!!)
                        mutable.value = state.value.copy(
                            proposals = (
                                state.value.proposals.filterNot {
                                    it.participant == sender
                                } +
                                    proposal
                                ).take(7),
                        )
                    }
                }
                publish(sender)
                publish()
            }
            ReadingSessionKind.Receipt -> if (value.revision == state.value.selection?.revision) {
                mutable.value = state.value.copy(openings = state.value.openings + (sender to value.opening!!))
                lastSnapshot = -2000
            }
            else -> Unit
        }
    }

    private fun accept(chapter: ReadingChapter) {
        val selection = ReadingSelection(++revision, chapter)
        mutable.value = state.value.copy(selection = selection, openings = emptyMap())
        selected(selection)
    }

    private fun publish(target: String? = null) {
        if (!context.host || !context.connected) return
        val recipients = if (target != null) listOf(target) else context.members.filterValues { it == 1 }.keys
        recipients.filter { it != context.localId }.forEach { id ->
            val receipt = receipts[id]
            send(
                ReadingSessionEnvelope(
                    kind = ReadingSessionKind.Snapshot,
                    revision = revision,
                    selection = state.value.selection,
                    permissions = state.value.permissions,
                    acknowledgement = receipt?.first ?: 0,
                    rejected = receipt?.second ?: false,
                    openings = state.value.openings,
                ),
                id,
            )
        }
        lastSnapshot = now()
    }

    private fun tick() {
        if (context.host && now() - lastSnapshot >= 2000) publish()
        lastReceipt?.let {
            if (!context.host && context.connected && now() - lastReceiptSent >= 2000) {
                lastReceiptSent = now()
                send(it, context.ownerId)
            }
        }
        pending?.let {
            if (now() - pendingSince >= 8000) {
                pending = null
                mutable.value = state.value.copy(pending = false, commandFailed = true)
            } else if (context.connected && now() - pendingSent >= 1000) {
                pendingSent = now()
                send(it, context.ownerId)
            }
        }
    }
}
