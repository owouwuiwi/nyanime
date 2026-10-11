package eu.kanade.tachiyomi.data.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import nyanime.tv.api.TvChannel
import nyanime.tv.api.TvLiveAdjustment
import nyanime.tv.api.TvLiveObservation
import nyanime.tv.api.TvLivePolicy
import nyanime.tv.api.TvLiveReference
import nyanime.tv.api.TvRoomSelection

@Serializable
enum class TvRoomKind { Selection, Reference, Ping, Pong, Exit }

/** Carried by the existing encrypted room transport. No second room, socket or native player. */
@Serializable
data class TvRoomEnvelope(
    val version: Int = 1,
    val kind: TvRoomKind,
    val selection: TvRoomSelection? = null,
    val reference: TvLiveReference? = null,
    val nonce: Long = 0,
    val hostUtc: Long = 0,
    val revision: Long = 0,
) {
    fun valid(): Boolean = version == 1 &&
        nonce >= 0 &&
        hostUtc >= 0 &&
        revision >= 0 &&
        when (kind) {
            TvRoomKind.Selection -> selection?.valid() == true && reference == null && nonce == 0L && hostUtc == 0L
            TvRoomKind.Reference -> reference?.valid() == true && selection == null && nonce == 0L && hostUtc == 0L
            TvRoomKind.Ping -> nonce > 0 && selection == null && reference == null && hostUtc == 0L
            TvRoomKind.Pong -> nonce > 0 && hostUtc > 0 && selection == null && reference == null
            TvRoomKind.Exit -> revision > 0 && selection == null && reference == null && nonce == 0L && hostUtc == 0L
        }
}

data class TvRoomContext(
    val active: Boolean = false,
    val host: Boolean = false,
    val localId: String = "",
    val ownerId: String = "",
    val members: Map<String, Int> = emptyMap(),
    val connected: Boolean = false,
    val roomId: String = "",
)

data class TvRoomState(
    val active: Boolean = false,
    val selection: TvRoomSelection? = null,
    val supported: Boolean = false,
    val adjustment: TvLiveAdjustment = TvLiveAdjustment.Unverified,
    val sourceMissing: Boolean = false,
)

