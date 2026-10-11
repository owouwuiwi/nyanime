package eu.kanade.tachiyomi.data.download.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore

/** Adapted from Downpour ConnectionTuner (Apache-2.0), see docs/download-components.md.
 * Server backoff overrides speed probing until the cooldown expires.
 */
internal class ConnectionTuner(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var limit = 2
        private set
    private var lastConnections = -1
    private var lastSpeed = 0L
    private var cooldownUntil = 0L
    private var probeAfter = 0L

    fun sample(speed: Long) {
        if (clock() < cooldownUntil || speed <= 0) return
        val current = limit
        limit = when {
            lastConnections < 0 -> current + 1
            current > lastConnections -> {
                val gain = speed - lastSpeed
                if (gain > lastSpeed.toDouble() / lastConnections * 0.5 && speed > lastSpeed) {
                    current + 1
                } else {
                    lastConnections
                }
            }
            clock() >= probeAfter && current < 4 -> current + 1
            else -> current
        }.coerceIn(1, 4)
        if (lastConnections < 0 || limit > current || current <= lastConnections) {
            lastConnections = current
            lastSpeed = speed
        }
        if (limit < current) probeAfter = clock() + 60_000
    }

    fun backoff(retryAfterMillis: Long = 30_000) {
        limit = maxOf(1, limit / 2)
        lastConnections = -1
        cooldownUntil = clock() + retryAfterMillis.coerceIn(5_000, 300_000)
        probeAfter = cooldownUntil
    }
}

/** One budget for both existing queues, including opaque extension getImage() calls. */
object TransferCoordinator {
    private data class SourceState(
        var active: Int = 0,
        val tuner: ConnectionTuner = ConnectionTuner(),
        var bytes: Long = 0,
        var measuredAt: Long = System.nanoTime(),
    )
    private val sources = mutableMapOf<Long, SourceState>()
    private var active = 0
    private val changed = MutableStateFlow(0L)
    val imageProcessing = Semaphore(1)
    val finalization = Semaphore(1)

    suspend fun <T> transfer(source: Long, block: suspend () -> T): T {
        while (true) {
            val revision = changed.value
            val acquired = synchronized(this) {
                val state = sources.getOrPut(source) { SourceState() }
                if (active < 8 && state.active < state.tuner.limit && !DownloadPlaybackGate.active.value) {
                    if (state.active == 0) {
                        state.bytes = 0
                        state.measuredAt = System.nanoTime()
                    }
                    active++
                    state.active++
                    true
                } else {
                    false
                }
            }
            if (acquired) break
            changed.first { it != revision }
        }
        try {
            return block()
        } finally {
            synchronized(this) {
                active--
                sources.getValue(source).active--
                changed.value++
            }
        }
    }

    @Synchronized
    fun bytes(source: Long, bytes: Long) {
        val state = sources.getOrPut(source) { SourceState() }
        state.bytes += bytes
        val now = System.nanoTime()
        val elapsed = now - state.measuredAt
        if (elapsed >= 1_000_000_000L) {
            // Probe only saturated sources: idle time is not evidence of a connection limit.
            if (state.active >=
                state.tuner.limit
            ) {
                state.tuner.sample((state.bytes * 1_000_000_000.0 / elapsed).toLong())
            }
            state.bytes = 0
            state.measuredAt = now
            changed.value++
        }
    }

    @Synchronized
    fun backoff(source: Long, retryAfterMillis: Long = 30_000) {
        sources.getOrPut(source) { SourceState() }.tuner.backoff(retryAfterMillis)
        changed.value++
    }

    @Synchronized
    fun wake() {
        changed.value++
    }
}

/** A separate playback gate: no dependency on Ultra, player internals or watch-room protocol. */
object DownloadPlaybackGate {
    private val mutable = MutableStateFlow(false)
    val active = mutable.asStateFlow()
    private var players = 0

    @Synchronized
    fun enter() {
        players++
        mutable.value = true
        TransferCoordinator.wake()
    }

    @Synchronized
    fun leave() {
        players = (players - 1).coerceAtLeast(0)
        mutable.value = players > 0
        TransferCoordinator.wake()
    }
}
