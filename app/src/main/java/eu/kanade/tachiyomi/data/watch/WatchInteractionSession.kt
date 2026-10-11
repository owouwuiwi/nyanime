package eu.kanade.tachiyomi.data.watch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nyanime.rooms.voice.RoomVoiceSignal
import java.util.UUID

/** Room-only, bounded, host-authoritative messaging. It never touches a player or persistent profile. */
class WatchInteractionSession(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val send: (String, WatchInteractionSealed) -> Unit,
) {
    private data class Pending(
        val target: String,
        val payload: WatchInteractionPayload,
        val created: Long,
        var sent: Long = -2000,
        var tries: Int = 0,
    )
    private val mutable = MutableStateFlow(WatchInteractionState())
    val state = mutable.asStateFlow()
    private var context = WatchInteractionContext()
    private var crypto: WatchInteractionCrypto? = null
    private var ticker: Job? = null
    private val outbox = linkedMapOf<String, Pending>()
    private val failedChats = mutableSetOf<String>()
    private val received = linkedMapOf<String, WatchInteractionPayload>()
    private val recent = mutableMapOf<String, ArrayDeque<Long>>()
    private var order = 0L
    private var voiceOrder = -1L
    private val voiceOrders = mutableMapOf<String, Long>()
    private val voiceRequests = mutableMapOf<String, Long>()
    private var request = 0L
    private var desiredVoice: WatchVoicePresence? = null
    private var chatVisible = false
    private var lastReaction = -1000L
    var onVoiceSignal: (String, String) -> Unit = { _, _ -> }
    var onVoicePeers: (Map<String, WatchVoicePresence>) -> Unit = {}

    fun start(value: WatchInteractionCrypto) {
        close()
        crypto = value
    }

    fun roomChanged(value: WatchInteractionContext) {
        if (value.roomId != context.roomId ||
            value.localId != context.localId ||
            value.epoch != context.epoch ||
            !value.active
        ) {
            outbox.clear()
            failedChats.clear()
            received.clear()
            recent.clear()
            order = 0
            voiceOrder = -1
            voiceOrders.clear()
            voiceRequests.clear()
            request = 0
            desiredVoice = null
            mutable.value = WatchInteractionState()
            ticker?.cancel()
            ticker = null
        }
        val changed = value.members.keys != context.members.keys || value.supported != context.supported
        context = value
        outbox.entries.removeAll { it.value.target !in value.members }
        mutable.value = state.value.copy(
            supported = value.supported,
            connected = value.connected,
            localId = value.localId,
            voices = state.value.voices.filterKeys { it in value.members },
        )
        onVoicePeers(state.value.voices)
        if (!value.supported || crypto == null) return
        if (ticker == null) {
            ticker = scope.launch {
                while (context.active) {
                    tick()
                    delay(250)
                }
            }
        }
        if (changed) {
            if (value.host) {
                publishSnapshot()
            } else {
                queue(value.ownerId, payload(WatchInteractionKind.Hello))
            }
        }
    }

    fun sendChat(text: String): Boolean {
        val normalized = text.trim().filter { it == '\n' || it == '\t' || !it.isISOControl() }
        if (!context.supported ||
            normalized.isBlank() ||
            normalized.length > 1000 ||
            state.value.messages.count { it.delivery == WatchChatDelivery.Sending } >= 8
        ) {
            return false
        }
        val value = payload(WatchInteractionKind.Chat).copy(text = normalized)
        addMessage(value, WatchChatDelivery.Sending)
        submit(value)
        mutable.value = state.value.copy(draft = "")
        return true
    }

    fun retryChat(id: String) {
        val message =
            state.value.messages.firstOrNull { it.id == id && it.own && it.delivery == WatchChatDelivery.Failed }
                ?: return
        failedChats.remove(id)
        mutable.value = state.value.copy(
            messages = state.value.messages.map {
                if (it.id == id) it.copy(delivery = WatchChatDelivery.Sending) else it
            },
        )
        submit(payload(WatchInteractionKind.Chat).copy(id = id, text = message.text))
    }

    fun react(reaction: WatchReaction) {
        if (!context.supported || now() - lastReaction < 750) return
        lastReaction = now()
        submit(payload(WatchInteractionKind.Reaction).copy(text = reaction.name))
    }

    fun setVoice(joined: Boolean, muted: Boolean = true) {
        if (!context.supported) return
        val previous = state.value.voices[context.localId]
        val presence = WatchVoicePresence(
            joined,
            !joined || muted,
            if (!joined) {
                ""
            } else {
                previous?.session?.takeIf { previous.joined }
                    ?: UUID.randomUUID().toString().replace("-", "")
            },
        )
        if (state.value.voices[context.localId] == presence) return
        desiredVoice = presence
        updateVoice(context.localId, presence)
        outbox.entries.removeAll { it.value.payload.kind == WatchInteractionKind.Voice }
        submit(payload(WatchInteractionKind.Voice).copy(presence = presence, request = ++request))
    }

    fun voiceSignal(target: String, signal: String) {
        if (target !in context.members ||
            signal.length !in 1..9000 ||
            state.value.voices[context.localId]?.joined != true ||
            state.value.voices[target]?.joined != true
        ) {
            return
        }
        queue(target, payload(WatchInteractionKind.Signal).copy(target = target, signal = signal))
    }

    fun setDraft(value: String) {
        if (value.length <= 1000) mutable.value = state.value.copy(draft = value)
    }

    fun chatVisible(visible: Boolean) {
        chatVisible = visible
        if (visible) mutable.value = state.value.copy(unread = 0)
    }

    fun setReactions(enabled: Boolean) {
        mutable.value =
            state.value.copy(
                reactionsEnabled = enabled,
                reactions = if (enabled) state.value.reactions else emptyList(),
            )
    }

    fun setOverlay(enabled: Boolean) {
        mutable.value = state.value.copy(overlayEnabled = enabled)
    }

    fun receive(sender: String, sealed: WatchInteractionSealed) {
        if (!context.supported ||
            sender !in context.members ||
            sender == context.localId
        ) {
            return
        }
        val value = crypto?.open(sender, context.membership, sealed) ?: return
        if (value.kind == WatchInteractionKind.Receipt || value.kind == WatchInteractionKind.Rejected) {
            val pending = outbox.remove(sender + ":" + value.reference) ?: return
            if (pending.payload.kind == WatchInteractionKind.Chat && pending.payload.author == context.localId) {
                if (value.kind == WatchInteractionKind.Rejected) {
                    setDelivery(value.reference, false)
                } else if (outbox.values.none { it.payload.id == value.reference }) {
                    setDelivery(value.reference, true, value.order)
                }
            }
            return
        }
        // Only audio negotiation may travel directly between admitted peers. Text and
        // presence remain ordered by the authenticated room owner.
        if (value.kind == WatchInteractionKind.Signal) {
            if (value.author != sender || value.target != context.localId) return
            val signal = runCatching { watchJson.decodeFromString<RoomVoiceSignal>(value.signal) }.getOrNull()
                ?.takeIf { it.valid() } ?: return
            val own = state.value.voices[context.localId]
            val peer = state.value.voices[sender]
            // A roster snapshot can arrive after SDP. Do not acknowledge it prematurely:
            // the bounded outbox will retry once the peer's current voice session is known.
            if (own?.joined != true ||
                peer?.joined != true ||
                signal.senderSession != peer.session ||
                signal.targetSession != own.session
            ) {
                return
            }
        } else if (!context.host && sender != context.ownerId) {
            return
        }
        val key = sender + ":" + value.id
        received[key]?.let {
            receipt(sender, value.id, it.order)
            return
        }
        if (!allow(sender, value.kind)) return
        if (value.kind == WatchInteractionKind.Signal) {
            remember(key, value)
            receipt(sender, value.id, 0)
            onVoiceSignal(sender, value.signal)
            return
        }
        if (context.host) {
            if (value.kind == WatchInteractionKind.Hello) {
                remember(key, value)
                receipt(sender, value.id, 0)
                queue(sender, snapshot())
                return
            }
            if (value.kind !in
                setOf(
                    WatchInteractionKind.Chat,
                    WatchInteractionKind.Reaction,
                    WatchInteractionKind.Voice,
                )
            ) {
                return
            }
            if (value.kind == WatchInteractionKind.Voice) {
                if (value.request <= (voiceRequests[sender] ?: -1)) {
                    receipt(sender, value.id, 0)
                    return
                }
                voiceRequests[sender] = value.request
            }
            val accepted = value.copy(author = sender, name = context.members.getValue(sender), order = ++order)
            remember(key, accepted)
            receipt(sender, value.id, accepted.order)
            accept(accepted)
            if (accepted.kind == WatchInteractionKind.Voice) publishSnapshot() else distribute(accepted)
        } else {
            if (value.author !in context.members && value.kind != WatchInteractionKind.Snapshot) return
            remember(key, value)
            receipt(sender, value.id, value.order)
            accept(value)
        }
    }

    private fun submit(value: WatchInteractionPayload) {
        if (context.host) {
            val accepted = value.copy(order = ++order)
            accept(accepted)
            if (accepted.kind == WatchInteractionKind.Voice) publishSnapshot() else distribute(accepted)
        } else {
            queue(context.ownerId, value)
        }
    }

    private fun accept(value: WatchInteractionPayload) {
        when (value.kind) {
            WatchInteractionKind.Chat -> addMessage(
                value,
                if (context.host &&
                    value.author == context.localId
                ) {
                    WatchChatDelivery.Sending
                } else {
                    WatchChatDelivery.Sent
                },
            )
            WatchInteractionKind.Reaction -> if (state.value.reactionsEnabled) {
                val reaction = WatchReaction.entries.firstOrNull { it.name == value.text } ?: return
                mutable.value = state.value.copy(
                    reactions = (
                        state.value.reactions +
                            WatchReactionEvent(value.id, value.author, reaction, now())
                        ).takeLast(12),
                )
            }
            WatchInteractionKind.Voice -> if (value.order > maxOf(voiceOrder, voiceOrders[value.author] ?: -1)) {
                voiceOrders[value.author] = value.order
                if (value.author != context.localId || value.request >= request) {
                    if (value.author == context.localId) desiredVoice = null
                    updateVoice(value.author, value.presence)
                }
            }
            WatchInteractionKind.Snapshot -> if (value.order >= voiceOrder) {
                voiceOrder = value.order
                voiceOrders.clear()
                if (value.voices[context.localId] == desiredVoice) desiredVoice = null
                val ownPending = desiredVoice
                val voices = value.voices.filterKeys { it in context.members }.toMutableMap()
                ownPending?.let { voices[context.localId] = it }
                mutable.value = state.value.copy(voices = voices)
                onVoicePeers(voices)
            }
            WatchInteractionKind.Signal -> if (value.target == context.localId &&
                state.value.voices[context.localId]?.joined == true &&
                state.value.voices[value.author]?.joined == true
            ) {
                onVoiceSignal(value.author, value.signal)
            }
            else -> Unit
        }
    }

    private fun distribute(value: WatchInteractionPayload) {
        val targets = if (value.kind == WatchInteractionKind.Signal) {
            setOf(value.target).filter { state.value.voices[it]?.joined == true }
        } else {
            context.members.keys
        }
        targets.filter { it != context.localId }.forEach { queue(it, value) }
        if (value.kind == WatchInteractionKind.Chat &&
            value.author == context.localId &&
            outbox.values.none { it.payload.id == value.id }
        ) {
            setDelivery(value.id, true, value.order)
        }
    }

    private fun snapshot() = payload(WatchInteractionKind.Snapshot).copy(voices = state.value.voices, order = ++order)

    private fun publishSnapshot() {
        val value = snapshot()
        context.members.keys.filter { it != context.localId }.forEach { queue(it, value) }
    }

    private fun updateVoice(id: String, value: WatchVoicePresence) {
        mutable.value = state.value.copy(voices = state.value.voices + (id to value))
        onVoicePeers(state.value.voices)
    }

    private fun addMessage(value: WatchInteractionPayload, delivery: WatchChatDelivery) {
        val existing = state.value.messages.firstOrNull { it.id == value.id }
        val own = value.author == context.localId
        val message = WatchChatMessage(
            value.id,
            value.author,
            value.name,
            value.text,
            own,
            delivery,
            value.order.takeIf { it > 0 } ?: Long.MAX_VALUE,
            existing?.receivedAt ?: now(),
        )
        mutable.value = state.value.copy(
            messages = (
                state.value.messages.filterNot {
                    it.id == value.id
                } +
                    message
                ).sortedBy { it.order }.takeLast(200),
            unread = if (!own &&
                !chatVisible &&
                existing == null
            ) {
                (state.value.unread + 1).coerceAtMost(200)
            } else {
                state.value.unread
            },
        )
    }

    private fun setDelivery(id: String, success: Boolean, acceptedOrder: Long = 0) {
        failedChats.retainAll(state.value.messages.map { it.id }.toSet())
        if (!success) failedChats.add(id)
        if (success && id in failedChats) return
        failedChats.remove(id)
        mutable.value = state.value.copy(
            messages = state.value.messages.map {
                if (it.id == id) {
                    it.copy(
                        delivery = if (success) WatchChatDelivery.Sent else WatchChatDelivery.Failed,
                        order = acceptedOrder.takeIf { number -> number > 0 } ?: it.order,
                    )
                } else {
                    it
                }
            }.sortedBy { it.order },
        )
    }

    private fun queue(target: String, value: WatchInteractionPayload) {
        val key = target + ":" + value.id
        if (target !in context.members || target == context.localId) return
        if (outbox.size >= 128 && key !in outbox) {
            if (value.kind == WatchInteractionKind.Chat && value.author == context.localId) setDelivery(value.id, false)
            return
        }
        if (value.kind == WatchInteractionKind.Snapshot) {
            outbox.entries.removeAll { it.value.target == target && it.value.payload.kind == value.kind }
        }
        outbox.putIfAbsent(key, Pending(target, value, now()))
    }

    private fun receipt(target: String, reference: String, order: Long) {
        emit(target, payload(WatchInteractionKind.Receipt).copy(reference = reference, order = order))
    }

    private fun emit(target: String, value: WatchInteractionPayload) {
        if (!context.connected) return
        runCatching { crypto?.seal(target, context.membership, value)?.let { send(target, it) } }
    }

    private fun tick() {
        val time = now()
        var budget = 1
        for ((key, pending) in outbox.toMap()) {
            if (outbox[key] !== pending) continue
            if (time - pending.created >= 10_000 || pending.tries >= 4 && time - pending.sent >= 2000) {
                outbox.remove(key)
                if (pending.payload.kind == WatchInteractionKind.Chat && pending.payload.author == context.localId) {
                    setDelivery(pending.payload.id, false)
                }
            } else if (time - pending.sent >= 2000 && context.connected && budget > 0) {
                budget--
                pending.sent = time
                pending.tries++
                emit(pending.target, pending.payload)
            }
        }
        val reactions = state.value.reactions.filter { time - it.at < 3500 }
        if (reactions.size != state.value.reactions.size) mutable.value = state.value.copy(reactions = reactions)
    }

    private fun allow(sender: String, kind: WatchInteractionKind): Boolean {
        val key = sender + ":" + if (kind == WatchInteractionKind.Reaction) "reaction" else "message"
        val times = recent.getOrPut(key) { ArrayDeque() }
        val time = now()
        while (times.isNotEmpty() && time - times.first() >= 10_000) times.removeFirst()
        if (times.size >= if (kind == WatchInteractionKind.Reaction) 12 else 64) return false
        times.addLast(time)
        return true
    }

    private fun remember(key: String, value: WatchInteractionPayload) {
        received[key] = value
        while (received.size > 1024) received.remove(received.keys.first())
    }

    private fun payload(kind: WatchInteractionKind) = WatchInteractionPayload(
        id = UUID.randomUUID().toString().replace("-", ""),
        kind = kind,
        author = context.localId,
        name = context.members[context.localId].orEmpty(),
    )

    fun close() {
        ticker?.cancel()
        ticker = null
        crypto = null
        context = WatchInteractionContext()
        outbox.clear()
        failedChats.clear()
        received.clear()
        recent.clear()
        mutable.value = WatchInteractionState()
        chatVisible = false
        order = 0
        voiceOrder = -1
        voiceOrders.clear()
        voiceRequests.clear()
        request = 0
        desiredVoice = null
        onVoicePeers(emptyMap())
    }
}
