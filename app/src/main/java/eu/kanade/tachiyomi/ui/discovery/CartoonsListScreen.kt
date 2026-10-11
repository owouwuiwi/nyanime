package eu.kanade.tachiyomi.ui.discovery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen

/** Deserialization bridge for routes saved by older previews. New routes use SourceHomeListScreen. */
class CartoonsListScreen(private val sectionId: String, private val title: String) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        LaunchedEffect(Unit) {
            if (navigator.lastItem == this@CartoonsListScreen) navigator.replace(DiscoveryTab)
        }
    }
}
