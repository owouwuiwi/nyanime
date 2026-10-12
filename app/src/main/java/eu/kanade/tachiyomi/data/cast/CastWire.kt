package eu.kanade.tachiyomi.data.cast

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Small, independently testable parts of HLS, HTTP ranges and UPnP AVTransport. */
object CastWire {
    private val quotedUri = Regex("""URI="([^"]+)"""")

    fun rewriteHls(manifest: String, base: HttpUrl, register: (HttpUrl) -> String): String {
        require(manifest.trimStart().startsWith("#EXTM3U")) { "Playlist HLS non valida" }
        require(!manifest.contains("#EXT-X-DEFINE")) { "Questa playlist HLS usa variabili non supportate dal Cast" }
        fun url(value: String): String {
            val resolved = requireNotNull(base.resolve(value)) { "Indirizzo HLS non valido" }
            require(resolved.scheme == "http" || resolved.scheme == "https")
            return register(resolved)
        }
        return manifest.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.trimStart().startsWith("#") -> quotedUri.replace(line) { match ->
                    "URI=\"" + url(match.groupValues[1]) + "\""
                }
                else -> url(line.trim())
            }
        }
    }

    data class ByteRange(val first: Long, val last: Long) {
        val length: Long get() = last - first + 1
    }

    fun range(header: String?, size: Long): ByteRange? {
        require(size >= 0)
        if (header == null) return null
        require(size > 0)
        val match = requireNotNull(Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim())) { "Range non valido" }
        val (start, end) = match.destructured
        require(start.isNotEmpty() || end.isNotEmpty())
        return if (start.isEmpty()) {
            val suffix = end.toLong()
            require(suffix > 0)
            ByteRange((size - suffix).coerceAtLeast(0), size - 1)
        } else {
            val first = start.toLong()
            val last = if (end.isEmpty()) size - 1 else end.toLong().coerceAtMost(size - 1)
            require(first in 0 until size && last >= first)
            ByteRange(first, last)
        }
    }

    fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    fun soap(service: String, action: String, args: Map<String, String>): String =
        """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body><u:$action xmlns:u="$service">
        """.trimIndent() +
            args.entries.joinToString("") { (key, value) -> "<$key>${xml(value)}</$key>" } +
            "</u:$action></s:Body></s:Envelope>"

    fun values(document: String): Map<String, String> {
        require(document.length <= 512 * 1024 && !document.contains("<!DOCTYPE", true))
        return Jsoup.parse(document, "", Parser.xmlParser()).getAllElements()
            .filter { it.children().isEmpty() }
            .associate { it.tagName().substringAfter(':') to it.text() }
    }

    data class Renderer(
        val device: CastDevice,
        val transportUrl: HttpUrl,
        val transportType: String,
        val renderingUrl: HttpUrl?,
        val renderingType: String?,
        val renderingDescription: HttpUrl?,
    )

    fun renderer(document: String, location: HttpUrl): Renderer? {
        require(document.length <= 512 * 1024 && !document.contains("<!DOCTYPE", true))
        val doc = Jsoup.parse(document, "", Parser.xmlParser())
        fun text(tag: String) = doc.getElementsByTag(tag).first()?.text().orEmpty()
        val services = doc.getElementsByTag("service")
        val transport = services.firstOrNull {
            it.getElementsByTag("serviceType").text().startsWith("urn:schemas-upnp-org:service:AVTransport:")
        } ?: return null
        val render = services.firstOrNull {
            it.getElementsByTag("serviceType").text().startsWith("urn:schemas-upnp-org:service:RenderingControl:")
        }
        val base = location.resolve(text("URLBase").ifBlank { location.toString() }) ?: location
        fun control(value: String): HttpUrl? = value.takeIf { it.isNotBlank() }?.let(base::resolve)?.takeIf {
            it.host == location.host && it.username.isEmpty() && it.password.isEmpty()
        }
        return Renderer(
            CastDevice(
                "dlna:" + text("UDN").ifBlank { location.toString() },
                text("friendlyName").ifBlank { location.host },
                CastProtocol.DLNA,
            ),
            control(transport.getElementsByTag("controlURL").text()) ?: return null,
            transport.getElementsByTag("serviceType").text(),
            render?.let { control(it.getElementsByTag("controlURL").text()) },
            render?.getElementsByTag("serviceType")?.text(),
            render?.let { control(it.getElementsByTag("SCPDURL").text()) },
        )
    }

    fun didl(media: CastMedia): String =
        """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
            xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <item id="0" parentID="-1" restricted="1"><dc:title>
        """.trimIndent() +
            xml(media.title + " — " + media.episodeName) +
            "</dc:title><upnp:class>object.item.videoItem</upnp:class>" +
            """<res protocolInfo="http-get:*:${xml(media.mimeType)}:*">""" +
            xml(media.url) +
            "</res></item></DIDL-Lite>"

    fun clock(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d:%02d".format(java.util.Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    fun millis(clock: String?): Long {
        val parts = clock?.substringBefore('.')?.split(':') ?: return 0
        if (parts.size != 3) return 0
        val values = parts.map { it.toLongOrNull() ?: return 0 }
        if (values.any { it < 0 } || values[0] > 1_000_000 || values[1] > 59 || values[2] > 59) return 0
        return ((values[0] * 60 + values[1]) * 60 + values[2]) * 1000
    }

    fun mime(url: String, reported: String? = null): String {
        val path = url.substringBefore('?').lowercase()
        val type = reported?.substringBefore(';')?.trim()?.lowercase()
        return when {
            path.endsWith(".m3u8") || type?.contains("mpegurl") == true -> "application/x-mpegURL"
            path.endsWith(".mpd") || type == "application/dash+xml" -> "application/dash+xml"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".vtt") -> "text/vtt"
            type?.startsWith("video/") == true -> type
            else -> "video/mp4"
        }
    }
}
