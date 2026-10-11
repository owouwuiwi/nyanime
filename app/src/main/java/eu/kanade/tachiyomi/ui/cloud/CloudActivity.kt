package eu.kanade.tachiyomi.ui.cloud

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity

class CloudActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CloudMotionProvider {
                TachiyomiTheme {
                    Navigator(CloudScreen()) { navigator ->
                        BackHandler { if (navigator.canPop) navigator.pop() else finish() }
                        DefaultNavigatorScreenTransition(navigator)
                    }
                }
            }
        }
    }
    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, CloudActivity::class.java))
        }
    }
}
