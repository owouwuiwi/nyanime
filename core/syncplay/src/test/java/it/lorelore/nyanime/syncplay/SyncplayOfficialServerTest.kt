package it.lorelore.nyanime.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in interoperability test against a separately running official server, never a public room. */
@EnabledIfSystemProperty(named = "syncplay.fixture.port", matches = "[0-9]+")
class SyncplayOfficialServerTest {
    @Test fun `a late observer does not pause or rewind an already playing room`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = requireNotNull(System.getProperty("syncplay.fixture.port")).toInt()
        val room = "Test-" + UUID.randomUUID().toString().take(12)
        val first = SyncplayClient(SyncplayOptions(SyncplayEndpoint("127.0.0.1", port), "First", room, false))
        val observer = SyncplayClient(SyncplayOptions(SyncplayEndpoint("127.0.0.1", port), "Observer", room, false))
        try {
            first.start(scope)
            withTimeout(6000) { first.state.first { it.phase == SyncplayPhase.Connected } }
            val file = SyncplayLocal(33.0, false, true, "Example episode", 180.0)
            first.observe(file)
            assertTrue(first.change(file, seek = true))
            withTimeout(6000) {
                first.timeline.filterNotNull().first { !it.remote.paused && it.remote.position >= 33.0 }
            }
            val disturbed = AtomicBoolean(false)
            val observations = scope.launch {
                first.timeline.filterNotNull().collect {
                    if (it.remote.paused || it.remote.position < 32.0) disturbed.set(true)
                }
            }
            observer.start(scope)
            withTimeout(6000) { observer.state.first { it.phase == SyncplayPhase.Connected } }
            withTimeout(6000) { first.state.first { it.members.size == 2 } }
            // Include multiple actual observer heartbeats; the initial room snapshot alone missed this bug.
            delay(2500)
            assertFalse(disturbed.get())
            assertFalse(first.timeline.value!!.remote.paused)
            observations.cancel()
        } finally {
            first.close()
            observer.close()
            scope.cancel()
        }
    }

    @Test fun `two clients exchange video state and chat through the official server`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = requireNotNull(System.getProperty("syncplay.fixture.port")).toInt()
        val room = "Test-" + UUID.randomUUID().toString().take(12)
        val first = SyncplayClient(SyncplayOptions(SyncplayEndpoint("127.0.0.1", port), "First", room, false))
        val second = SyncplayClient(SyncplayOptions(SyncplayEndpoint("127.0.0.1", port), "Second", room, false))
        try {
            first.start(scope)
            withTimeout(6000) { first.state.first { it.phase == SyncplayPhase.Connected } }
            val file = SyncplayLocal(0.0, true, true, "Example episode", 180.0)
            first.observe(file)
            second.observe(file)
            second.start(scope)
            withTimeout(6000) { second.state.first { it.phase == SyncplayPhase.Connected } }
            withTimeout(6000) {
                first.state.first {
                    it.members.size == 2 &&
                        it.members.all { member -> member.file == file.file }
                }
            }
            assertTrue(first.state.value.chat)
            assertTrue(first.state.value.readiness)
            first.observe(file.copy(position = 33.0, paused = false))
            first.change(file.copy(position = 33.0, paused = false), seek = true)
            val seek = withTimeout(6000) {
                second.timeline.filterNotNull().first {
                    it.remote.seek &&
                        !it.remote.paused &&
                        it.remote.position >= 33.0
                }
            }
            assertFalse(seek.remote.paused)
            first.observe(file.copy(position = 36.0, paused = true))
            first.change(file.copy(position = 36.0, paused = true))
            withTimeout(6000) {
                second.timeline.filterNotNull().first { it.remote.paused && it.remote.position >= 35.0 }
            }
            assertTrue(first.chat("Test message"))
            val chat = withTimeout(6000) {
                second.state.first {
                    it.messages.any { message ->
                        message.text ==
                            "Test message"
                    }
                }
            }
            assertEquals("First", chat.messages.last().name)
        } finally {
            first.close()
            second.close()
            scope.cancel()
        }
    }
}
