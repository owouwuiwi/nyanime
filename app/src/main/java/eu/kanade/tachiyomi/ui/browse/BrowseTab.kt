package eu.kanade.tachiyomi.ui.browse

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.browse.store.ExtensionStoreScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.StoreMedium
import eu.kanade.tachiyomi.ui.home.LocalSelectHomeTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.more.MoreTab
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

data object BrowseTab : Tab {
    override val options: TabOptions
        @Composable get() {
            val selected = LocalTabNavigator.current.current is BrowseTab
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter)
            return TabOptions(3u, stringResource(R.string.store_title), rememberAnimatedVectorPainter(image, selected))
        }

    private val requests = Channel<StoreMedium>(1, BufferOverflow.DROP_OLDEST)
    fun showExtension() {
        requests.trySend(StoreMedium.MANGA)
    }
    fun showAnimeExtension() {
        requests.trySend(StoreMedium.ANIME)
    }

    @Composable
    override fun Content() {
        var medium by rememberSaveable { mutableStateOf(StoreMedium.ANIME) }
        val selectTab = LocalSelectHomeTab.current
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        ExtensionStoreScreen(medium, onMedium = { medium = it }, onBack = {
            if (navigator.lastItem == BrowseTab && navigator.canPop) navigator.pop() else selectTab(MoreTab)
        })
        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
            requests.receiveAsFlow().collect { medium = it }
        }
    }
}
