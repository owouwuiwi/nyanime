@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.community

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

@Serializable
internal data class DevicePlayback(val device: String, val ref: SyncReference, val position: Long, val playing: Boolean)

/** Explicit, expiring handoff. Routine progress replication never invokes this adapter. */
internal class DeviceHandoff(
    private val deviceId: () -> String,
    private val sendAction: (PrivateAction) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(manager: CommunityManager) : this(manager::deviceId, { manager.sendDeviceAction(it) })
    interface Player {
        fun snapshot(): DevicePlayback?
        fun pause(): Boolean
        fun resume(position: Long)
        fun message(text: String)
    }
    private var player: Player? = null // Accessed only from Main.
    private var request = ""
    private var deadline = 0L
    private var selected = ""
    private var fallback = 0L
    private val completed = LinkedHashMap<String, DevicePlayback>()
    private val peers = mutableMapOf<String, Pair<DevicePlayback, Long>>()
    private var lastPresence = 0L

    suspend fun attach(adapter: Player) {
        request = ""
        val snapshot = withContext(Dispatchers.Main.immediate) {
            adapter.snapshot()?.also { player = adapter }
        } ?: return
        if (!snapshot.playing) return
        if (peers.values.none { (sample, expiry) ->
                sample.playing &&
                    sample.ref == snapshot.ref &&
                    expiry > now()
            }
        ) {
            return
        }
        val paused = withContext(Dispatchers.Main.immediate) { adapter.pause() }
        if (!paused) return
        request = UUID.randomUUID().toString()
        deadline = now() + 3_500
        selected = ""
        fallback = snapshot.position
        withContext(Dispatchers.Main.immediate) { adapter.message("Cerco la ripresa sugli altri dispositivi…") }
        send("device.offer", snapshot)
    }

    fun detach(adapter: Player) {
        if (player === adapter) player = null
    }

    /** Turning sync off must not retain an activity or resume an old request after re-enabling. */
    suspend fun disconnect() {
        val waiting = request.isNotEmpty()
        request = ""
        peers.clear()
        completed.clear()
        withContext(Dispatchers.Main.immediate) {
            if (waiting) player?.resume(fallback)
            player = null
        }
    }
    private fun send(type: String, playback: DevicePlayback, target: String = "") {
        sendAction(
            PrivateAction(
                type = type,
                request = request,
                peer = target,
                body = communityJson.encodeToString(playback),
                expires = deadline,
            ),
        )
    }

    suspend fun receive(action: PrivateAction) {
        if (action.expires !in now()..now() + 20_000 ||
            action.request.isBlank()
        ) {
            return
        }
        val remote = communityJson.decodeFromString<DevicePlayback>(action.body)
        if (remote.device == deviceId() || !remote.ref.valid() || remote.position !in 0..604_800_000) return
        if (action.type == "device.presence") {
            peers[remote.device] = remote to action.expires
            return
        }
        val local = withContext(Dispatchers.Main.immediate) { player?.snapshot() } ?: return
        if (local.ref != remote.ref) return
        when (action.type) {
            "device.offer" -> if (local.playing || request.isNotEmpty() && remote.device > local.device) {
                // Concurrent Play converges to one device without pausing both indefinitely.
                if (request.isNotEmpty()) request = ""
                sendAction(
                    action.copy(
                        type = "device.ready",
                        peer = remote.device,
                        body = communityJson.encodeToString(local),
                    ),
                )
            }
            "device.ready" -> if (action.peer == deviceId() &&
                action.request == request &&
                selected.isEmpty()
            ) {
                selected = remote.device
                send("device.take", local, remote.device)
            }
            "device.take" -> if (action.peer == deviceId()) {
                completed[action.request]?.let { previous ->
                    sendAction(
                        action.copy(
                            type = "device.ack",
                            peer = remote.device,
                            body = communityJson.encodeToString(previous),
                        ),
                    )
                    return
                }
                val paused = withContext(Dispatchers.Main.immediate) { player?.pause() == true }
                if (paused) {
                    completed[action.request] = local
                    while (completed.size > 64) completed.remove(completed.keys.first())
                    sendAction(
                        action.copy(
                            type = "device.ack",
                            peer = remote.device,
                            body = communityJson.encodeToString(local),
                        ),
                    )
                    withContext(Dispatchers.Main.immediate) {
                        player?.message("Riproduzione trasferita sull’altro dispositivo")
                    }
                }
            }
            "device.ack" -> if (action.peer == deviceId() &&
                action.request == request &&
                remote.device == selected
            ) {
                request = ""
                withContext(Dispatchers.Main.immediate) {
                    player?.resume(remote.position)
                    player?.message("Riprendi da dove eri rimasto")
                }
            }
        }
    }

    suspend fun tick() {
        val now = now()
        peers.entries.removeAll { it.value.second < now }
        if (now - lastPresence >= 5_000) {
            lastPresence = now
            val snapshot = withContext(Dispatchers.Main.immediate) { player?.snapshot() }
            if (snapshot !=
                null
            ) {
                sendAction(
                    PrivateAction(
                        type = "device.presence",
                        request = deviceId(),
                        body = communityJson.encodeToString(snapshot),
                        expires =
                        now + 15_000,
                    ),
                )
            }
        }
        if (request.isNotEmpty() && now() > deadline) {
            request = ""
            withContext(Dispatchers.Main.immediate) {
                player?.resume(fallback)
                player?.message("Ripresa dal progresso disponibile su questo dispositivo")
            }
        }
    }
}
