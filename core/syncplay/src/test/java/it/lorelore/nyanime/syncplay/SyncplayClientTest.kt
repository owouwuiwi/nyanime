package it.lorelore.nyanime.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyncplayClientTest {
    @Test fun `hello and heartbeats work while paused without a video`() = runBlocking {
        ServerSocket(0).use { server ->
            val done = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            val peer = pool.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = BoundedLineReader(socket.getInputStream())
                    val hello = SyncplayProtocol.parse(reader.next()).obj("Hello")!!
                    assertEquals("Reader", hello.text("username"))
                    socket.getOutputStream().write(
                        (
                            "{\"Hello\":{\"username\":\"Reader\",\"room\":{\"name\":\"Room\"}," +
                                "\"version\":\"1.7.0\"}}\n"
                            ).toByteArray(),
                    )
                    var states = 0
                    while (states < 3) {
                        val packet = SyncplayProtocol.parse(reader.next())
                        packet.obj("State")?.let {
                            assertNull(it.obj("playstate"), "An observer must not pause the room")
                            assertTrue(it.obj("ping") != null)
                            states++
                        }
                    }
                    done.countDown()
                    while (socket.getInputStream().read() != -1) Unit
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client =
                SyncplayClient(
                    SyncplayOptions(SyncplayEndpoint("127.0.0.1", server.localPort), "Reader", "Room", false),
                )
            try {
                client.start(scope)
                withTimeout(5000) { client.state.first { it.phase == SyncplayPhase.Connected } }
                assertTrue(done.await(5, TimeUnit.SECONDS))
                client.close()
                peer.get(3, TimeUnit.SECONDS)
                assertEquals(SyncplayPhase.Idle, client.state.value.phase)
            } finally {
                client.close()
                scope.cancel()
                pool.shutdownNow()
            }
        }
    }

    @Test fun `required TLS never sends credentials to a server that refuses TLS`() = runBlocking {
        ServerSocket(0).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            val peer = pool.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val first = BoundedLineReader(socket.getInputStream()).next()
                    assertTrue(first.contains("startTLS"))
                    assertFalse(first.contains("Hello"))
                    assertFalse(first.contains("secret"))
                    socket.getOutputStream().write("{\"TLS\":{\"startTLS\":\"false\"}}\n".toByteArray())
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client =
                SyncplayClient(
                    SyncplayOptions(
                        SyncplayEndpoint("127.0.0.1", server.localPort),
                        "Reader",
                        "Room",
                        password = "secret",
                    ),
                )
            try {
                client.start(scope)
                val state = withTimeout(5000) { client.state.first { it.phase == SyncplayPhase.Failed } }
                assertEquals("tls", state.error)
                peer.get(3, TimeUnit.SECONDS)
                Unit
            } finally {
                client.close()
                scope.cancel()
                pool.shutdownNow()
            }
        }
    }

    @Test fun `disconnect interrupts a connection waiting for TLS response`() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            val peer = pool.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    BoundedLineReader(socket.getInputStream()).next()
                    accepted.countDown()
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client =
                SyncplayClient(SyncplayOptions(SyncplayEndpoint("127.0.0.1", server.localPort), "Reader", "Room"))
            try {
                client.start(scope)
                assertTrue(accepted.await(3, TimeUnit.SECONDS))
                client.close()
                peer.get(2, TimeUnit.SECONDS)
                assertEquals(SyncplayPhase.Idle, client.state.value.phase)
            } finally {
                client.close()
                scope.cancel()
                pool.shutdownNow()
            }
        }
    }
}
