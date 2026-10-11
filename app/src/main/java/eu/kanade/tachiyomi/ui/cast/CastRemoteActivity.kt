package eu.kanade.tachiyomi.ui.cast

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.cast.CastController

class CastRemoteActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        if (CastController.get(this).state.value.media?.live != null) {
            eu.kanade.tachiyomi.data.watch.WatchTogetherManager.get(this).present(this)
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val controller = CastController.get(this)
        if (controller.state.value.active &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            controller.adjustVolume(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.05f else -0.05f)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TachiyomiTheme { CastRemoteScreen(onBack = ::finish) } }
    }
}
