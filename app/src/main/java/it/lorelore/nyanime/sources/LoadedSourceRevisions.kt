package it.lorelore.nyanime.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Host-owned identity of the code actually loaded, never the advertised version name. */
internal data class LoadedSourceRevision(val id: Long, val source: Any, val revision: String)

internal class SourceReplacedException : CancellationException("Source revision replaced")

/** Publication and database application share a lock; network requests never hold it. */
internal class SourceRevisionRegistry {
    private val publication = Mutex()
    private val snapshots = mutableMapOf<Pair<String, Long>, LoadedSourceRevision>()
    private val known = mutableSetOf<Pair<String, Long>>()

    suspend fun publish(medium: String, loaded: List<LoadedSourceRevision>, install: () -> Unit = {}) {
        publication.withLock {
            synchronized(snapshots) {
                snapshots.keys.removeAll { it.first == medium }
                loaded.forEach {
                    snapshots[medium to it.id] = it
                    known += medium to it.id
                }
                install()
            }
        }
    }

    fun capture(medium: String, id: Long, source: Any): LoadedSourceRevision? = synchronized(snapshots) {
        val snapshot = snapshots[medium to id]
        if ((snapshot != null && snapshot.source !== source) || snapshot == null && (medium to id) in known) {
            throw SourceReplacedException()
        }
        snapshot
    }

    fun current(medium: String, ticket: LoadedSourceRevision): Boolean = synchronized(snapshots) {
        snapshots[medium to ticket.id]?.let { it.source === ticket.source && it.revision == ticket.revision } == true
    }

    suspend fun <T> apply(medium: String, ticket: LoadedSourceRevision?, block: suspend () -> T): T =
        publication.withLock {
            if (ticket != null && !current(medium, ticket)) throw SourceReplacedException()
            block()
        }
}

internal object LoadedSourceRevisions {
    val registry = SourceRevisionRegistry()
}
