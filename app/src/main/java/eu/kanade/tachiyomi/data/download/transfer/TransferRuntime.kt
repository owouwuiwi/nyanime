package eu.kanade.tachiyomi.data.download.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class TransferPhase { QUEUED, PREPARING, TRANSFERRING, PROCESSING, SAVING, COMPLETE, WAITING, FAILED }
enum class TransferWait { MANUAL, PLAYBACK, NETWORK, WIFI, SYSTEM }

data class TransferSnapshot(
    val phase: TransferPhase = TransferPhase.QUEUED,
    val bytes: Long = 0,
    val total: Long = -1,
    val bytesPerSecond: Long = 0,
    val secondsRemaining: Long? = null,
    val wait: TransferWait? = null,
)

/** Fetch's recent-value weighting, with a fixed-size ring instead of a growing backing array.
 * Apache-2.0; upstream revision and attribution in docs/download-components.md.
 */
internal class RecentAverage(private val capacity: Int = 8) {
    private val values = DoubleArray(capacity.also { require(it > 0) })
    private var next = 0
    private var count = 0
    fun add(value: Double) {
        values[next] = value
        next = (next + 1) % capacity
        count = minOf(count + 1, capacity)
    }
    fun clear() {
        next = 0
        count = 0
    }
    fun average(): Double {
        var result = 0.0
        for (i in 0 until count) result += values[(next - 1 - i + capacity) % capacity] * (count - i)
        return if (count == 0) 0.0 else result / (count * (count + 1) / 2.0)
    }
}

class TransferRuntime(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutable = MutableStateFlow(TransferSnapshot())
    val state = mutable.asStateFlow()
    private val average = RecentAverage()
    private val received = AtomicLong()
    private var previousBytes = 0L
    private var previousTime = clock()

    @Synchronized
    fun phase(phase: TransferPhase, wait: TransferWait? = null) {
        average.clear()
        mutable.value = mutable.value.copy(phase = phase, wait = wait, bytesPerSecond = 0, secondsRemaining = null)
        previousTime = clock()
        previousBytes = received.get()
    }

    @Synchronized
    fun addBytes(count: Long) = progress(received.addAndGet(count), -1)

    @Synchronized
    fun progress(bytes: Long, total: Long) {
        received.set(bytes)
        val now = clock()
        val elapsed = now - previousTime
        if (elapsed < 250 && bytes != total) return
        if (elapsed > 0 && bytes >= previousBytes) average.add((bytes - previousBytes) * 1000.0 / elapsed)
        val speed = average.average().toLong().coerceAtLeast(0)
        mutable.value = TransferSnapshot(
            TransferPhase.TRANSFERRING,
            bytes,
            total,
            speed,
            if (total > bytes && speed > 0) (total - bytes + speed - 1) / speed else null,
        )
        previousBytes = bytes
        previousTime = now
    }
}
