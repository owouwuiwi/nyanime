package eu.kanade.tachiyomi.data.cast

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DlnaTransport(private val context: Context, private val localAddress: () -> String) : CastTransport {
    private val mutableDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val devices = mutableDevices.asStateFlow()
    private val renderers = ConcurrentHashMap<String, CastWire.Renderer>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    @Volatile private var socket: DatagramSocket? = null
    private var active: CastWire.Renderer? = null
    private var media: CastMedia? = null
    private var seenPlaying = false
    private var lastPosition = 0L
    private var supportsSeek = true
    private var lastVolume = 1f
    private var lastBrightness = 0.5f
    private var capabilities = DlnaCapabilities(null, null)
    private var ticks = 0

    override suspend fun discover() = coroutineScope {
        stopDiscovery()
        renderers.clear()
        mutableDevices.value = emptyList()
        val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("ultrayomi-cast-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        val pending = Semaphore(4)
        val visited = mutableSetOf<String>()
        try {
            withContext(Dispatchers.IO) {
                DatagramSocket(InetSocketAddress(InetAddress.getByName(localAddress()), 0)).use { search ->
                    socket = search
                    search.soTimeout = 300
                    val target = InetAddress.getByName("239.255.255.250")
                    val request = (
                        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
                        ).toByteArray()
                    repeat(2) { search.send(DatagramPacket(request, request.size, target, 1900)) }
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                    while (!search.isClosed && System.nanoTime() < until) {
                        currentCoroutineContext().ensureActive()
                        val packet = DatagramPacket(ByteArray(8192), 8192)
                        try {
                            search.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val location = packet.data.decodeToString(0, packet.length).lineSequence()
                            .firstOrNull { it.startsWith("location:", true) }
                            ?.substringAfter(':')?.trim()?.toHttpUrlOrNull() ?: continue
                        // Description and SOAP endpoints must belong to the device that answered SSDP.
                        if (location.host != packet.address.hostAddress ||
                            visited.size >= 64 ||
                            !visited.add(location.toString())
                        ) {
                            continue
                        }
                        launch {
                            pending.withPermit {
                                val renderer = try {
                                    client.newCall(Request.Builder().url(location).build()).execute().use { response ->
                                        check(response.isSuccessful)
                                        val bytes = response.body.source().apply { request(512 * 1024L + 1) }
                                        require(bytes.buffer.size <= 512 * 1024)
                                        CastWire.renderer(bytes.readUtf8(), location)
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    null
                                } ?: return@withPermit
                                currentCoroutineContext().ensureActive()
                                rememberRenderer(renderer)
                            }
                        }
                    }
                }
            }
        } finally {
            socket = null
            if (lock?.isHeld == true) lock.release()
        }
    }

    override fun stopDiscovery() {
        socket?.close()
        socket = null
    }

    internal fun rememberRenderer(renderer: CastWire.Renderer) {
        renderers[renderer.device.id] = renderer
        mutableDevices.value = renderers.values.map { it.device }.sortedBy { it.name }
    }

    override suspend fun load(device: CastDevice, media: CastMedia) {
        active = renderers[device.id] ?: error("La TV non è più disponibile. Cerca di nuovo.")
        this.media = media
        seenPlaying = false
        supportsSeek = media.live == null
        lastPosition = media.startMs
        ticks = 0
        capabilities = active?.renderingDescription?.let { url ->
            try {
                runInterruptible(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        check(response.isSuccessful)
                        val body = response.body.source().apply { request(512 * 1024L + 1) }
                        require(body.buffer.size <= 512 * 1024)
                        DlnaCapabilities.parse(body.readUtf8())
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        } ?: DlnaCapabilities(null, null)
        command("SetAVTransportURI", mapOf("CurrentURI" to media.url, "CurrentURIMetaData" to CastWire.didl(media)))
        command("Play", mapOf("Speed" to "1"))
        if (media.startMs > 0) {
            // Some renderers accept REL_TIME only after entering PLAYING.
            repeat(6) {
                val state = command("GetTransportInfo")["CurrentTransportState"]
                if (state == "PLAYING" || state == "PAUSED_PLAYBACK") {
                    seek(media.startMs)
                    return
                }
                delay(350)
            }
            seek(media.startMs)
        }
    }

    override suspend fun status(): CastPlayback {
        val transport = command("GetTransportInfo")
        val position = command("GetPositionInfo")
        val state = transport["CurrentTransportState"]
        val uri = position["TrackURI"]
        check(uri.isNullOrBlank() || uri == media?.url) { "La riproduzione sulla TV è cambiata" }
        if (state == "PLAYING") seenPlaying = true
        val duration = CastWire.millis(position["TrackDuration"]).takeIf { it > 0 } ?: media?.durationMs ?: 0
        val current = CastWire.millis(position["RelTime"])
        val stopped = state == "STOPPED" || state == "NO_MEDIA_PRESENT"
        // STOPPED commonly resets RelTime to zero. Keep the last reported progress.
        if (!stopped && state != "TRANSITIONING") lastPosition = current
        if (ticks++ % 5 == 0) refreshLevels()
        return CastPlayback(
            positionMs = lastPosition,
            durationMs = duration,
            paused = state != "PLAYING",
            buffering = state == "TRANSITIONING",
            finished = stopped && seenPlaying && duration > 0 && lastPosition >= duration - 5000,
            canSeek = supportsSeek,
            canSetVolume = capabilities.volume != null,
            volume = lastVolume,
            canSetBrightness = capabilities.brightness != null,
            brightness = lastBrightness,
        )
    }

    override suspend fun pause(paused: Boolean) {
        command(if (paused) "Pause" else "Play", if (paused) emptyMap() else mapOf("Speed" to "1"))
    }

    override suspend fun seek(positionMs: Long) {
        try {
            command("Seek", mapOf("Unit" to "REL_TIME", "Target" to CastWire.clock(positionMs)))
            lastPosition = positionMs
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            supportsSeek = false
            throw e
        }
    }

    override suspend fun volume(value: Float) {
        val control = capabilities.volume ?: error("La TV non espone il controllo del volume")
        command(
            "SetVolume",
            mapOf("Channel" to "Master", "DesiredVolume" to control.fromFraction(value).toString()),
            rendering = true,
        )
        lastVolume = control.toFraction(control.fromFraction(value))
    }

    override suspend fun brightness(value: Float) {
        val control = capabilities.brightness ?: error("La TV non espone il controllo della luminosità")
        command("SetBrightness", mapOf("DesiredBrightness" to control.fromFraction(value).toString()), rendering = true)
        lastBrightness = control.toFraction(control.fromFraction(value))
    }

    private suspend fun refreshLevels() {
        capabilities.volume?.let { control ->
            try {
                val value = command("GetVolume", mapOf("Channel" to "Master"), rendering = true)["CurrentVolume"]
                lastVolume = control.toFraction(requireNotNull(value?.toIntOrNull()))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                capabilities = capabilities.copy(volume = null)
            }
        }
        capabilities.brightness?.let { control ->
            try {
                val value = command("GetBrightness", rendering = true)["CurrentBrightness"]
                lastBrightness = control.toFraction(requireNotNull(value?.toIntOrNull()))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                capabilities = capabilities.copy(brightness = null)
            }
        }
    }

    override suspend fun subtitle(index: Int) {
        require(index == -1) { "Questa TV DLNA gestisce i sottotitoli dal proprio telecomando" }
    }

    override suspend fun stop() {
        try {
            if (active != null) command("Stop")
        } finally {
            active = null
            media = null
        }
    }

    private suspend fun command(
        action: String,
        values: Map<String, String> = emptyMap(),
        rendering: Boolean = false,
    ): Map<String, String> = runInterruptible(Dispatchers.IO) {
        val renderer = active ?: error("Nessuna TV collegata")
        val url = if (rendering) renderer.renderingUrl else renderer.transportUrl
        val service = if (rendering) renderer.renderingType else renderer.transportType
        require(url != null && service != null) { "Comando non supportato dalla TV" }
        val args = mapOf("InstanceID" to "0") + values
        val request = Request.Builder().url(url).header("SOAPACTION", "\"$service#$action\"")
            .post(CastWire.soap(service, action, args).toRequestBody("text/xml; charset=utf-8".toMediaType())).build()
        val call = client.newCall(request)
        if (rendering) call.timeout().timeout(2, TimeUnit.SECONDS)
        call.execute().use { response ->
            val source = response.body.source()
            source.request(512 * 1024L + 1)
            require(source.buffer.size <= 512 * 1024)
            val result = CastWire.values(source.readUtf8())
            check(response.isSuccessful && "errorCode" !in result) {
                "La TV non ha accettato il comando $action (${result["errorCode"] ?: response.code})"
            }
            result
        }
    }
}
