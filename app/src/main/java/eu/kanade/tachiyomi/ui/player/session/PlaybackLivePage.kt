package eu.kanade.tachiyomi.ui.player.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.tv.TvSelection
import eu.kanade.tachiyomi.ui.player.PlayerSession
import nyanime.tv.api.TvCatalogueRequest
import nyanime.tv.api.TvChannel

@Composable
internal fun PlaybackLivePage(
    owner: PlayerSession,
    runtime: LivePlayback,
    video: PlaybackRect,
    wide: Boolean,
    visibility: () -> Float,
) {
    val state by runtime.session.state.collectAsState()
    var channels by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    val density = LocalDensity.current
    LaunchedEffect(runtime) {
        channels = runtime.services.registry.state.value.values.flatMap { registration ->
            val selected = runtime.services.personal.read().sources[registration.source.id.toString()]
                ?: TvSelection(registration.source.capabilities.initialCountry)
            runtime.services.repository.cachedCatalogue(
                registration.source.id,
                TvCatalogueRequest(selected.country, selected.language, selected.category),
            )
                .data?.channels.orEmpty()
        }.filter {
            runtime.services.adultContent.value || !it.adult
        }.distinctBy { it.ref }.sortedBy { it.name.lowercase() }
    }
    val modifier = if (wide) {
        Modifier.padding(
            start = with(density) {
                video.width.toDp()
            },
            top = with(density) { video.y.toDp() },
        ).fillMaxSize()
    } else {
        Modifier.padding(top = with(density) { (video.y + video.height).toDp() }).fillMaxSize()
    }
    LazyColumn(
        modifier.playbackVisibility(visibility),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.media?.channel?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.tv_live), color = MaterialTheme.colorScheme.primary)
                state.media?.channel?.feedName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item { Text(stringResource(R.string.tv_channels), style = MaterialTheme.typography.titleLarge) }
        item {
            OutlinedTextField(
                query,
                { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(stringResource(R.string.playback_find_channel))
                },
                shape = RoundedCornerShape(16.dp),
            )
        }
        items(
            channels.filter {
                it.name.contains(query, ignoreCase = true)
            },
            key = { "${it.ref.sourceId}:${it.ref.id}" },
        ) { channel ->
            Surface(
                onClick = {
                    owner.openLive(channel.ref)
                },
                shape = RoundedCornerShape(16.dp),
                color = if (channel.ref ==
                    state.reference
                ) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(channel.name, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
