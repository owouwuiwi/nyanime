package eu.kanade.tachiyomi.data.community

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

@Serializable
internal data class PairingCode(
    val key: String,
    val nonce: String,
    val until: Long,
    val relays: List<String>,
    val purpose: String = "community",
) {
    fun encode() =
        "NYD1." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(communityJson.encodeToString(this).toByteArray())
    fun valid() =
        validKey(key) &&
            validKey(nonce) &&
            until in System.currentTimeMillis()..System.currentTimeMillis() + 180_000 &&
            relays.size in 1..5 &&
            relays.all(CommunityRelays::validRelay)
    companion object {
        fun parse(text: String): PairingCode {
            require(text.startsWith("NYD1.") && text.length <= 2000) { "Codice dispositivo non valido" }
            return communityJson.decodeFromString<PairingCode>(
                Base64.getUrlDecoder().decode(text.removePrefix("NYD1.")).decodeToString(),
            ).also {
                require(it.valid()) { "Codice scaduto. Generane uno nuovo." }
            }
        }
    }
}
internal data class PairingState(
    val code: String = "",
    val comparison: String = "",
    val confirmed: Boolean = false,
    val peerConfirmed: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
)

/** Ephemeral keys, mutual confirmation and one-use encrypted key delivery. No identity in the QR. */
internal class DevicePairing(
    private val manager: CommunityManager,
    relays: List<String>,
    private val sender: Boolean,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val temporary = CommunityIdentity()
    private var code =
        PairingCode(
            temporary.publicKey,
            randomBytes(32).hex(),
            System.currentTimeMillis() + 180_000,
            relays,
            if (manager.personalOnly) PersonalSyncPolicy.PURPOSE else "community",
        )
    private var peer = ""
    private var transport: CommunityRelays? = null
    private var outgoing = mutableListOf<NostrEvent>()
    private var delivered = false
    private var adopting = false

    @Volatile private var closed = false
    private val mutable = MutableStateFlow(PairingState(code = if (sender) "" else code.encode()))
    val state = mutable.asStateFlow()
    init {
        if (!sender) connect()
        scope.launch {
            while (true) {
                delay(1000)
                mutex.withLock {
                    if (System.currentTimeMillis() >
                        code.until
                    ) {
                        mutable.value =
                            mutable.value.copy(error = "Codice scaduto. Chiudi e genera un nuovo collegamento.")
                        transport?.close()
                        transport =
                            null
                    } else {
                        transport?.send(outgoing.toList())
                    }
                }
            }
        }
    }
    private fun connect() {
        transport = CommunityRelays(
            temporary,
            code.relays,
            {
                listOf(
                    buildJsonObject {
                        put("kinds", JsonArray(listOf(JsonPrimitive(1059))))
                        put("#p", JsonArray(listOf(JsonPrimitive(temporary.publicKey))))
                        put(
                            "since",
                            System.currentTimeMillis() / 1000 - 172_800,
                        )
                        put("limit", 20)
                    },
                )
            },
            received = { event ->
                mutex.withLock {
                    runCatching { receive(event) }.onFailure {
                        mutable.value =
                            mutable.value.copy(error = "Collegamento non valido. Genera un nuovo codice.")
                    }
                }
            },
            accepted = { id, _ -> mutex.withLock { outgoing.removeAll { it.id == id } } },
            status = { _, _ -> },
        )
    }
    fun join(text: String) {
        scope.launch {
            mutex.withLock {
                runCatching {
                    require(sender && peer.isEmpty())
                    val parsed = PairingCode.parse(text.trim())
                    require(parsed.purpose == code.purpose) { "Usa il QR di Impostazioni → I miei dispositivi." }
                    code = parsed
                    peer = code.key
                    connect()
                    send("hello", "")
                    compare()
                }.onFailure { mutable.value = mutable.value.copy(error = it.message ?: "Codice non valido") }
            }
        }
    }
    private fun compare() {
        val hash = sha256((listOf(peer, temporary.publicKey).sorted().joinToString() + code.nonce).toByteArray())
        val number =
            ((hash[0].toInt() and 255) shl 16 or ((hash[1].toInt() and 255) shl 8) or (hash[2].toInt() and 255)) %
                1_000_000
        mutable.value = mutable.value.copy(comparison = "%06d".format(java.util.Locale.ROOT, number))
    }
    fun confirm() {
        scope.launch {
            mutex.withLock {
                if (peer.isEmpty() || !code.valid() || mutable.value.finished) return@withLock
                mutable.value = mutable.value.copy(confirmed = true)
                send("confirm", "")
                deliver()
            }
        }
    }
    private fun send(type: String, body: String) {
        val action = PrivateAction(type = "pair.$type", request = code.nonce, body = body, expires = code.until)
        outgoing.add(
            GiftWrap.wrap(
                temporary,
                peer,
                GiftWrap.rumor(temporary.publicKey, 30079, communityJson.encodeToString(action)),
                expires = code.until,
            ),
        )
        transport?.send(outgoing.toList())
    }
    private suspend fun receive(event: NostrEvent) {
        if (!code.valid() || mutable.value.finished) return
        val rumor = GiftWrap.open(temporary, event)
        val action = communityJson.decodeFromString<PrivateAction>(rumor.content)
        if (rumor.kind != 30079 || action.request != code.nonce || action.expires != code.until) return
        if (!sender && peer.isEmpty() && action.type == "pair.hello") {
            peer = rumor.pubkey
            compare()
        }
        if (rumor.pubkey != peer) return
        when (action.type) {
            "pair.confirm" -> {
                mutable.value = mutable.value.copy(peerConfirmed = true)
                deliver()
            }
            "pair.secret" -> if (!sender && mutable.value.confirmed && !adopting) {
                adopting = true
                val secret = action.body.hexBytes()
                require(secret.size == 32)
                try {
                    manager.dispatch {
                        try {
                            if (closed || !code.valid()) return@dispatch
                            manager.adopt(secret)
                            scope.launch {
                                mutex.withLock {
                                    if (!closed) {
                                        send("done", "")
                                        mutable.value = mutable.value.copy(finished = true)
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            scope.launch {
                                mutex.withLock {
                                    adopting = false
                                    mutable.value =
                                        mutable.value.copy(error = "Non riesco a salvare il collegamento. Riprova.")
                                }
                            }
                            throw error
                        } finally {
                            secret.fill(0)
                        }
                    }
                } catch (
                    error: Throwable,
                ) {
                    secret.fill(0)
                    throw error
                }
            }
            "pair.done" -> if (sender) mutable.value = mutable.value.copy(finished = true)
        }
    }
    private fun deliver() {
        if (!sender || !mutable.value.confirmed || !mutable.value.peerConfirmed || delivered) return
        delivered = true
        // Secret lives only in the encrypted envelope and is cleared immediately after sealing.
        val secret = manager.pairingSecret()
        try {
            send("secret", secret.hex())
        } finally {
            secret.fill(0)
        }
    }
    override fun close() {
        closed = true
        transport?.close()
        scope.cancel()
        outgoing.clear()
        temporary.close()
    }
}
