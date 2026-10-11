package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes the process-wide native renderer. Asking an Activity to finish is not
 * teardown: a replacement waits for the previous owner's explicit release.
 */
internal class NativePlaybackOwnership {
    private data class Owner(val token: Any, val stop: () -> Unit)
    private val owner = MutableStateFlow<Owner?>(null)
    private val acquisition = Mutex()

    suspend fun acquire(token: Any, stop: () -> Unit, cancelled: () -> Boolean): Boolean = acquisition.withLock {
        if (cancelled()) return@withLock false
        val previous = owner.value
        if (previous?.token === token) return@withLock true
        previous?.stop?.invoke()
        owner.first { it == null }
        if (cancelled()) return@withLock false
        owner.value = Owner(token, stop)
        true
    }

    fun release(token: Any): Boolean {
        val previous = owner.value ?: return false
        if (previous.token !== token) return false
        owner.value = null
        return true
    }
}
