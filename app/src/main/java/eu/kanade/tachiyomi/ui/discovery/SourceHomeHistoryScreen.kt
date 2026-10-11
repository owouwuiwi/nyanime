package eu.kanade.tachiyomi.ui.discovery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeServices
import eu.kanade.tachiyomi.ui.history.HistoriesTab
import eu.kanade.tachiyomi.ui.history.anime.AnimeHistoryScreenModel
import eu.kanade.tachiyomi.ui.history.anime.animeHistoryTab
import kotlinx.coroutines.flow.map
import tachiyomi.domain.discovery.SourceHomeGroupAccess
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceHomeHistoryScreen(private val homeKey: String) : Screen() {
    @Composable
    override fun Content() {
        val services = remember { Injekt.get<ExtensionHomeServices>() }
        val accessFlow = remember(homeKey) { services.observeGroup(homeKey) }
        val access by accessFlow.collectAsState(SourceHomeGroupAccess(loading = true))
        val model = rememberScreenModel {
            AnimeHistoryScreenModel(sourceIds = accessFlow.map { it.group?.sourceIds.orEmpty() })
        }
        val query by model.query.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val availability = DiscoveryHomeAvailability.from(access)
        LaunchedEffect(availability) {
            if (availability.shouldLeaveSourcePage(navigator.lastItem == this@SourceHomeHistoryScreen)) navigator.pop()
        }
        val group = access.group
        if (access.loading || group == null) return
        val tab = animeHistoryTab(LocalContext.current, fromMore = true, screenModel = model, globalHistory = false)
        Scaffold(
            modifier = androidx.compose.ui.Modifier.privacyRegion(
                eu.kanade.tachiyomi.ui.privacy.PrivacyArea.HISTORY,
            ),
            topBar = {
                SearchToolbar(
                    titleContent = { AppBarTitle("Cronologia · ${group.title}") },
                    searchEnabled = true,
                    searchQuery = query,
                    onChangeSearchQuery = model::search,
                    navigateUp = { navigator.pop() },
                    actions = {
                        TextButton(onClick = { navigator.push(HistoriesTab) }) { Text("Tutta la cronologia") }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding)) { tab.content(PaddingValues(), model.snackbarHostState) }
        }
    }
}
