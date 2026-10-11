package eu.kanade.tachiyomi.data.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

class CompanionTransport(private val context: Context, private val localAddress: () -> String) : CastTransport {
    private val receivers = ConcurrentHashMap<String, CompanionReceiverInfo>()
    private val mutableDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val devices = mutableDevices.asStateFlow()
    private val mutablePairing = MutableStateFlow<CompanionPairing?>(null)
    val pairing = mutablePairing.asStateFlow()
    private val pairLock = Mutex()

    @Volatile private var socket: DatagramSocket? = null
    private var paired: CompanionClient? = null
    private var active: CompanionClient? = null
    private var mediaId = ""
    private val http = CompanionClient.networkClient()

    override suspend fun discover() {
        stopDiscovery()
        receivers.keys.retainAll(listOfNotNull(active?.receiver?.device?.id, paired?.receiver?.device?.id).toSet())
        mutableDevices.value = receivers.values.map { it.device }
        val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("nyanime-companion-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        try {
            withContext(Dispatchers.IO) {
                DatagramSocket(InetSocketAddress(InetAddress.getByName(localAddress()), 0)).use { search ->
                    socket = search
                    search.soTimeout = 250
                    val text = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ${CompanionClient.DISCOVERY}\r\n\r\n"
                    val bytes = text.toByteArray()
                    repeat(2) {
                        search.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 1900))
                    }
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
                    val visited = mutableSetOf<String>()
                    while (!search.isClosed && System.nanoTime() < until) {
                        currentCoroutineContext().ensureActive()
                        val packet = DatagramPacket(ByteArray(4096), 4096)
                        try {
                            search.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val lines = packet.data.decodeToString(0, packet.length).lineSequence().toList()
                        if (lines.none { it.equals("ST: ${CompanionClient.DISCOVERY}", true) }) continue
                        val ip = packet.address.hostAddress ?: continue
                        if (!CompanionClient.isLanIpv4(ip) || visited.size >= 24) continue
                        val location =
                            lines.firstOrNull { it.startsWith("location:", true) }?.substringAfter(':')?.trim()
                                ?: continue
                        val endpoint = try {
                            val raw = location.toHttpUrlOrNull() ?: continue
                            require(
                                raw.scheme == "http" &&
                                    raw.host == ip &&
                                    raw.encodedPath == CompanionClient.PATH + "info" &&
                                    raw.username.isEmpty() &&
                                    raw.password.isEmpty() &&
                                    raw.query == null &&
                                    raw.fragment == null,
                            )
                            CompanionClient.address("$ip:${raw.port}")
                        } catch (_: Exception) {
                            continue
                        }
                        if (!visited.add(endpoint.toString())) continue
                        try {
                            remember(CompanionClient.describe(endpoint, http))
                        } catch (
                            e: Exception,
                        ) {
                            if (e is CancellationException) throw e
                        }
                    }
                }
            }
        } finally {
            socket = null
            if (lock?.isHeld == true) lock.release()
        }
    }
    private fun remember(receiver: CompanionReceiverInfo): CastDevice {
        receivers[receiver.device.id] = receiver
        mutableDevices.value = receivers.values.map { it.device }.sortedBy { it.name }
        return receiver.device
    }
    suspend fun find(
        address: String,
    ): CastDevice = remember(CompanionClient.describe(CompanionClient.address(address), http))
    override fun stopDiscovery() {
        socket?.close()
        socket = null
    }

    suspend fun pair(device: CastDevice) = pairLock.withLock {
        if (active?.receiver?.device?.id == device.id && active?.approved == true) return@withLock
        // Preflight may fail before load owns the transport; keep the TV's approved pairing reusable.
        if (paired?.receiver?.device?.id == device.id && paired?.approved == true) return@withLock
        val receiver = receivers[device.id] ?: error("Cerca di nuovo la TV")
        val candidate = CompanionClient(receiver, http)
        mutablePairing.value = CompanionPairing(device.name)
        try {
            val name = Build.MODEL.orEmpty().filter {
                it.code >= 32 && it.code != 127
            }.take(64).ifBlank { "Telefono Nyanime" }
            candidate.pair(name) { code -> mutablePairing.value = CompanionPairing(device.name, code) }
            paired?.takeIf { it !== active }?.clear()
            paired = candidate
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { withTimeout(3000) { candidate.cancelPair() } } }
            candidate.clear()
            throw e
        } finally {
            mutablePairing.value = null
        }
    }

    override suspend fun load(device: CastDevice, media: CastMedia) {
        val candidate = paired?.takeIf { it.receiver.device.id == device.id && it.approved }
            ?: active?.takeIf { it.receiver.device.id == device.id && it.approved }
            ?: error("Abbina prima la TV")
        require(media.live == null || candidate.receiver.live) {
            context.getString(eu.kanade.tachiyomi.R.string.tv_cast_unsupported)
        }
        val format = when (media.mimeType) {
            "application/vnd.apple.mpegurl", "application/x-mpegurl" -> "hls"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            else -> error("Formato non supportato dall’app TV")
        }
        require(format in candidate.receiver.formats) { "Formato non supportato dall’app TV" }
        if (active != null && active !== candidate) stop()
        active = candidate
        mediaId = UUID.randomUUID().toString().replace("-", "")
        candidate.command(
            buildJsonObject {
                put("type", "load")
                put(
                    "media",
                    buildJsonObject {
                        put("id", mediaId)
                        put("title", media.title.take(300))
                        put("episode", media.episodeName.take(300))
                        put("url", media.url)
                        put("mimeType", media.mimeType)
                        put("positionMs", media.startMs)
                        put("durationMs", media.durationMs)
                        put("live", media.live != null)
                        put(
                            "subtitles",
                            buildJsonArray {
                                media.subtitles.take(if (candidate.receiver.subtitles) 16 else 0).forEach { subtitle ->
                                    add(
                                        buildJsonObject {
                                            put("name", subtitle.name.take(100))
                                            put("url", subtitle.url)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
        )
    }

    override suspend fun status(): CastPlayback {
        val value = (active ?: error("TV non collegata")).command(buildJsonObject { put("type", "status") })
            .getValue("playback").jsonObject
        val stopped = value.getValue("stopped").jsonPrimitive.boolean
        require(stopped || value.getValue("mediaId").jsonPrimitive.content == mediaId) {
            "Il video sulla TV è cambiato"
        }
        fun time(key: String) = value.getValue(key).jsonPrimitive.double
            .also { require(it.isFinite() && it in 0.0..604800000.0) }.roundToLong()
        fun level(key: String) = value.getValue(key).jsonPrimitive.float.also { require(it.isFinite() && it in 0f..1f) }
        return CastPlayback(
            positionMs = time("positionMs"), durationMs = time("durationMs"),
            paused = value.getValue("paused").jsonPrimitive.boolean,
            buffering = value.getValue("buffering").jsonPrimitive.boolean,
            finished = value.getValue("finished").jsonPrimitive.boolean,
            canSeek = value.getValue("canSeek").jsonPrimitive.boolean,
            canSetVolume = active?.receiver?.volume == true, volume = level("volume"),
            canSetBrightness = active?.receiver?.brightness == true, brightness = level("brightness"),
            canSetSubtitles = active?.receiver?.subtitles == true,
            disconnected = stopped,
            remoteError = value["error"]?.jsonPrimitive?.contentOrNull?.takeIf {
                it.isNotBlank()
            }?.take(300),
        )
    }
    private suspend fun send(type: String, value: kotlinx.serialization.json.JsonPrimitive? = null) {
        (active ?: error("TV non collegata")).command(
            buildJsonObject {
                put("type", type)
                put("mediaId", mediaId)
                if (value != null) put("value", value)
            },
        )
    }
    override suspend fun pause(paused: Boolean) = send("pause", kotlinx.serialization.json.JsonPrimitive(paused))
    override suspend fun seek(positionMs: Long) = send("seek", kotlinx.serialization.json.JsonPrimitive(positionMs))
    override suspend fun volume(value: Float) = send("volume", kotlinx.serialization.json.JsonPrimitive(value))
    override suspend fun brightness(value: Float) = send("brightness", kotlinx.serialization.json.JsonPrimitive(value))
    override suspend fun subtitle(index: Int) = send("subtitle", kotlinx.serialization.json.JsonPrimitive(index))
    override suspend fun stop() {
        val previous = active ?: return
        try {
            if (mediaId.isNotEmpty()) send("stop")
        } finally {
            previous.clear()
            if (paired === previous) paired = null
            active = null
            mediaId = ""
        }
    }
}
