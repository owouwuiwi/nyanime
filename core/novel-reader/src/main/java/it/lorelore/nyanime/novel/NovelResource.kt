package it.lorelore.nyanime.novel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Also owns cancellation at the dispatcher handoff, after acquire has already succeeded. */
internal suspend fun <T : Any> acquireNovelResource(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    acquire: suspend () -> T,
    release: (T) -> Unit,
): T {
    var owned: T? = null
    try {
        return withContext(dispatcher) {
            acquire().also {
                owned = it
                ensureActive()
            }
        }
    } catch (error: Throwable) {
        owned?.let { value ->
            runCatching { release(value) }.exceptionOrNull()?.let(error::addSuppressed)
        }
        throw error
    }
}
