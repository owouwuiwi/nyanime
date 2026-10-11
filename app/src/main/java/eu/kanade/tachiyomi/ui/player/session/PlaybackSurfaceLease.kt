package eu.kanade.tachiyomi.ui.player.session

/** All mutations are serialized on the owner dispatcher. Views never own the native renderer. */
internal class PlaybackSurfaceLease<S : Any>(
    private val attach: (S) -> Unit,
    private val detach: () -> Unit,
    private val resize: (Int, Int) -> Unit,
) {
    private data class Entry<S>(val surface: S, val width: Int, val height: Int)
    private val entries = java.util.IdentityHashMap<Any, Entry<S>>()
    private var owner: Any? = null
    private var attached: S? = null
    private var size: Pair<Int, Int>? = null
    private var ready = false
    private var closed = false

    fun activate(token: Any) {
        if (closed) return
        owner = token
        // Keep the previous live surface until the incoming host has a valid replacement.
        if (entries.containsKey(token)) reconcile()
    }

    fun offer(token: Any, surface: S, width: Int, height: Int) {
        if (closed) return
        entries[token] = Entry(surface, width, height)
        if (owner === token) reconcile()
    }

    fun removed(token: Any) {
        val removed = entries.remove(token)
        if (owner === token || removed?.surface === attached) reconcile()
    }

    fun deactivate(token: Any) {
        if (owner !== token || closed) return
        owner = null
        reconcile()
    }

    fun initialized() {
        if (closed) return
        ready = true
        reconcile()
    }

    fun close() {
        if (closed) return
        closed = true
        if (attached != null && ready) detach()
        attached = null
        owner = null
        size = null
        entries.clear()
        ready = false
    }

    private fun reconcile() {
        if (!ready || closed) return
        val entry = owner?.let(entries::get)
        if (entry?.surface !== attached) {
            if (attached != null) detach()
            attached = entry?.surface
            size = null
            entry?.let { attach(it.surface) }
        }
        if (entry != null && entry.width > 0 && entry.height > 0) {
            val next = entry.width to entry.height
            if (size != next) {
                resize(entry.width, entry.height)
                size = next
            }
        }
    }
}
