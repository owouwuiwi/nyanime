package eu.kanade.presentation.browse.store

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import eu.kanade.presentation.browse.anime.components.AnimeExtensionIcon
import eu.kanade.presentation.browse.manga.components.MangaExtensionIcon
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.extensionstore.StoreEntry
import eu.kanade.tachiyomi.data.rin.RinLoader
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installed signed icons work offline; catalogue images also cover RINs without embedded icons. */
@Composable
internal fun RinStoreIcon(entry: StoreEntry, modifier: Modifier) {
    val anime = entry.anime as? AnimeExtension.Installed
    val manga = entry.manga as? MangaExtension.Installed
    val context = LocalContext.current
    val record = entry.rinRecord?.takeIf { anime?.icon == null && manga?.icon == null }
    val localIcon by produceState<ImageBitmap?>(null, record?.file, context) {
        value = record?.let {
            withContext(Dispatchers.IO) {
                (RinLoader.icon(context, it) as? BitmapDrawable)?.bitmap?.asImageBitmap()
            }
        }
    }
    when {
        anime?.icon != null -> AnimeExtensionIcon(anime, modifier)
        manga?.icon != null -> MangaExtensionIcon(manga, modifier)
        localIcon != null -> Image(requireNotNull(localIcon), null, modifier)
        entry.rinCandidate?.iconUrl != null -> AsyncImage(
            model = entry.rinCandidate.iconUrl,
            contentDescription = null,
            modifier = modifier,
            error = painterResource(R.mipmap.ic_default_source),
        )
        else -> Icon(entry.medium.icon(), null, modifier)
    }
}
