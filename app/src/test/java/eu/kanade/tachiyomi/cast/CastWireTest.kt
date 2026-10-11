package eu.kanade.tachiyomi.cast

import eu.kanade.tachiyomi.data.cast.CastWire
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastWireTest {
    @Test
    fun hlsRewritesVariantsSegmentsKeysAndMapsAgainstTheEffectiveResponseUrl() {
        val targets = mutableListOf<String>()
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="../key?k=1&x=2"
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="128@0"
            #EXT-X-MEDIA:TYPE=SUBTITLES,URI="/sub/list.m3u8"
            #EXTINF:6,
            segment.ts?token=a
            https://cdn.example/next.ts
        """.trimIndent()
        val rewritten = CastWire.rewriteHls(playlist, "https://example.test/path/list.m3u8".toHttpUrl()) {
            targets += it.toString()
            "http://192.168.1.3/cast/${targets.size}"
        }
        assertEquals(
            listOf(
                "https://example.test/key?k=1&x=2",
                "https://example.test/path/init.mp4",
                "https://example.test/sub/list.m3u8",
                "https://example.test/path/segment.ts?token=a",
                "https://cdn.example/next.ts",
            ),
            targets,
        )
        assertTrue(rewritten.contains("""URI="http://192.168.1.3/cast/1""""))
        assertTrue(rewritten.contains("""BYTERANGE="128@0""""))
        assertFalse(rewritten.contains("token=a"))
    }

    @Test
    fun rejectsNonHttpHlsReferencesAndUnresolvedVariables() {
        assertThrows(IllegalArgumentException::class.java) {
            CastWire.rewriteHls("#EXTM3U\nfile:///private/file", "https://example.test/".toHttpUrl()) { it.toString() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CastWire.rewriteHls("#EXTM3U\n#EXT-X-DEFINE:NAME=\"x\",VALUE=\"a\"", "https://example.test/".toHttpUrl()) {
                it.toString()
            }
        }
    }

    @Test
    fun localRangesMatchHttpSuffixOpenEndedAndClippedSemantics() {
        assertNull(CastWire.range(null, 0))
        assertEquals(CastWire.ByteRange(0, 0), CastWire.range("bytes=0-0", 10))
        assertEquals(CastWire.ByteRange(7, 9), CastWire.range("bytes=-3", 10))
        assertEquals(CastWire.ByteRange(3, 9), CastWire.range("bytes=3-", 10))
        assertEquals(CastWire.ByteRange(0, 9), CastWire.range("bytes=0-100", 10))
        assertEquals(CastWire.ByteRange(0, 9), CastWire.range("bytes=-100", 10))
        listOf("bytes=10-", "bytes=7-3", "bytes=-0", "bytes=-", "bytes=0-1,4-5").forEach {
            assertThrows(Exception::class.java) { CastWire.range(it, 10) }
        }
    }

    @Test
    fun rendererUsesAdvertisedServiceVersionsAndRejectsForeignControlHosts() {
        val location = "http://192.168.1.20:1400/device.xml".toHttpUrl()
        val xml = """
            <root><URLBase>http://192.168.1.20:1400/base/</URLBase><device>
            <friendlyName>TV &amp; salotto</friendlyName><UDN>uuid:tv1</UDN><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType>
            <controlURL>control</controlURL></service>
            </serviceList></device></root>
        """.trimIndent()
        val renderer = CastWire.renderer(xml, location)!!
        assertEquals("TV & salotto", renderer.device.name)
        assertEquals("http://192.168.1.20:1400/base/control", renderer.transportUrl.toString())
        assertEquals("urn:schemas-upnp-org:service:AVTransport:2", renderer.transportType)
        assertNull(
            CastWire.renderer(xml.replace("<controlURL>control", "<controlURL>http://other.test/control"), location),
        )
    }

    @Test
    fun soapPreservesNamespacesAndEscapesMediaUrls() {
        val request = CastWire.soap(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "SetAVTransportURI",
            mapOf("CurrentURI" to "https://example.test/?a=1&b=<2>"),
        )
        assertTrue(request.contains("a=1&amp;b=&lt;2&gt;"))
        val values = CastWire.values(
            "<s:Envelope><s:Body><u:Response><CurrentTransportState>PLAYING</CurrentTransportState></u:Response></s:Body></s:Envelope>",
        )
        assertEquals("PLAYING", values["CurrentTransportState"])
        assertThrows(IllegalArgumentException::class.java) { CastWire.values("<!DOCTYPE a><a/>") }
    }

    @Test
    fun transportClockHandlesUnknownDurationAndLongVideos() {
        assertEquals("27:03:04", CastWire.clock(97_384_000))
        assertEquals(97_384_000, CastWire.millis("27:03:04.500"))
        listOf(null, "NOT_IMPLEMENTED", "00:99:00", "-1:20:30", "999999999999:01:01").forEach {
            assertEquals(0, CastWire.millis(it))
        }
        assertEquals(
            "application/x-mpegURL",
            CastWire.mime("https://example.test/video", "application/vnd.apple.mpegurl"),
        )
    }
}
