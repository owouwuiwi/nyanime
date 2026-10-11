package it.lorelore.nyanime.music

import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.home.HomeScreen

/** Only restores older saved navigation. Music has no entry point or runtime in the app. */
@Keep
class MusicComingSoonScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        LaunchedEffect(navigator) {
            navigator.popUntilRoot()
            if (navigator.lastItem is MusicComingSoonScreen) navigator.replaceAll(HomeScreen)
        }
    }
}
