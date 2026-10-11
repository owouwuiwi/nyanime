package it.lorelore.nyanime.presentation.themes

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.openInBrowser
import it.lorelore.nyanime.themes.AnimeTheme
import it.lorelore.nyanime.themes.ThemeVideo
import it.lorelore.nyanime.themes.ThemeVideoKind
import java.util.Locale

/** Metadata remains native; no embedded search or extraction engine is loaded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeVideoSheet(
    song: AnimeTheme,
    variant: ThemeVideoKind,
    animeTitle: String,
    network: Boolean,
    onDismiss: () -> Unit,
    onSelect: (ThemeVideo) -> Unit,
) {
    val context = LocalContext.current
    val linked = song.videos.filter { it.kind == variant || it.kind == ThemeVideoKind.UNKNOWN }
        .distinctBy { it.id }
    var query by remember(song.key, variant) {
        mutableStateOf(
            listOf(
                animeTitle,
                song.title,
                song.artists.joinToString(" "),
                if (variant == ThemeVideoKind.SEQUENCE) "creditless ${song.label}" else "full song",
            )
                .filter { it.isNotBlank() }.joinToString(" ").take(512),
        )
    }
    fun search() {
        if (!network || query.isBlank()) return
        val uri = Uri.Builder().scheme("https").authority("www.youtube.com").path("results")
            .appendQueryParameter("search_query", query.trim()).build()
        context.openInBrowser(uri.toString())
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 680.dp).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("heading") {
                Text(song.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.anime_themes_external_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(linked, key = { it.id }) { video ->
                VideoCandidateRow(
                    video.copy(
                        spoiler = video.spoiler ||
                            song.versions.any {
                                it.spoiler
                            },
                    ),
                    network,
                    onSelect,
                )
            }
            item("search") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        query,
                        { query = it.take(512) },
                        Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.anime_themes_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                    )
                    Button(onClick = ::search, enabled = network && query.isNotBlank()) {
                        Text(stringResource(R.string.anime_themes_external_search))
                    }
                    if (!network) Text(stringResource(R.string.anime_themes_offline))
                }
            }
        }
    }
}

@Composable
private fun VideoCandidateRow(video: ThemeVideo, enabled: Boolean, onSelect: (ThemeVideo) -> Unit) {
    var revealed by remember(video.id) { mutableStateOf(false) }
    val hidden = video.spoiler && !revealed
    Surface(onClick = {
        onSelect(video)
    }, enabled = enabled, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeThumbnail(video.id, hidden, enabled, Modifier.size(width = 96.dp, height = 54.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        video.title.ifBlank {
                            stringResource(R.string.anime_themes_choose)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val detail =
                        listOfNotNull(
                            video.channel.takeIf(String::isNotBlank),
                            video.seconds?.takeIf { it > 0 }?.let {
                                String.format(
                                    Locale.ROOT,
                                    "%d:%02d",
                                    it / 60,
                                    it % 60,
                                )
                            },
                        )
                    Text(
                        detail.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hidden) {
                Text(stringResource(R.string.anime_themes_spoiler), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { revealed = true }) { Text(stringResource(R.string.anime_themes_reveal)) }
            }
        }
    }
}
