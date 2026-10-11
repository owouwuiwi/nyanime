package eu.kanade.tachiyomi.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.UpdateVersionCard
import eu.kanade.presentation.more.UpdateVersionNotes
import eu.kanade.presentation.more.parseUpdateArchive
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.GITHUB_REPO
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold

class UpdateHistoryScreen(private val focusVersion: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val back = LocalBackPress.current
        var notes by remember { mutableStateOf<List<UpdateVersionNotes>?>(null) }
        var initialPositionApplied by rememberSaveable { mutableStateOf(false) }
        val listState = rememberLazyListState()
        LaunchedEffect(Unit) {
            notes = withContext(Dispatchers.IO) {
                context.resources.openRawResource(R.raw.nyanime_update_history).bufferedReader().use {
                    parseUpdateArchive(it.readText())
                }
            }
            if (!initialPositionApplied) {
                val index = notes.orEmpty().indexOfFirst { it.version == focusVersion }
                if (index > 0) listState.scrollToItem(index + 1)
                initialPositionApplied = true
            }
        }
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(R.string.app_update_history_title),
                    navigateUp = back?.let { it::invoke },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = { context.openInBrowser("https://github.com/$GITHUB_REPO/releases") }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.app_update_github))
                        }
                    },
                )
            },
        ) { padding ->
            // Do not measure a one-item placeholder list: it would clamp a restored scroll position.
            if (notes == null) {
                Text(
                    stringResource(R.string.app_update_history_description),
                    Modifier.padding(padding).padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item(key = "intro") {
                        Text(
                            stringResource(R.string.app_update_history_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    itemsIndexed(notes.orEmpty(), key = { _, note -> note.key }) { index, note ->
                        UpdateVersionCard(
                            note,
                            initiallyExpanded = note.version == focusVersion || (focusVersion == null && index == 0),
                            last = index == notes.orEmpty().lastIndex,
                        )
                    }
                    if (notes != null) {
                        item(key = "end") {
                            Text(
                                stringResource(R.string.app_update_archive_end),
                                Modifier.padding(start = 20.dp, top = 8.dp, bottom = 16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
