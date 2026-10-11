package eu.kanade.tachiyomi.ui.player.session

import android.view.WindowManager
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import java.util.WeakHashMap

/** Keeps Android's window rotation in step with the video host, without owning playback. */
internal class PlaybackWindowMode {
    private data class Original(val fullscreen: Boolean, val rotation: Int)

    private val original = WeakHashMap<BaseActivity, Original>()

    fun apply(activity: BaseActivity, fullscreen: Boolean) {
        val window = activity.window
        original.getOrPut(activity) {
            Original(
                window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0,
                window.attributes.rotationAnimation,
            )
        }
        // The playback host owns the resize animation. A system crossfade still rotates
        // its old screenshot into the new display, exposing a vertical strip of video.
        val rotation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
        if (window.attributes.rotationAnimation != rotation) {
            window.attributes = window.attributes.apply { rotationAnimation = rotation }
        }
        // Retain the immersive window through the incoming layout commit. Restore its
        // system bars only after the playback host has replaced the rotation cover.
        if (fullscreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    fun restore(activity: BaseActivity) {
        val saved = original.remove(activity) ?: return
        val window = activity.window
        window.attributes = window.attributes.apply { rotationAnimation = saved.rotation }
        if (saved.fullscreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }
}
