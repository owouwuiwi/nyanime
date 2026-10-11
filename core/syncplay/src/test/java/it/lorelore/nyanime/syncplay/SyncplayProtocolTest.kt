package it.lorelore.nyanime.syncplay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException

class SyncplayProtocolTest {
    private fun protocol() = SyncplayProtocol(
        SyncplayOptions(SyncplayEndpoint("server.test", 8999), "Reader", "Private test"),
        { 100.0 },
        { 40.0 },
    ).also {
        it.receive(
            SyncplayProtocol.parse(
                """{"Hello":{"username":"Reader","version":"1.7.0","room":{"name":"Private test"}}}""",
            ),
        )
    }

    @Test fun `parse explicit endpoints without accepting URLs or credentials`() {
        assertEquals(SyncplayEndpoint("server.test", 8999), SyncplayEndpoint.parse(" server.test:8999 "))
        assertEquals(SyncplayEndpoint("::1", 1234), SyncplayEndpoint.parse("[::1]:1234"))
        listOf(
            "http://server.test:80",
            "name@server.test:80",
            "server.test",
            "server.test:0",
            "server.test:65536",
            "a b:80",
            "server.test:80/path",
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { SyncplayEndpoint.parse(it) }
        }
    }

    @Test fun `only advertise implemented capabilities and hash server password`() {
        val p =
            SyncplayProtocol(
                SyncplayOptions(
                    SyncplayEndpoint("server.test", 8999),
                    "Reader",
                    "Room",
                    password = "test",
                ),
                {
                    0.0
                },
                { 0.0 },
            )
        val hello = p.hello().obj("Hello")!!
        assertEquals("098f6bcd4621d373cade4e832627b4f6", hello.text("password"))
        assertEquals(false, hello.obj("features")!!.bool("sharedPlaylists"))
        assertEquals(false, hello.obj("features")!!.bool("managedRooms"))
    }

    @Test fun `on the fly changes wait for acknowledgements and acknowledge server changes`() {
        val p = protocol()
        val local = SyncplayLocal(20.0, false, true, "Episode", 100.0)
        val change = p.outgoing(local, changed = true, seek = true)
        assertEquals(1, change.obj("State")!!.obj("ignoringOnTheFly")!!.integer("client"))
        assertNull(p.receive(SyncplayProtocol.parse("""{"State":{"playstate":{"position":1,"paused":true}}}""")))
        val remote = p.receive(
            SyncplayProtocol.parse(
                """{"State":{"ignoringOnTheFly":{"client":1,"server":4},"playstate":{"position":20,"paused":false,"doSeek":true,"setBy":"Reader"}}}""",
            ),
        )!!
        assertEquals(20.0, remote.position)
        assertTrue(remote.seek)
        assertEquals(4, p.outgoing(local).obj("State")!!.obj("ignoringOnTheFly")!!.integer("server"))
        assertNull(p.outgoing(local).obj("State")!!.obj("ignoringOnTheFly"))
    }

    @Test fun `members are scoped to the selected room and bounded`() {
        val p = protocol()
        p.receive(
            SyncplayProtocol.parse(
                """{"List":{"Private test":{"Reader":{"file":{"name":"Episode","duration":100}}},"Other":{"Stranger":{"file":{"name":"Different","duration":50}}}}}""",
            ),
        )
        assertEquals(listOf("Reader"), p.state.members.map { it.name })
        p.receive(
            SyncplayProtocol.parse(
                """{"Set":{"user":{"Peer":{"room":{"name":"Private test"},"file":{"name":"Episode","duration":100}}}}}""",
            ),
        )
        assertEquals(2, p.state.members.size)
        p.receive(
            SyncplayProtocol.parse("""{"Set":{"user":{"Peer":{"room":{"name":"Elsewhere"},"event":{"left":true}}}}}"""),
        )
        assertEquals(listOf("Reader"), p.state.members.map { it.name })
    }

    @Test fun `observers and unready videos acknowledge packets without publishing a playback clock`() {
        val p = protocol()
        listOf(SyncplayLocal(), SyncplayLocal(0.0, true, false, "Example episode", 180.0)).forEach { local ->
            val state = p.outgoing(local).obj("State")!!
            assertNull(state.obj("playstate"))
            assertTrue(state.obj("ping") != null)
        }
        assertEquals(
            false,
            p.outgoing(SyncplayLocal(30.0, false, true, "Example episode", 180.0))
                .obj("State")!!.obj("playstate")!!.bool("paused"),
        )
    }

    @Test fun `bad state does not become a playback command`() {
        val p = protocol()
        listOf("-1", "90000", "1e309", "null", "\"unavailable\"").forEach { position ->
            assertThrows(SyncplayFailure::class.java) {
                p.receive(SyncplayProtocol.parse("""{"State":{"playstate":{"position":$position,"paused":false}}}"""))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { p.outgoing(SyncplayLocal(Double.NaN)) }
        assertThrows(SyncplayFailure::class.java) { SyncplayProtocol.parse("[".repeat(25) + "]".repeat(25)) }
        assertThrows(SyncplayFailure::class.java) { SyncplayProtocol.parse(" ".repeat(65537)) }
    }

    @Test fun `partial lines survive timeouts without consuming the next packet`() {
        val bytes = "{\"List\":null}\r\n{\"Chat\":\"ok\"}\n".toByteArray()
        var index = 0
        var interrupted = false
        val input = object : InputStream() {
            override fun read(): Int {
                if (index == 5 && !interrupted) {
                    interrupted = true
                    throw SocketTimeoutException()
                }
                return if (index < bytes.size) bytes[index++].toInt() else -1
            }
        }
        val reader = BoundedLineReader(input)
        assertThrows(SocketTimeoutException::class.java) { reader.next() }
        assertEquals("{\"List\":null}", reader.next())
        assertEquals("{\"Chat\":\"ok\"}", reader.next())
        assertThrows(SyncplayFailure::class.java) {
            BoundedLineReader(ByteArrayInputStream("12345".toByteArray()), 4).next()
        }
    }
}
