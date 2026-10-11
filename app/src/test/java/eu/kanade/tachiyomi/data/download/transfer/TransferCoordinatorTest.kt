package eu.kanade.tachiyomi.data.download.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class TransferCoordinatorTest {
    @Test fun budgetIsSharedAcrossSourcesAndQueueTypes() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val perSource = (0..4).associateWith { AtomicInteger() }
        val perSourcePeak = (0..4).associateWith { AtomicInteger() }
        (0 until 40).map { index ->
            async(Dispatchers.IO) {
                val source = index % 5
                TransferCoordinator.transfer(700L + source) {
                    val count = active.incrementAndGet()
                    val sourceCount = perSource.getValue(source).incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    perSourcePeak.getValue(source).updateAndGet { maxOf(it, sourceCount) }
                    delay(20)
                    active.decrementAndGet()
                    perSource.getValue(source).decrementAndGet()
                }
            }
        }.awaitAll()
        assertTrue(peak.get() in 2..8)
        assertTrue(perSourcePeak.values.all { it.get() in 1..4 })
        assertEquals(0, active.get())
    }

    @Test fun playbackGateBlocksNewTransfersAndResumesOnlyAfterLastPlayerLeaves() = runBlocking {
        DownloadPlaybackGate.enter()
        DownloadPlaybackGate.enter()
        try {
            val task = async(Dispatchers.IO) { TransferCoordinator.transfer(800) { true } }
            delay(30)
            assertFalse(task.isCompleted)
            DownloadPlaybackGate.leave()
            delay(30)
            assertFalse(task.isCompleted)
            DownloadPlaybackGate.leave()
            assertTrue(task.await())
        } finally {
            DownloadPlaybackGate.leave()
            DownloadPlaybackGate.leave()
        }
    }

    @Test fun serverBackoffCannotBeOverruledBySpeedProbe() {
        var now = 0L
        val tuner = ConnectionTuner { now }
        tuner.sample(100)
        assertEquals(3, tuner.limit)
        tuner.backoff(60_000)
        repeat(10) {
            now += 1000
            tuner.sample(10_000)
        }
        assertEquals(1, tuner.limit)
        now = 61_000
        tuner.sample(10_000)
        assertEquals(2, tuner.limit)
    }

    @Test fun queueAppendDoesNotDisplaceOngoingItemAndManualSlotCountIsRespected() {
        data class Item(val source: Long, val active: Boolean)
        val ongoing = Item(1, true)
        val newItems = List(5) { Item(2L + it, false) }
        assertEquals(listOf(ongoing), selectDownloads(newItems + ongoing, 1, Item::source, Item::active))
        assertEquals(3, selectDownloads(listOf(ongoing) + newItems, 0, Item::source, Item::active).size)
    }

    @Test fun noBenefitReducesConnectionsAndProbesAgainAfterNetworkConditionsMayChange() {
        var now = 0L
        val tuner = ConnectionTuner { now }
        tuner.sample(100)
        assertEquals(3, tuner.limit)
        now = 1000
        tuner.sample(105)
        assertEquals(2, tuner.limit)
        now = 30_000
        tuner.sample(200)
        assertEquals(2, tuner.limit)
        now = 61_001
        tuner.sample(200)
        assertEquals(3, tuner.limit)
        now += 1000
        tuner.sample(320)
        assertEquals(4, tuner.limit)
    }

    @Test fun recentWeightedAverageHasBoundedStorageEvenAfterLongDownloads() {
        val average = RecentAverage(3)
        repeat(100_000) { average.add(9.0) }
        average.add(12.0)
        assertEquals(10.5, average.average(), 0.00001)
    }

    @Test fun speedAndEtaUseMonotonicSamplesAndWaitingStopsTheEstimate() {
        var now = 0L
        val runtime = TransferRuntime { now }
        now = 1000
        runtime.progress(1000, 5000)
        assertEquals(1000L, runtime.state.value.bytesPerSecond)
        assertEquals(4L, runtime.state.value.secondsRemaining)
        runtime.phase(TransferPhase.WAITING, TransferWait.PLAYBACK)
        assertEquals(0L, runtime.state.value.bytesPerSecond)
        assertNull(runtime.state.value.secondsRemaining)
    }
}
