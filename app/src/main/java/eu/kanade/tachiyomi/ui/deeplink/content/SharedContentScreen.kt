package eu.kanade.tachiyomi.ui.deeplink.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.cast.CastController
import eu.kanade.tachiyomi.data.share.ContentLinks
import eu.kanade.tachiyomi.data.share.SharedMedium
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.PlayerLifecycle
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class SharedContentScreen(encoded: String) : Screen() {
    // Keep a malformed external intent from inflating the saved navigation state.
    private val encoded = encoded.take(ContentLinks.MAX_LINK_LENGTH + 4097)

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val liveLink = androidx.compose.runtime.remember(encoded) {
            ContentLinks.decode(encoded)?.takeIf {
                it.medium ==
                    SharedMedium.TV
            }
        }
        if (liveLink != null) {
            eu.kanade.tachiyomi.ui.tv.TvLinkContent(liveLink) { navigator.pop() }
            return
        }
        val model = rememberScreenModel { SharedContentScreenModel(encoded) }
        val state by model.state.collectAsState()
        var handled by rememberSaveable { mutableStateOf(false) }
        val resolved = state as? SharedContentScreenModel.State.Resolved
        val cast by CastController.get(context).state.collectAsState()
        val room by WatchTogetherManager.get(context).controller.state.collectAsState()
        val occupied = resolved?.itemId != null && (cast.active || cast.connecting || room.active)

        fun openEntry(medium: SharedMedium, id: Long) {
            navigator.replace(if (medium == SharedMedium.ANIME) AnimeScreen(id, true) else MangaScreen(id, true))
        }

        LaunchedEffect(resolved, occupied) {
            if (resolved != null && !handled && !occupied) {
                if (resolved.itemId != null && resolved.link.medium == SharedMedium.ANIME) {
                    if (eu.kanade.tachiyomi.ui.player.session.PlaybackSessions.current.value ==
                        null
                    ) {
                        PlayerLifecycle.awaitReleased()
                    }
                }
                handled = true
                openEntry(resolved.link.medium, resolved.entryId)
                resolved.itemId?.let { item ->
                    val intent = when (resolved.link.medium) {
                        SharedMedium.TV -> error("TV links use the live resolver")
                        SharedMedium.ANIME -> PlayerActivity.newIntent(
                            context,
                            resolved.entryId,
                            item,
                            startPositionMs = resolved.link.positionMs ?: 0,
                        )
                        SharedMedium.MANGA -> ReaderActivity.newIntent(
                            context,
                            resolved.entryId,
                            item,
                            startPage = resolved.link.page ?: 1,
                        )
                    }
                    context.startActivity(intent)
                }
            }
        }
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(AYMR.strings.content_open_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                when (val current = state) {
                    SharedContentScreenModel.State.Loading -> {
                        CircularProgressIndicator()
                        Text(stringResource(AYMR.strings.content_open_loading), textAlign = TextAlign.Center)
                        TextButton(onClick = { navigator.pop() }) { Text(stringResource(MR.strings.action_cancel)) }
                    }
                    is SharedContentScreenModel.State.Failed -> {
                        val message = when (current.failure) {
                            SharedContentScreenModel.Failure.INVALID -> AYMR.strings.content_open_invalid
                            SharedContentScreenModel.Failure.SOURCE_MISSING -> AYMR.strings.content_open_source_missing
                            SharedContentScreenModel.Failure.ITEM_MISSING -> AYMR.strings.content_open_item_missing
                            SharedContentScreenModel.Failure.NETWORK -> AYMR.strings.content_open_network
                        }
                        Text(
                            stringResource(message),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        current.link?.sourceName?.let {
                            Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                        }
                        if (current.failure != SharedContentScreenModel.Failure.INVALID) {
                            Button(onClick = model::retry) { Text(stringResource(AYMR.strings.content_open_retry)) }
                        }
                        val entry = current.entryId
                        val link = current.link
                        if (entry != null && link != null) {
                            TextButton(onClick = { openEntry(link.medium, entry) }) {
                                Text(stringResource(AYMR.strings.content_share_entry))
                            }
                        }
                    }
                    is SharedContentScreenModel.State.Resolved -> if (occupied) {
                        Text(
                            current.link.title,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                        )
                        Text(stringResource(AYMR.strings.content_open_session_active), textAlign = TextAlign.Center)
                        Button(onClick = { openEntry(current.link.medium, current.entryId) }) {
                            Text(stringResource(AYMR.strings.content_share_entry))
                        }
                    } else {
                        CircularProgressIndicator()
                        Text(stringResource(AYMR.strings.content_open_loading), textAlign = TextAlign.Center)
                        TextButton(onClick = { navigator.pop() }) { Text(stringResource(MR.strings.action_cancel)) }
                    }
                }
            }
        }
    }
}
