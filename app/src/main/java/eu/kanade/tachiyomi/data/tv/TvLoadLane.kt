package eu.kanade.tachiyomi.data.tv

import nyanime.tv.api.TvStream

/**
 * MPV's JNI events are delivered in order but do not expose playlist-entry IDs.
 * Stop/idle is the boundary between loads. Rapid zaps replace the pending selection,
 * and native callbacks capture eventTicket before being posted to the owner dispatcher.
 */
class TvLoadLane(
    private val stop: () -> Unit,
    private val load: (TvStream, Long) -> Unit,
) {
    private data class Pending(val stream: TvStream, val ticket: Long)
    private var pending: Pending? = null
    private var initialized = false
    private var idle = false
    private var stopping = false
    private var closed = false

    @Volatile var eventTicket: Long? = null
        private set
    val owned: Boolean get() = initialized && !closed

    /** Called after MPV init with the actual idle-active property, not a guessed timeout. */
    fun initialized(idle: Boolean) {
        if (closed) return
        check(!initialized)
        initialized = true
        this.idle = idle
        pump()
    }

    fun request(stream: TvStream, ticket: Long) {
        if (closed) return
        require(ticket > 0)
        pending = Pending(stream, ticket)
        eventTicket = null
        pump()
    }

    /** Received on the same serialized dispatcher as the other native events. */
    fun idle(value: Boolean) {
        if (!owned) return
        idle = value
        if (value) {
            stopping = false
            eventTicket = null
            pump()
        }
    }

    fun close() {
        closed = true
        eventTicket = null
        pending = null
        initialized = false
    }

    private fun pump() {
        if (!owned) return
        val next = pending ?: return
        if (!idle) {
            if (!stopping) {
                stopping = true
                try {
                    stop()
                } catch (failure: Exception) {
                    stopping = false
                    throw failure
                }
            }
            return
        }
        pending = null
        idle = false
        stopping = false
        eventTicket = next.ticket
        load(next.stream, next.ticket)
    }
}
