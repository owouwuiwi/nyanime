package eu.kanade.tachiyomi.ui.player.session

import android.os.Build
import android.view.View
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Commit the video cover in the app window before Android captures its rotation animation. */
internal suspend fun awaitPlaybackWindowFrame(view: View) {
    suspendCancellableCoroutine<Unit> { continuation ->
        val observer = view.viewTreeObserver
        val callback = Runnable { if (continuation.isActive) continuation.resume(Unit) }
        if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated && observer.isAlive) {
            observer.registerFrameCommitCallback(callback)
            continuation.invokeOnCancellation {
                if (observer.isAlive) observer.unregisterFrameCommitCallback(callback)
            }
            view.invalidate()
        } else {
            val first = Runnable { view.postOnAnimation(callback) }
            view.postOnAnimation(first)
            continuation.invokeOnCancellation {
                view.removeCallbacks(first)
                view.removeCallbacks(callback)
            }
        }
    }
}
