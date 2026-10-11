package eu.kanade.tachiyomi.data.community

import android.content.Context
import android.graphics.Bitmap
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import tachiyomi.domain.entries.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.entries.manga.interactor.GetMangaByUrlAndSourceId
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.net.URI

/** Public cards never expose an extension's image URL or authentication query parameters. */
internal class PublicArtwork(private val context: Context) {
    suspend fun prepare(
        title: PublicTitle,
        library: List<SyncRecord>,
        upload: suspend (ByteArray) -> String,
    ): PublicTitle {
        if (title.artwork.isBlank()) return title
        if (runCatching {
                val uri = URI(title.artwork)
                BlossomImages.hosts.any { URI(it).host == uri.host } &&
                    uri.path.substringAfterLast('/').substringBefore('.').matches(Regex("[a-f0-9]{64}"))
            }.getOrDefault(false)
        ) {
            return title
        }
        val local =
            library.firstOrNull { it.title == title.title && it.ref.manga == title.manga }
                ?: return title.copy(artwork = "")
        val model: Any = if (local.ref.manga) {
            Injekt.get<GetMangaByUrlAndSourceId>().await(local.ref.titleUrl, local.ref.source)
                ?: return title.copy(artwork = "")
        } else {
            Injekt.get<GetAnimeByUrlAndSourceId>().await(local.ref.titleUrl, local.ref.source)
                ?: return title.copy(artwork = "")
        }
        val image = context.imageLoader.execute(ImageRequest.Builder(context).data(model).size(720).build()).image
        val bitmap = image?.asDrawable(context.resources)?.getBitmapOrNull() ?: return title.copy(artwork = "")
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
            it.toByteArray()
        }
        return title.copy(artwork = upload(bytes))
    }
}
