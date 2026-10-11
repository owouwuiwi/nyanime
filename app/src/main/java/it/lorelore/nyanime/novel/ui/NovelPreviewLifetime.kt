package it.lorelore.nyanime.novel.ui

import it.lorelore.nyanime.novel.NovelOnlineAccess
import kotlinx.coroutines.CompletableDeferred

/**
 * A cancelled navigator may still be finishing a cache write; cleanup awaits its actual finally.
 */
internal class NovelPreviewLifetime {
    private val gate = Any()
    private var closing = false
    private var active = 0
    private val idle = CompletableDeferred<Unit>()

    suspend fun <T> run(operation: suspend () -> T): T {
        synchronized(gate) {
            check(!closing) { "The preview has ended" }
            active++
        }
        try {
            return operation()
        } finally {
            synchronized(gate) {
                active--
                if (closing && active == 0) idle.complete(Unit)
            }
        }
    }

    fun stop() =
        synchronized(gate) {
            closing = true
            if (active == 0) idle.complete(Unit)
        }

    suspend fun drain() {
        stop()
        idle.await()
    }
}

internal class NovelPreviewAccess(
    private val delegate: NovelOnlineAccess,
    private val lifetime: NovelPreviewLifetime,
) : NovelOnlineAccess by delegate {
    override suspend fun materialize(href: String) = lifetime.run { delegate.materialize(href) }

    override suspend fun cachedOnly(): NovelOnlineAccess = lifetime.run {
        NovelPreviewAccess(delegate.cachedOnly(), lifetime)
    }
}
