package eu.kanade.tachiyomi.data.watch

import eu.kanade.tachiyomi.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class WatchShortRequest(val id: String, val name: String)
data class WatchShortState(
    val code: String = "",
    val waiting: Boolean = false,
    val relayCount: Int = 0,
    val requests: List<WatchShortRequest> = emptyList(),
    val message: String = "",
)

/** Eight digits identify a temporary rendezvous, never the room's encryption key. */
class WatchShortRooms(
    private val scope: CoroutineScope,
    private val text: RoomText,
    private val onInvite: (String, String) -> Unit,
    private val transportFactory: (WatchInvite, WatchIdentity) -> WatchTransport = { invite, identity ->
        NostrWatchTransport(invite, identity)
    },
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val mutableState = MutableStateFlow(WatchShortState())
    val state = mutableState.asStateFlow()
    private var transport: WatchTransport? = null
    private var identity: WatchIdentity? = null
    private var fullInvite = ""
    private var displayName = ""
    private var generation = 0L
    private var sequence = 0L
    private var retry: Job? = null
    private val refused = mutableSetOf<String>()
    private val approved = mutableSetOf<String>()

    fun host(invite: String, name: String) {
        close()
        WatchInvite.parse(invite, System.currentTimeMillis())
        fullInvite = invite
        displayName = name.trim().take(32)
        val code = "%08d".format(SecureRandom().nextInt(100_000_000))
        start(code, false)
        val token = generation
        retry = scope.launch {
            delay(10 * 60_000L)
            if (token == generation && fullInvite.isNotBlank()) host(invite, name)
        }
    }

    fun join(code: String, name: String) {
        close()
        require(code.matches(Regex("[0-9]{8}"))) { text(R.string.room_code_digits_error) }
        displayName = name.trim().take(32).ifBlank { text(R.string.room_viewer) }
        start(code, true)
        val token = generation
        retry = scope.launch {
            while (token == generation && mutableState.value.waiting) {
                if (mutableState.value.relayCount > 0) send("join-request", name = displayName)
                delay(5_000)
            }
        }
    }

    fun approve(id: String) {
        if (fullInvite.isBlank() || mutableState.value.requests.none { it.id == id }) return
        approved.add(id)
        mutableState.value = mutableState.value.copy(requests = mutableState.value.requests.filterNot { it.id == id })
        grant(id)
    }

    fun reject(id: String) {
        refused.add(id)
        mutableState.value = mutableState.value.copy(requests = mutableState.value.requests.filterNot { it.id == id })
    }

    private fun start(code: String, joining: Boolean) {
        val sideIdentity = WatchIdentity()
        val sideInvite = rendezvous(code)
        identity = sideIdentity
        mutableState.value = WatchShortState(
            code = code,
            waiting = joining,
            message = if (joining) text(R.string.room_find_room) else text(R.string.room_prepare_code),
        )
        val network = transportFactory(sideInvite, sideIdentity)
        transport = network
        val token = ++generation
        network.start(
            onMessage = { sender, message -> scope.launch { if (token == generation) receive(sender, message) } },
            onConnection = { count ->
                scope.launch {
                    if (token == generation) {
                        mutableState.value = mutableState.value.copy(
                            relayCount = count,
                            message = when (network.relayFailure()) {
                                WatchRelayFailure.Rejected -> text(R.string.room_invite_rejected)
                                WatchRelayFailure.RateLimited -> text(R.string.room_relay_busy)
                                WatchRelayFailure.None -> if (count > 0) {
                                    if (joining) {
                                        text(R.string.room_waiting_approval)
                                    } else {
                                        text(R.string.room_code_ready)
                                    }
                                } else {
                                    text(R.string.room_connect_invites)
                                }
                            },
                        )
                    }
                }
            },
        )
    }

    private fun receive(sender: String, message: WatchMessage) {
        val current = mutableState.value
        if (fullInvite.isNotBlank() && message.command == "join-request" && sender !in refused) {
            if (sender in approved) {
                grant(sender)
                return
            }
            if (current.requests.any { it.id == sender } || current.requests.size >= 3) return
            mutableState.value = current.copy(
                requests = current.requests +
                    WatchShortRequest(sender, message.name.ifBlank { text(R.string.room_friend) }.take(32)),
            )
        } else if (current.waiting && message.command == "join-accept" && message.target == identity?.publicKey) {
            val unlocked = runCatching { decrypt(message.invitation, sender, current.code) }.getOrNull() ?: return
            if (runCatching { WatchInvite.parse(unlocked, System.currentTimeMillis()) }.isFailure) return
            val name = displayName
            close()
            onInvite(unlocked, name)
        }
    }

    private fun grant(peer: String) {
        val current = mutableState.value
        val mine = identity ?: return
        val nonce = watchRandom(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(mine.sharedKey(peer, current.code), "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(aad(current.code, mine.publicKey, peer))
        val wrapped = watchBase64(nonce + cipher.doFinal(fullInvite.toByteArray()))
        send("join-accept", target = peer, invitation = wrapped)
    }

    private fun decrypt(wrapped: String, sender: String, code: String): String {
        val mine = requireNotNull(identity)
        val bytes = Base64.getUrlDecoder().decode(wrapped)
        require(bytes.size in 29..5000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(mine.sharedKey(sender, code), "AES"),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
        )
        cipher.updateAAD(aad(code, sender, mine.publicKey))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
    }

    private fun send(command: String, name: String = "", target: String = "", invitation: String = "") {
        transport?.send(
            WatchMessage(
                type = WatchMessageType.Status,
                sequence = ++sequence,
                at = now(),
                command = command,
                name = name,
                target = target,
                invitation = invitation,
                coordinationVersion = 2,
            ),
        )
    }

    fun close() {
        ++generation
        retry?.cancel()
        retry = null
        transport?.close()
        transport = null
        identity?.clear()
        identity = null
        fullInvite = ""
        displayName = ""
        sequence = 0
        refused.clear()
        approved.clear()
        mutableState.value = WatchShortState()
    }

    companion object {
        fun normalizeInput(input: String): String {
            val shortLink = Regex("nyanime://watch/v1#[0-9]{8}(?![0-9])").find(input)?.value
            return when {
                shortLink != null -> WatchInvite.codeFromLink(shortLink, System.currentTimeMillis())
                "NY1." in input || "nyanime://watch/" in input -> input.trim().take(5000)
                else -> input.filter(Char::isDigit).take(8)
            }
        }

        fun link(code: String): String {
            require(code.matches(Regex("[0-9]{8}")))
            return "nyanime://watch/v1#$code"
        }

        fun shareText(code: String, text: RoomText): String = text(R.string.room_share_invite, code, link(code))

        private fun rendezvous(
            code: String,
        ) = WatchInvite(
            secret = watchHash(("nyanime-short-topic-v1:" + code).toByteArray())
                .copyOf(16).watchHex(),
            owner = "0".repeat(24),
            expires = System.currentTimeMillis() + 600_000L,
            relays = WatchInvite.defaultRelays,
        )
        private fun aad(code: String, sender: String, receiver: String) =
            "nyanime-short-grant-v1:$code:$sender:$receiver".toByteArray()
    }
}
