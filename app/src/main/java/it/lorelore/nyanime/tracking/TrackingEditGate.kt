package it.lorelore.nyanime.tracking

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Manual edits, refreshes and automatic progress updates serialize on the same record. */
object TrackingEditGate {
    class Entry {
        val mutex = Mutex()
        val generation = AtomicLong()
        val progressGeneration = AtomicLong()
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    fun key(manga: Boolean, track: Long) = "${if (manga) TrackingMedium.MANGA else TrackingMedium.ANIME}:$track"
    fun entry(key: String): Entry = entries.getOrPut(key) { Entry() }
}
