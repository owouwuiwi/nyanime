package eu.kanade.tachiyomi.ui.cloud

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncManager
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncPreferences
import eu.kanade.tachiyomi.data.cloud.sync.PortablePosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nyanime.telegram.sync.SyncRef
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import tachiyomi.presentation.core.util.collectAsState as collectPreference

/** Optional enrichment of existing resume cards. No client is started for users without sync. */
@Composable
internal fun rememberCloudResume(medium: String, titleId: Long, itemId: Long): List<PortablePosition> {
    val preferences = remember { CloudSyncPreferences() }
    val enabled by preferences.enabled().collectPreference()
    if (!enabled) return emptyList()
    val context = LocalContext.current
    val manager = remember { CloudSyncManager.get(context) }
    val revision by manager.contentRevision.collectAsState()
    val status by manager.state.collectAsState()
    var positions by remember(medium, titleId, itemId) { mutableStateOf<List<PortablePosition>>(emptyList()) }
    LaunchedEffect(medium, titleId, itemId, revision, status.phase) {
        try {
            positions = withContext(Dispatchers.IO) {
                val ref = if (medium == "anime") {
                    val title = Injekt.get<AnimeRepository>().getAnimeById(titleId) ?: return@withContext emptyList()
                    val item = Injekt.get<EpisodeRepository>().getEpisodeById(itemId) ?: return@withContext emptyList()
                    SyncRef("anime-item", title.source, title.url, item.url)
                } else {
                    val title = Injekt.get<MangaRepository>().getMangaById(titleId) ?: return@withContext emptyList()
                    val item = Injekt.get<ChapterRepository>().getChapterById(itemId) ?: return@withContext emptyList()
                    SyncRef("manga-item", title.source, title.url, item.url)
                }
                manager.positions(ref)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            positions = emptyList() // The normal local resume remains available.
        }
    }
    return positions
}

@Composable
internal fun CloudResumeMenu(positions: List<PortablePosition>, manga: Boolean, onSelect: (Long) -> Unit) {
    positions.distinctBy { it.device to it.position }.take(8).forEach { position ->
        val point = if (manga) {
            stringResource(R.string.cloud_sync_page, position.position + 1)
        } else {
            val seconds = position.position / 1000
            String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.cloud_sync_resume_device, position.deviceName, point)) },
            leadingIcon = { Icon(Icons.Outlined.Devices, null) },
            onClick = { onSelect(position.position) },
        )
    }
}
