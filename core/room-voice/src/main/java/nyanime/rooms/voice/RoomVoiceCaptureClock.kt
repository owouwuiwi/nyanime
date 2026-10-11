package nyanime.rooms.voice

import java.util.concurrent.locks.LockSupport

/**
 * The external WebRTC recorder has no hardware clock while AudioRecord is disabled.
 * Its buffer callback must supply one 10 ms silent frame at a time. Real captures
 * already block on AudioRecord and must not receive an additional delay.
 */
internal class RoomVoiceCaptureClock(
    private val nowNanos: () -> Long = System::nanoTime,
    private val waitNanos: (Long) -> Unit = LockSupport::parkNanos,
) {
    private var nextFrame: Long? = null

    fun captureTimestamp(bytesRead: Int, captureTimeNanos: Long): Long {
        if (bytesRead > 0) {
            nextFrame = null
            return captureTimeNanos
        }
        val now = nowNanos()
        // Drop missed silence frames after suspension; never flood the native mixer to catch up.
        val deadline = nextFrame?.takeIf { it - now in 1..FRAME_NANOS } ?: (now + FRAME_NANOS)
        var remaining = deadline - now
        while (remaining > 0 && !Thread.currentThread().isInterrupted) {
            waitNanos(remaining)
            remaining = deadline - nowNanos()
        }
        nextFrame = deadline + FRAME_NANOS
        return nowNanos()
    }

    private companion object {
        // WebRtcAudioRecord CALLBACK_BUFFER_SIZE_MS; independent of sample rate and channels.
        const val FRAME_NANOS = 10_000_000L
    }
}
