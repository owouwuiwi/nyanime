package nyanime.secretgame

/** Monotonic time only. Navigation consumes the gesture until its owner resets it. */
class VersionTapGate {
    private var previous: Long? = null
    private var count = 0
    private var consumed = false

    fun tap(now: Long): Boolean {
        if (consumed) return false
        previous?.let { if (now < it || now - it >= 2_000) count = 0 }
        previous = now
        count++
        if (count != 5) return false
        consumed = true
        return true
    }

    fun reset() {
        previous = null
        count = 0
        consumed = false
    }
}
