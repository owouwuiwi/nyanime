package it.lorelore.nyanime.reader.quality

/** Bounded ownership of queued worker requests. An old completion cannot retire newer work. */
internal class QualityRequestGate<K>(private val capacity: Int = 32) {
    private val latest = LinkedHashMap<K, Int>()
    init {
        require(capacity > 0)
    }

    @Synchronized
    fun submit(owner: K, id: Int) {
        latest.remove(owner)
        latest[owner] = id
        while (latest.size > capacity) latest.remove(latest.keys.first())
    }

    @Synchronized
    fun isCurrent(owner: K, id: Int): Boolean = latest[owner] == id

    @Synchronized
    fun retire(owner: K, id: Int) {
        if (latest[owner] == id) latest.remove(owner)
    }
}
