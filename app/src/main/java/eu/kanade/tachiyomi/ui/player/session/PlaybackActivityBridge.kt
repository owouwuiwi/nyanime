package eu.kanade.tachiyomi.ui.player.session

import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Activity lifecycle affects rendering and PiP, never the lifetime of the service-owned decoder. */
internal object PlaybackActivityBridge {
    fun disablePip(activity: BaseActivity) {
        if (android.os.Build.VERSION.SDK_INT < 26 ||
            !activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            return
        }
        val params = android.app.PictureInPictureParams.Builder().setActions(emptyList())
        if (android.os.Build.VERSION.SDK_INT >= 31) params.setAutoEnterEnabled(false)
        runCatching { activity.setPictureInPictureParams(params.build()) }
    }

    fun prepareNavigation(activity: BaseActivity, intent: android.content.Intent) {
        if (intent.component?.packageName == activity.packageName || intent.`package` == activity.packageName) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        }
    }
    fun leave(activity: BaseActivity) {
        val session = PlaybackSessions.current.value
        if (session == null || session.hostActivity !== activity || !session.canEnterPictureInPicture) {
            disablePip(activity)
            return
        }
        if (session.snapshot.value.paused) return
        val preferences = Injekt.get<PlayerPreferences>()
        if (preferences.enablePip().get() &&
            preferences.pipOnExit().get() &&
            !activity.isChangingConfigurations &&
            android.os.Build.VERSION.SDK_INT < 31
        ) {
            // Android 12+ already owns automatic PiP entry. A second manual entry
            // races its window resize; older Android versions still require this call.
            session.enterPictureInPictureMode()
        }
    }

    fun pip(activity: BaseActivity, enabled: Boolean) {
        val session = PlaybackSessions.current.value ?: return
        if (session.hostActivity === activity) session.pipChanged(enabled)
    }

    fun key(activity: BaseActivity, code: Int, event: android.view.KeyEvent?): Boolean {
        val session = PlaybackSessions.current.value ?: return false
        if (session.hostActivity !== activity ||
            session.presentation.value != PlaybackPresentation.Fullscreen
        ) {
            return false
        }
        return session.onKeyDown(code, event)
    }
    fun keyUp(activity: BaseActivity, code: Int, event: android.view.KeyEvent?): Boolean {
        val session = PlaybackSessions.current.value ?: return false
        if (session.hostActivity !== activity ||
            session.presentation.value != PlaybackPresentation.Fullscreen ||
            event == null
        ) {
            return false
        }
        return session.onKeyUp(code, event)
    }
}
