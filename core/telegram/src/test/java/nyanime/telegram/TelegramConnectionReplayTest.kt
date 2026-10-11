package nyanime.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TelegramConnectionReplayTest {
    private fun event(state: String) = tdRequest("updateConnectionState", "state" to tdRequest(state))

    @Test
    fun lateSubscriberReceivesAlreadyEstablishedConnection() {
        val replay = TelegramConnectionReplay()
        replay.attach { assertNull(it) }
        replay.update(event("connectionStateReady")) {}
        replay.attach {
            val state = TelegramState(
                authorization = "authorizationStateReady",
                account = TelegramAccount(1, "Device owner", ""),
                connection = requireNotNull(it).objectOrNull("state")!!.type(),
            )
            assertEquals(true, state.online)
        }
    }

    @Test
    fun snapshotCannotArriveAfterANewerLiveUpdate() {
        val replay = TelegramConnectionReplay()
        replay.update(event("connectionStateReady")) {}
        val events = mutableListOf<String>()
        val attached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val updating = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attach = executor.submit {
                replay.attach {
                    attached.countDown()
                    assertEquals(true, release.await(3, TimeUnit.SECONDS))
                    events.add(it!!.objectOrNull("state")!!.type())
                }
            }
            assertEquals(true, attached.await(3, TimeUnit.SECONDS))
            val update = executor.submit {
                updating.countDown()
                replay.update(event("connectionStateConnecting")) { events.add("connectionStateConnecting") }
            }
            assertEquals(true, updating.await(3, TimeUnit.SECONDS))
            assertFalse(update.isDone)
            release.countDown()
            attach.get(3, TimeUnit.SECONDS)
            update.get(3, TimeUnit.SECONDS)
            assertEquals(listOf("connectionStateReady", "connectionStateConnecting"), events)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun disconnectedStateReplacesOnlineSnapshotAndFreshEngineHasNoSnapshot() {
        val replay = TelegramConnectionReplay()
        replay.update(event("connectionStateReady")) {}
        replay.update(event("connectionStateWaitingForNetwork")) {}
        replay.attach { assertEquals("connectionStateWaitingForNetwork", it!!.objectOrNull("state")!!.type()) }
        TelegramConnectionReplay().attach { assertNull(it) }
    }
}
