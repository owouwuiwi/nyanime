package eu.kanade.presentation.more.settings.screen.about

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import nyanime.secretgame.SecretGameContent

/** The app owns navigation; game state and implementation belong to the feature. */
object SecretGameScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        SecretGameContent(onBack = { navigator.pop() })
    }
}
