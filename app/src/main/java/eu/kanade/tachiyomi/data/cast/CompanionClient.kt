package eu.kanade.tachiyomi.data.cast

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CompanionPairing(val name: String, val code: String = "")
internal data class CompanionReceiverInfo(
    val device: CastDevice,
    val endpoint: HttpUrl,
    val receiverId: String,
    val volume: Boolean,
    val brightness: Boolean,
    val subtitles: Boolean,
    val formats: Set<String>,
    val live: Boolean = false,
)

internal class CompanionClient(
    val receiver: CompanionReceiverInfo,
    private val http: OkHttpClient = networkClient(),
) {
    private var sessionId = ""
    private var keys: CompanionCrypto.Keys? = null
    private var sequence = 0L
    var approved: Boolean = false
        private set

    suspend fun pair(name: String, onCode: (String) -> Unit) {
        val crypto = CompanionCrypto()
        val first = request(
            http,
            receiver.endpoint,
            "pair",
            buildJsonObject {
                put("version", 1)
                put("receiverId", receiver.receiverId)
                put("name", name)
                put("commitment", crypto.commitment)
            },
        )
        sessionId = first.getValue("sessionId").jsonPrimitive.content
        require(sessionId.matches(Regex("[a-f0-9]{32}")))
        require(first.getValue("receiverId").jsonPrimitive.content == receiver.receiverId)
        val revealed = request(
            http,
            receiver.endpoint,
            "reveal",
            buildJsonObject {
                put("sessionId", sessionId)
                put("publicKey", crypto.publicKey)
                put("nonce", crypto.nonce)
            },
        )
        require(revealed.getValue("sessionId").jsonPrimitive.content == sessionId)
        require(revealed.getValue("receiverId").jsonPrimitive.content == receiver.receiverId)
        keys = crypto.complete(
            receiver.receiverId,
            sessionId,
            name,
            revealed.getValue("publicKey").jsonPrimitive.content,
            revealed.getValue("nonce").jsonPrimitive.content,
            first.getValue("commitment").jsonPrimitive.content,
        )
        onCode(keys!!.code)
        withTimeout(120_000) {
            while (true) {
                val result = command(buildJsonObject { put("type", "pairStatus") })
                if (result.getValue("approved").jsonPrimitive.boolean) {
                    approved = true
                    return@withTimeout
                }
                delay(500)
            }
        }
    }

    /** Commands are serialized by the owner; a network retry reuses the exact encrypted envelope. */
    suspend fun command(body: JsonObject): JsonObject {
        val activeKeys = keys ?: error("Collega prima l'app sulla TV")
        val seq = ++sequence
        val envelope = CompanionCrypto.seal(activeKeys.client, sessionId, "client", seq, body)
        val reply = try {
            request(http, receiver.endpoint, "session/$sessionId", envelope)
        } catch (e: IOException) {
            delay(150)
            request(http, receiver.endpoint, "session/$sessionId", envelope)
        }
        return CompanionCrypto.open(activeKeys.server, sessionId, "server", seq, reply).also {
            check(it["ok"]?.jsonPrimitive?.boolean == true) {
                it["error"]?.jsonPrimitive?.content?.take(300) ?: "La TV non ha eseguito il comando"
            }
        }
    }

    suspend fun cancelPair() {
        if (keys != null) command(buildJsonObject { put("type", "cancelPair") })
        clear()
    }
    fun clear() {
        approved = false
        keys?.clear()
        keys = null
        sessionId = ""
    }

    companion object {
        const val PATH = "/nyanime-cast/v1/"
        const val DISCOVERY = "urn:nyanime:device:CompanionReceiver:1"
        const val PORT = 38473
        fun networkClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()

        fun isLanIpv4(host: String): Boolean {
            if (!host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return false
            val parts = host.split('.').map { it.toInt() }
            if (parts.any { it > 255 } || parts[3] == 255) return false
            return parts[0] == 10 ||
                parts[0] == 172 &&
                parts[1] in 16..31 ||
                parts[0] == 192 &&
                parts[1] == 168 ||
                parts[0] == 169 &&
                parts[1] == 254
        }
        fun address(input: String): HttpUrl {
            val value = input.trim().removePrefix("http://")
            require(value.matches(Regex("[0-9.]+(:[0-9]{1,5})?"))) { "Inserisci l'indirizzo mostrato dall'app TV" }
            val host = value.substringBefore(':')
            require(isLanIpv4(host)) { "Usa l'indirizzo locale della TV" }
            val port = value.substringAfter(':', PORT.toString()).toInt()
            require(port in 1..65535)
            return "http://$host:$port$PATH".toHttpUrlOrNull() ?: error("Indirizzo non valido")
        }
        suspend fun describe(endpoint: HttpUrl, client: OkHttpClient = networkClient()): CompanionReceiverInfo {
            val result = request(client, endpoint, "info", null)
            require(
                result["protocol"]?.jsonPrimitive?.content == "nyanime-cast" &&
                    result["version"]?.jsonPrimitive?.content == "1",
            ) { "Aggiorna l'app Nyanime sulla TV" }
            val id = result.getValue("id").jsonPrimitive.content
            val name = result.getValue("name").jsonPrimitive.content
            require(
                id.matches(Regex("[a-f0-9]{32}")) &&
                    name.isNotBlank() &&
                    name.length <= 100 &&
                    name.none { it.code < 32 || it.code == 127 },
            )
            val capabilities = result.getValue("capabilities").jsonObject
            fun supported(key: String) = capabilities[key]?.jsonPrimitive?.booleanOrNull == true
            val formats = capabilities.getValue("formats").jsonArray.map { it.jsonPrimitive.content }.toSet()
            require(formats.size in 1..16 && formats.all { it.length <= 32 })
            return CompanionReceiverInfo(
                CastDevice("companion:$id", name, CastProtocol.COMPANION),
                endpoint,
                id,
                supported("volume"),
                supported("brightness"),
                supported("subtitles"),
                formats,
                supported("live"),
            )
        }
        private suspend fun request(
            client: OkHttpClient,
            endpoint: HttpUrl,
            path: String,
            body: JsonObject?,
        ): JsonObject =
            withContext(Dispatchers.IO) {
                val url = endpoint.newBuilder().encodedPath(PATH + path).build()
                val builder = Request.Builder().url(url)
                if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
                client.newCall(builder.build()).await().use { response ->
                    check(response.isSuccessful) {
                        when (response.code) {
                            409 -> "La TV è già collegata o attende una conferma. Controlla lo schermo."
                            429 -> "Troppi tentativi. Attendi un minuto e riprova."
                            401, 403 -> "Collegamento terminato. Abbina di nuovo la TV."
                            else -> "L'app TV non risponde correttamente."
                        }
                    }
                    val source = response.body.source()
                    source.request(96 * 1024L + 1)
                    require(source.buffer.size <= 96 * 1024)
                    Json.parseToJsonElement(source.readUtf8()).jsonObject
                }
            }
    }
}
