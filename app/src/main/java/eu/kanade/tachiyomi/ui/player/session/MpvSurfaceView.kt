package eu.kanade.tachiyomi.ui.player.session

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.player.PlayerSession
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.ui.privacy.PrivacyMedia
import eu.kanade.tachiyomi.ui.privacy.observeContentIncognito
import eu.kanade.tachiyomi.ui.privacy.registerNsfwView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.coroutines.resume

/** A disposable rendering host. Creating or releasing it never loads a file or destroys MPV. */
internal class MpvSurfaceView(
    context: Context,
    private val session: PlayerSession,
    private val activity: BaseActivity,
) : SurfaceView(context), SurfaceHolder.Callback, DefaultLifecycleObserver {
    private val token = Any()
    private var released = false
    private val engine get() = session.player

    init {
        if (android.os.Build.VERSION.SDK_INT >= 34) setSurfaceLifecycle(SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
        holder.addCallback(this)
        activity.lifecycle.addObserver(this)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) activate()
        activity.privacyDisplayController?.registerView(this, PrivacyArea.VIDEO)
        activity.privacyDisplayController?.registerNsfwView(this, PrivacyArea.VIDEO) {
            session.live.value?.session?.state?.value?.media?.channel ?: session.viewModel.currentAnime.value
        }
        activity.privacyDisplayController?.observeContentIncognito(
            PrivacyArea.VIDEO,
            PrivacyMedia.VIDEO,
            combine(session.viewModel.currentAnime, session.live) {
                    anime,
                    live,
                ->
                if (live ==
                    null
                ) {
                    anime?.source
                } else {
                    null
                }
            },
            owner = this,
        )
    }

    private fun activate() {
        if (released || !session.available) return
        session.attachHost(activity)
        session.renderHost(this)
        engine.activateSurface(token)
        offer()
    }

    private fun offer() {
        if (released) return
        if (holder.surface.isValid) {
            val frame = holder.surfaceFrame
            engine.offerSurface(token, holder.surface, frame.width(), frame.height())
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = offer()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = offer()
    override fun surfaceDestroyed(holder: SurfaceHolder) = engine.removeSurface(token)
    override fun onResume(owner: LifecycleOwner) = activate()
    override fun onStop(owner: LifecycleOwner) {
        if (!activity.isInPictureInPictureMode) {
            session.releaseRenderHost(this)
            engine.deactivateSurface(token)
            session.detachHost(activity)
        }
    }

    /** PixelCopy scales to a bounded temporary preview; original video rendering stays untouched. */
    internal suspend fun captureFrame(): android.graphics.Bitmap? {
        if (released || !holder.surface.isValid || width <= 0 || height <= 0) return null
        val scale = minOf(1f, 720f / width, 480f / height)
        val bitmap = android.graphics.Bitmap.createBitmap(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            try {
                android.view.PixelCopy.request(this, bitmap, { result ->
                    if (continuation.isActive) {
                        continuation.resume(
                            if (result ==
                                android.view.PixelCopy.SUCCESS
                            ) {
                                bitmap
                            } else {
                                null
                            },
                        )
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (_: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        activity.privacyDisplayController?.run {
            unregister(this@MpvSurfaceView to PrivacyArea.VIDEO)
            unregister(this@MpvSurfaceView to PrivacyArea.NSFW)
            stopObservingIncognito(PrivacyArea.VIDEO, this@MpvSurfaceView)
        }
        holder.removeCallback(this)
        activity.lifecycle.removeObserver(this)
        session.releaseRenderHost(this)
        engine.deactivateSurface(token)
        engine.removeSurface(token)
        session.detachHost(activity)
    }
}
