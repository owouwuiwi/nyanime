package it.lorelore.nyanime.attention.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** All explicit player commands invalidate automatic resume, including an already-paused pause. */
object AttentionControl {
    private val mutableActive = MutableStateFlow(false)
    val active = mutableActive.asStateFlow()
    val isActive: Boolean get() = mutableActive.value
    private val revision = AtomicLong()
    private val owned = ThreadLocal.withInitial { false }
    private data class ExpectedPause(val paused: Boolean, val untilNanos: Long)
    private val expected = mutableMapOf<String, ExpectedPause>()

    @Synchronized fun setActive(active: Boolean) {
        mutableActive.value = active
        expected.clear()
        revision.incrementAndGet()
    }
    fun revision(): Long = revision.get()
    fun externalCommand() {
        if (!isActive) return
        synchronized(this) {
            if (owned.get() != true) {
                expected.clear()
                revision.incrementAndGet()
            }
        }
    }

    /** A native pause notification can arrive after the issuing UI call has returned. */
    fun playbackCommand(engine: String, paused: Boolean, changesNativeState: Boolean) {
        if (!isActive) return
        synchronized(this) {
            if (owned.get() == true) {
                if (changesNativeState) expected[engine] = ExpectedPause(paused, System.nanoTime() + 2_000_000_000)
            } else {
                externalCommand()
            }
        }
    }
    fun nativePauseObserved(engine: String, paused: Boolean) {
        if (!isActive) return
        synchronized(this) {
            val pending = expected.remove(engine)
            if (pending != null && pending.paused == paused && System.nanoTime() <= pending.untilNanos) return
            externalCommand()
        }
    }
    fun <T> ownedCommand(block: () -> T): T {
        if (!isActive) return block()
        val previous = owned.get()
        owned.set(true)
        return try {
            block()
        } finally {
            owned.set(previous)
        }
    }
}

interface AttentionPlayer {
    fun snapshot(): AttentionPlayback?
    fun pause()
    fun resume()
    fun seek(positionMs: Long)
}

/** The online engine registers only after its normal lazy startup has completed. */
object AttentionPlayers {
    var online: AttentionPlayer? = null
}
