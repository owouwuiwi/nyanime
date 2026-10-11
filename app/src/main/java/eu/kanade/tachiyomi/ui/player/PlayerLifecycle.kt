package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Android may resume MainActivity before destroying the player removed by CLEAR_TOP. */
internal object PlayerLifecycle {
    private val owners = MutableStateFlow(0)
    private val native = NativePlaybackOwnership()

    suspend fun acquire(owner: Any, stop: () -> Unit, cancelled: () -> Boolean): Boolean {
        if (!native.acquire(owner, stop, cancelled)) return false
        acquired()
        return true
    }

    fun release(owner: Any) {
        if (native.release(owner)) released()
    }

    fun acquired() {
        owners.update { it + 1 }
    }

    fun released() {
        owners.update { (it - 1).coerceAtLeast(0) }
    }

    /** Suspend until native teardown is complete; never initialize two MPV owners concurrently. */
    suspend fun awaitReleased() {
        owners.first { it == 0 }
    }
}
