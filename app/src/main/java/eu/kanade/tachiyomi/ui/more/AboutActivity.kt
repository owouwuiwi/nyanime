package eu.kanade.tachiyomi.ui.more

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent

/** Hosts the same Info screen when another mode needs it, preserving the caller's back stack. */
class AboutActivity : BaseActivity() {
    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setComposeContent {
            Navigator(AboutScreen) { navigator ->
                val back: () -> Unit = { if (navigator.canPop) navigator.pop() else finish() }
                BackHandler(onBack = back)
                CompositionLocalProvider(LocalBackPress provides back) {
                    DefaultNavigatorScreenTransition(navigator = navigator)
                }
            }
        }
    }
}
