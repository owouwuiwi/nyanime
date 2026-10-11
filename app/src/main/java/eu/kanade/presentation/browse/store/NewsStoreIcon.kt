package eu.kanade.presentation.browse.store

import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.extensionstore.StoreEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun NewsStoreIcon(entry: StoreEntry, modifier: Modifier) {
    val context = LocalContext.current
    val iconUrl = entry.newsCandidate?.resources?.iconUrl ?: entry.addonCandidate?.resources?.iconUrl
    if (!entry.installed && iconUrl != null) {
        AsyncImage(model = iconUrl, contentDescription = null, modifier = modifier)
        return
    }
    val bitmap by produceState<ImageBitmap?>(null, entry.packageName, entry.version) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(entry.packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, null, modifier) } ?: Icon(entry.medium.icon(), null, modifier)
}
