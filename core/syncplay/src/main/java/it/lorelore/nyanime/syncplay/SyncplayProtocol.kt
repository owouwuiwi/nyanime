package it.lorelore.nyanime.syncplay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.IDN
import java.net.URI
import java.security.MessageDigest

data class SyncplayEndpoint(val host: String, val port: Int) {
    companion object {
        fun parse(value: String): SyncplayEndpoint {
            val input = value.trim()
            require(input.length in 3..300 && input.none { it.isWhitespace() || it.isISOControl() })
            require(!input.contains('/') && !input.contains('@') && !input.contains('?') && !input.contains('#'))
            val uri = URI("syncplay://$input")
            require(uri.port in 1..65535 && uri.host != null && uri.userInfo == null)
            val host = uri.host.removeSurrounding("[", "]")
            require(host.isNotBlank())
            return SyncplayEndpoint(if (':' in host) host else IDN.toASCII(host), uri.port)
        }
    }
}

data class SyncplayOptions(
    val endpoint: SyncplayEndpoint,
    val nickname: String,
    val room: String,
    val tls: Boolean = true,
    val password: String = "",
) {
    fun validate() = apply {
        require(nickname.isNotBlank() && nickname.length <= 16 && nickname.none(Char::isISOControl))
        require(room.isNotBlank() && room.length <= 35 && room.none(Char::isISOControl))
        // Managed/operator rooms require capabilities this client does not advertise.
        require(!room.startsWith('+'))
        require(password.length <= 256)
    }
}

data class SyncplayLocal(
    val position: Double = 0.0,
    val paused: Boolean = true,
    val ready: Boolean = false,
    val file: String = "",
    val duration: Double = 0.0,
) {
    fun valid() = position.isFinite() &&
        position in 0.0..86400.0 &&
        duration.isFinite() &&
        duration in 0.0..86400.0 &&
        file.length <= 480
}

data class SyncplayRemote(val position: Double, val paused: Boolean, val seek: Boolean, val setBy: String)
data class SyncplayMember(val name: String, val file: String, val duration: Double?, val ready: Boolean?)
data class SyncplayChat(val name: String, val text: String)
enum class SyncplayPhase { Idle, Connecting, Connected, Failed }
data class SyncplayState(
    val phase: SyncplayPhase = SyncplayPhase.Idle,
    val username: String = "",
    val room: String = "",
    val serverVersion: String = "",
    val tls: Boolean = false,
    val members: List<SyncplayMember> = emptyList(),
    val messages: List<SyncplayChat> = emptyList(),
    val error: String = "",
    val chat: Boolean = false,
    val readiness: Boolean = false,
)

class SyncplayFailure(val code: String, val detail: String = "") : Exception(code)

