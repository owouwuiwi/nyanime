package eu.kanade.tachiyomi.cast

import android.content.Context
import eu.kanade.tachiyomi.data.cast.CastMedia
import eu.kanade.tachiyomi.data.cast.CastWire
import eu.kanade.tachiyomi.data.cast.DlnaTransport
import fi.iki.elonen.NanoHTTPD
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class DlnaTransportTest {
    @Test
    fun realSoapUsesAdvertisedServicesAndReceiverLevelsAndPreservesStoppedProgress() = runBlocking {
        val tv = TestReceiver()
        tv.start()
        val transport = DlnaTransport(mockk<Context>()) { "127.0.0.1" }
        try {
            val renderer = CastWire.renderer(tv.description, "${tv.base}/description.xml".toHttpUrl())!!
            transport.rememberRenderer(renderer)
            val media = CastMedia(
                1,
                2,
                3,
                "Title & <episode>",
                "Episode 1",
                "${tv.base}/video?a=1&b=2",
                "video/mp4",
                25_000,
                60_000,
            )
            transport.load(renderer.device, media)
            assertEquals(listOf("SetAVTransportURI", "Play", "GetTransportInfo", "Seek"), tv.actions.toList())
            assertEquals("00:00:25", tv.position)
            assertEquals(media.url, tv.uri)
            assertTrue(tv.metadata.contains("Title &amp; &lt;episode&gt;"))
            assertTrue(tv.soapHeaders.all { it.contains(":2#") })

            val playing = transport.status()
            assertFalse(playing.paused)
            assertEquals(25_000L, playing.positionMs)
            assertEquals(0.25f, playing.volume)
            assertEquals(0.5f, playing.brightness)
            assertTrue(playing.canSetBrightness)
            transport.volume(0.75f)
            transport.brightness(0.75f)
            assertEquals(160, tv.volume)
            assertEquals(160, tv.brightness)
            transport.pause(true)
            assertTrue(transport.status().paused)

            tv.state = "TRANSITIONING"
            tv.position = "00:00:00"
            assertEquals(25_000L, transport.status().positionMs)
            tv.state = "STOPPED"
            assertFalse(transport.status().finished)
            tv.state = "PLAYING"
            tv.position = "00:00:58"
            transport.status()
            tv.state = "STOPPED"
            tv.position = "00:00:00"
            val finished = transport.status()
            assertEquals(58_000L, finished.positionMs)
            assertTrue(finished.finished)
            transport.stop()
            assertEquals("Stop", tv.actions.last())
        } finally {
            tv.stop()
        }
    }

    private class TestReceiver : NanoHTTPD("127.0.0.1", 0) {
        val base get() = "http://127.0.0.1:$listeningPort"
        val actions = CopyOnWriteArrayList<String>()
        val soapHeaders = CopyOnWriteArrayList<String>()

        @Volatile var state = "STOPPED"

        @Volatile var position = "00:00:00"

        @Volatile var uri = ""
        var metadata = ""
        var volume = 60
        var brightness = 110
        val description get() = """
            <root><device><friendlyName>Test TV</friendlyName><UDN>uuid:test</UDN><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType>
            <controlURL>/av</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:2</serviceType>
            <controlURL>/rc</controlURL><SCPDURL>/rc.xml</SCPDURL></service>
            </serviceList></device></root>
        """.trimIndent()

        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/rc.xml") {
                val scpd = "<scpd><actionList>" +
                    listOf("Volume", "Brightness").joinToString("") {
                        "<action><name>Get$it</name></action><action><name>Set$it</name></action>"
                    } +
                    "</actionList><serviceStateTable>" +
                    listOf("Volume", "Brightness").joinToString("") {
                        "<stateVariable><name>$it</name><allowedValueRange><minimum>10</minimum>" +
                            "<maximum>210</maximum><step>5</step></allowedValueRange></stateVariable>"
                    } +
                    "</serviceStateTable></scpd>"
                return newFixedLengthResponse(Response.Status.OK, "text/xml", scpd)
            }
            val header = session.headers["soapaction"].orEmpty()
            val action = header.substringAfter('#').trim('"')
            soapHeaders += header
            actions += action
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val args = CastWire.values(files["postData"].orEmpty())
            val result = when (action) {
                "SetAVTransportURI" -> {
                    uri = args.getValue(
                        "CurrentURI",
                    )
                    metadata = args.getValue("CurrentURIMetaData")
                    emptyMap()
                }
                "Play" -> {
                    state = "PLAYING"
                    emptyMap()
                }
                "Pause" -> {
                    state = "PAUSED_PLAYBACK"
                    emptyMap()
                }
                "Stop" -> {
                    state = "STOPPED"
                    emptyMap()
                }
                "Seek" -> {
                    position = args.getValue("Target")
                    emptyMap()
                }
                "GetTransportInfo" -> mapOf("CurrentTransportState" to state)
                "GetPositionInfo" -> mapOf("RelTime" to position, "TrackDuration" to "00:01:00", "TrackURI" to uri)
                "GetVolume" -> mapOf("CurrentVolume" to volume.toString())
                "GetBrightness" -> mapOf("CurrentBrightness" to brightness.toString())
                "SetVolume" -> {
                    volume = args.getValue("DesiredVolume").toInt()
                    emptyMap()
                }
                "SetBrightness" -> {
                    brightness = args.getValue("DesiredBrightness").toInt()
                    emptyMap()
                }
                else -> error("Unexpected action: $action")
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/xml",
                CastWire.soap(
                    header.substringBefore('#').trim('"'),
                    action + "Response",
                    result,
                ),
            )
        }
    }
}
