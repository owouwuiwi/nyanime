package eu.kanade.tachiyomi.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cast.CastController
import eu.kanade.tachiyomi.data.share.ContentLink
import eu.kanade.tachiyomi.data.tv.TvServices
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager
import kotlinx.coroutines.flow.first
import nyanime.tv.api.TvChannelRef
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun TvLinkContent(link: ContentLink, onBack: () -> Unit) {
    val context = LocalContext.current
    val services = remember { Injekt.get<TvServices>() }
    val registry by services.registry.state.collectAsState()
    val ready by services.initialized.collectAsState()
    val cast by CastController.get(context).state.collectAsState()
    val room by WatchTogetherManager.get(context).controller.state.collectAsState()
    val occupied = cast.active || cast.connecting || room.active
    var opened by rememberSaveable(link) { mutableStateOf(false) }
    var failed by rememberSaveable(link) { mutableStateOf(false) }
    LaunchedEffect(ready, registry, occupied) {
        if (!ready || opened || occupied || registry[link.sourceId] == null) return@LaunchedEffect
        try {
            context.startActivity(TvPlayerActivity.intent(context, TvChannelRef(link.sourceId, link.entryUrl)))
            opened = true
            onBack()
        } catch (_: Exception) {
            failed = true
        }
    }
    Scaffold(topBar = { behavior ->
        AppBar(title = stringResource(R.string.tv_title), navigateUp = onBack, scrollBehavior = behavior)
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Text(link.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            when {
                !ready -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.tv_connecting))
                }
                occupied -> Text(
                    tachiyomi.presentation.core.i18n.stringResource(
                        tachiyomi.i18n.aniyomi.AYMR.strings.content_open_session_active,
                    ),
                    textAlign = TextAlign.Center,
                )
                registry[link.sourceId] == null -> Text(
                    stringResource(R.string.tv_missing_source),
                    textAlign = TextAlign.Center,
                )
                failed -> Text(stringResource(R.string.tv_player_unavailable), textAlign = TextAlign.Center)
            }
            TextButton(onClick = onBack) { Text(stringResource(R.string.tv_back)) }
        }
    }
}