/** Independent implementation of the official JSON-lines protocol, not the Dart player. */
class SyncplayProtocol(
    private val options: SyncplayOptions,
    private val wallSeconds: () -> Double,
    private val monotonicSeconds: () -> Double,
) {
    var state = SyncplayState(phase = SyncplayPhase.Connecting, room = options.room, tls = options.tls)
        private set
    var remote: SyncplayRemote? = null
        private set
    private var clientAck = 0
    private var serverAck: Int? = null
    private var pending = false
    private var latencyEcho: JsonElement? = null
    private var rtt = 0.0
    private val pings = linkedMapOf<Double, Double>()
    private val members = linkedMapOf<String, SyncplayMember>()

    fun hello(): JsonObject = buildJsonObject {
        put(
            "Hello",
            buildJsonObject {
                put("username", options.nickname)
                put("room", buildJsonObject { put("name", options.room) })
                put("version", "1.7.0")
                put("realversion", "1.7.0")
                if (options.password.isNotEmpty()) {
                    put(
                        "password",
                        MessageDigest.getInstance("MD5").digest(options.password.toByteArray())
                            .joinToString("") { "%02x".format(it) },
                    )
                }
                put(
                    "features",
                    buildJsonObject {
                        put("sharedPlaylists", false)
                        put("chat", true)
                        put("featureList", true)
                        put("readiness", true)
                        put("managedRooms", false)
                    },
                )
            },
        )
    }

    fun outgoing(local: SyncplayLocal, changed: Boolean = false, seek: Boolean = false): JsonObject {
        require(local.valid())
        if (changed) {
            clientAck++
            pending = true
        }
        val stamp = wallSeconds()
        pings[stamp] = monotonicSeconds()
        while (pings.size > 16) pings.remove(pings.keys.first())
        return buildJsonObject {
            put(
                "State",
                buildJsonObject {
                    if (pending || serverAck != null) {
                        put(
                            "ignoringOnTheFly",
                            buildJsonObject {
                                if (pending) put("client", clientAck)
                                serverAck?.let { put("server", it) }
                            },
                        )
                    }
                    put(
                        "ping",
                        buildJsonObject {
                            put("clientRtt", rtt)
                            put("clientLatencyCalculation", stamp)
                            latencyEcho?.let { put("latencyCalculation", it) }
                        },
                    )
                    // An observer, an unapproved video or local buffering must not change the room's clock.
                    if (changed || local.ready && local.file.isNotBlank()) {
                        put(
                            "playstate",
                            buildJsonObject {
                                put("position", local.position)
                                put("paused", local.paused)
                                if (changed) put("doSeek", seek)
                            },
                        )
                    }
                },
            )
        }.also { serverAck = null }
    }

    fun file(local: SyncplayLocal): JsonObject {
        require(local.valid())
        return buildJsonObject {
            put(
                "Set",
                buildJsonObject {
                    put(
                        "file",
                        if (local.file.isBlank()) {
                            JsonNull
                        } else {
                            buildJsonObject {
                                put("name", local.file.take(250))
                                put("duration", local.duration)
                                put("size", 0)
                            }
                        },
                    )
                },
            )
        }
    }

    fun readiness(ready: Boolean) = buildJsonObject {
        put(
            "Set",
            buildJsonObject {
                put(
                    "ready",
                    buildJsonObject {
                        put("isReady", ready)
                        put("manuallyInitiated", false)
                    },
                )
            },
        )
    }

    /** Returns a new timeline only when on-the-fly acknowledgement permits accepting it. */
    fun receive(packet: JsonObject): SyncplayRemote? {
        packet.obj("Error")?.let { throw SyncplayFailure("server", it.text("message").clean(240)) }
        packet.obj("Hello")?.let { hello ->
            val name = hello.text("username").clean(40)
            val room = hello.obj("room")?.text("name").orEmpty()
            if (name.isBlank() || room != options.room) throw SyncplayFailure("protocol")
            state =
                state.copy(
                    phase = SyncplayPhase.Connected,
                    username = name,
                    serverVersion = hello.text("realversion").ifBlank { hello.text("version") }.clean(40),
                    chat = hello.obj("features")?.bool("chat") == true,
                    readiness = hello.obj("features")?.bool("readiness") == true,
                )
        }
        packet.obj("List")?.let { list ->
            members.clear()
            list.obj(options.room)?.entries?.take(100)?.forEach { (name, value) ->
                (value as? JsonObject)?.let { updateMember(name, it) }
            }
        }
        packet.obj("Set")?.obj("user")?.entries?.take(100)?.forEach { (name, value) ->
            (value as? JsonObject)?.let { user ->
                val room = user.obj("room")?.text("name")
                if (user.obj("event")?.bool("left") == true || (room != null && room != options.room)) {
                    members.remove(name)
                } else if (room == options.room || name in members) {
                    updateMember(name, user)
                }
            }
        }
        packet.obj("Set")?.obj("ready")?.let { ready ->
            val name = ready.text("username")
            members[name]?.let { members[name] = it.copy(ready = ready.bool("isReady")) }
        }
        packet.obj("Chat")?.let { chat ->
            val message = SyncplayChat(chat.text("username").clean(40), chat.text("message").clean(2000))
            if (message.text.isNotBlank()) state = state.copy(messages = (state.messages + message).takeLast(80))
        }
        state = state.copy(members = members.values.sortedBy { it.name.lowercase() })
        val input = packet.obj("State") ?: return null
        input.obj("ping")?.let { ping ->
            latencyEcho =
                ping["latencyCalculation"]?.takeIf { (it as? JsonPrimitive)?.doubleOrNull?.isFinite() == true }
            ping.number("clientLatencyCalculation")?.let { stamp ->
                pings.remove(stamp)?.let { sent ->
                    val sample = monotonicSeconds() - sent
                    if (sample in 0.0..10.0) rtt = if (rtt == 0.0) sample else rtt * .85 + sample * .15
                }
            }
        }
        input.obj("ignoringOnTheFly")?.let { ignoring ->
            ignoring.integer("server")?.takeIf { it >= 0 }?.let { serverAck = it }
            if (ignoring.integer("client") == clientAck || serverAck != null) pending = false
        }
        if (pending || state.phase != SyncplayPhase.Connected) return null
        val play = input.obj("playstate") ?: return null
        val position = play.number("position")?.takeIf { it in 0.0..86400.0 } ?: throw SyncplayFailure("protocol")
        val paused = play.bool("paused") ?: throw SyncplayFailure("protocol")
        return SyncplayRemote(
            (position + if (paused) 0.0 else (rtt / 2).coerceAtMost(1.5)).coerceAtMost(86400.0),
            paused,
            play.bool("doSeek") == true,
            play.text("setBy").clean(40),
        ).also { remote = it }
    }

    private fun updateMember(name: String, value: JsonObject) {
        if (name.isBlank() || name.length > 40 || name !in members && members.size >= 100) return
        val old = members[name] ?: SyncplayMember(name.clean(40), "", null, null)
        val file = value.obj("file")
        val removed = value["file"] == JsonNull
        members[name] = old.copy(
            file = if (removed) "" else file?.text("name")?.clean(480) ?: old.file,
            duration = if (removed) null else file?.number("duration")?.takeIf { it in 0.0..86400.0 } ?: old.duration,
            ready = value.bool("isReady") ?: old.ready,
        )
    }

    companion object {
        val json = Json { isLenient = false }
        fun parse(line: String): JsonObject {
            if (line.toByteArray().size > 65536) throw SyncplayFailure("protocol")
            var depth = 0
            var quoted = false
            var escaped = false
            line.forEach { character ->
                if (escaped) {
                    escaped = false
                } else if (quoted && character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    quoted = !quoted
                } else if (!quoted) {
                    if (character == '{' || character == '[') depth++
                    if (character == '}' || character == ']') depth--
                    if (depth !in 0..24) throw SyncplayFailure("protocol")
                }
            }
            return json.parseToJsonElement(line).jsonObject
        }
        fun list() = buildJsonObject { put("List", JsonNull) }
        fun chat(text: String): JsonObject {
            require(text.isNotBlank() && text.length <= 150 && text.none(Char::isISOControl))
            return buildJsonObject { put("Chat", text) }
        }
    }
}

internal fun JsonObject.obj(key: String) = this[key] as? JsonObject
internal fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()
internal fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
internal fun JsonObject.integer(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
private fun String.clean(limit: Int) = filterNot(Char::isISOControl).take(limit)
