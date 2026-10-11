package eu.kanade.tachiyomi.ui.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.tv.TvServices
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** The same TV facets are accessible from advanced search, without invoking an anime source. */
class TvBrowseScreen : Screen() {
    @Composable override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel {
            val services = Injekt.get<TvServices>()
            TvHomeModel(services.repository, services.adultContent)
        }
        Scaffold(topBar = { behavior ->
            AppBar(title = stringResource(R.string.tv_title), navigateUp = navigator::pop, scrollBehavior = behavior)
        }) { padding ->
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
                TvHomeContent(model, true, { TvActions.play(context, it) }, { TvActions.share(context, it) }, null)
            }
        }
    }
}