/** Owner-ordered channel changes, independent pauses and optional verified UTC alignment. */
class TvRoomController(
    private val scope: CoroutineScope,
    private val monotonic: () -> Long,
    private val wall: () -> Long,
    private val send: (TvRoomEnvelope, String) -> Unit,
    private val openChannel: (TvRoomSelection) -> Unit,
    private val observe: () -> TvLiveObservation?,
    private val adjust: (TvLiveAdjustment) -> Unit,
    private val exited: () -> Unit,
) {
    private val mutable = MutableStateFlow(TvRoomState())
    val state = mutable.asStateFlow()
    private var room = TvRoomContext()
    private var closed = false
    private var ticker: Job? = null
    private var revision = 0L
    private var acceptedRevision = 0L
    private var exitRevision = 0L
    private var reference: TvLiveReference? = null
    private var lastSelection = -5_000L
    private var lastReference = -5_000L
    private var lastPing = -5_000L
    private var nonce = 0L
    private data class Probe(val monotonic: Long, val wall: Long)
    private data class ClockSample(val rtt: Long, val offset: Long, val at: Long)
    private val probes = linkedMapOf<Long, Probe>()
    private val clocks = ArrayDeque<ClockSample>()
    private val rates = mutableMapOf<String, Long>()

    fun roomChanged(value: TvRoomContext) {
        if (closed) return
        val changedIdentity =
            room.active &&
                (value.roomId != room.roomId || value.localId != room.localId || value.ownerId != room.ownerId)
        if (!value.active || changedIdentity) {
            ticker?.cancel()
            ticker = null
            revision = 0
            acceptedRevision = 0
            exitRevision = 0
            reference = null
            probes.clear()
            clocks.clear()
            rates.clear()
            mutable.value = TvRoomState()
            adjust(TvLiveAdjustment.Unverified)
        }
        room = value
        if (!value.active) return
        val compatible = value.members.filterKeys { it != value.localId }.values.all { it >= 1 }
        mutable.value = state.value.copy(active = true, supported = compatible)
        if (!compatible && room.host && state.value.selection != null) exit()
        if (!value.connected) adjust(TvLiveAdjustment.Unverified)
        rates.keys.retainAll(value.members.keys)
        if (ticker == null) {
            lastSelection = -5_000
            lastReference = -5_000
            lastPing = -5_000
            ticker = scope.launch {
                while (room.active) {
                    tick()
                    delay(500)
                }
            }
        }
    }

    fun select(channel: TvChannel): Boolean {
        if (closed || !room.active || !room.host || !state.value.supported) return false
        val selected = TvRoomSelection(channel.ref, channel.name, ++revision)
        if (!selected.valid()) return false
        accept(selected)
        publishSelection(selected)
        return true
    }

    fun restore(value: TvRoomSelection) {
        if (closed || !room.active || !room.host || !value.valid()) return
        revision = maxOf(revision, value.revision)
        val next = value.copy(revision = ++revision)
        accept(next)
        publishSelection(next)
    }

    fun suspendLive() {
        if (closed) return
        reference = null
        mutable.value = state.value.copy(selection = null, adjustment = TvLiveAdjustment.Unverified)
        adjust(TvLiveAdjustment.Unverified)
    }

    fun sourceMissing(value: Boolean) {
        if (!closed) mutable.value = state.value.copy(sourceMissing = value)
    }

    fun exit(): Boolean {
        if (closed || !room.active || !room.host || state.value.selection == null) return false
        val next = ++revision
        publish(TvRoomEnvelope(kind = TvRoomKind.Exit, revision = next))
        clearSelection(next)
        return true
    }

    /** The room adapter passes only authenticated members and validates the outer sequence lane. */
    fun receive(sender: String, value: TvRoomEnvelope) {
        if (closed || !room.active || sender == room.localId || !value.valid()) return
        if (sender != room.ownerId && sender !in room.members) return
        when (value.kind) {
            TvRoomKind.Selection -> if (sender == room.ownerId) accept(requireNotNull(value.selection))
            TvRoomKind.Reference -> if (sender == room.ownerId) {
                val next = requireNotNull(value.reference)
                if (next.selectionRevision == acceptedRevision &&
                    (reference == null || next.sentUtc > requireNotNull(reference).sentUtc)
                ) {
                    reference = next
                }
            }
            TvRoomKind.Ping -> if (room.host) {
                val time = monotonic()
                if (time - (rates[sender] ?: -5_000L) < 1_000) return
                rates[sender] = time
                send(TvRoomEnvelope(kind = TvRoomKind.Pong, nonce = value.nonce, hostUtc = wall()), sender)
            }
            TvRoomKind.Pong -> if (sender == room.ownerId && !room.host) {
                val probe = probes.remove(value.nonce) ?: return
                val time = monotonic()
                val rtt = time - probe.monotonic
                // Changes to the phone clock during a probe invalidate it; monotonic RTT remains bounded.
                if (rtt !in 0..8_000 || kotlin.math.abs(wall() - (probe.wall + rtt)) > 1_000) return
                clocks.addLast(ClockSample(rtt, probe.wall + rtt / 2 - value.hostUtc, time))
                while (clocks.size > 8) clocks.removeFirst()
            }
            TvRoomKind.Exit -> if (sender == room.ownerId &&
                value.revision > acceptedRevision
            ) {
                clearSelection(value.revision)
            }
        }
    }

    private fun accept(value: TvRoomSelection) {
        if (value.revision <= acceptedRevision) return
        acceptedRevision = value.revision
        revision = maxOf(revision, value.revision)
        exitRevision = 0
        reference = null
        mutable.value =
            state.value.copy(selection = value, adjustment = TvLiveAdjustment.Unverified, sourceMissing = false)
        openChannel(value)
    }

    private fun clearSelection(next: Long) {
        if (next <= acceptedRevision) return
        acceptedRevision = next
        revision = maxOf(revision, next)
        exitRevision = next
        reference = null
        mutable.value =
            state.value.copy(selection = null, adjustment = TvLiveAdjustment.Unverified, sourceMissing = false)
        adjust(TvLiveAdjustment.Unverified)
        exited()
    }

    /** Explicit targets keep unsupported members out of the extension message lane. */
    private fun publish(value: TvRoomEnvelope) {
        if (!room.connected) return
        room.members.filter { (id, version) -> id != room.localId && version >= 1 }.keys.forEach { send(value, it) }
    }

    private fun publishSelection(value: TvRoomSelection) {
        if (!room.connected) return
        publish(TvRoomEnvelope(kind = TvRoomKind.Selection, selection = value))
        lastSelection = monotonic()
    }

    private fun tick() {
        if (!room.connected) return
        val time = monotonic()
        val selection = state.value.selection
        if (selection == null) {
            // Repeat the latest mode change so an interrupted send cannot strand a guest in live mode.
            if (room.host && exitRevision > 0 && time - lastSelection >= 2_000) {
                publish(TvRoomEnvelope(kind = TvRoomKind.Exit, revision = exitRevision))
                lastSelection = time
            }
            return
        }
        val local = observe()
        if (room.host) {
            if (time - lastSelection >= 2_000) publishSelection(selection)
            if (local != null && !local.paused && !local.buffering && time - lastReference >= 2_000) {
                local.broadcastUtc()?.let { utc ->
                    publish(
                        TvRoomEnvelope(
                            kind = TvRoomKind.Reference,
                            reference = TvLiveReference(selection.revision, utc, wall()),
                        ),
                    )
                    lastReference = time
                }
            }
            return
        }
        if (time - lastPing >= 5_000) {
            val id = ++nonce
            probes[id] = Probe(time, wall())
            while (probes.size > 4) probes.remove(probes.keys.first())
            send(TvRoomEnvelope(kind = TvRoomKind.Ping, nonce = id), room.ownerId)
            lastPing = time
        }
        while (clocks.firstOrNull()?.let { time - it.at > 30_000 } == true) clocks.removeFirst()
        val offset = clocks.minByOrNull { it.rtt }?.offset
        val adjustment =
            local?.let { TvLivePolicy.adjustment(it, reference, selection.revision, wall(), offset) }
                ?: TvLiveAdjustment.Unverified
        if (adjustment != state.value.adjustment) {
            mutable.value = state.value.copy(adjustment = adjustment)
            adjust(adjustment)
        }
    }

    fun close() {
        if (closed) return
        roomChanged(TvRoomContext())
        closed = true
    }
}
