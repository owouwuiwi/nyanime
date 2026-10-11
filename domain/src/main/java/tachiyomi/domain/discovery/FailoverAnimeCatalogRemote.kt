package tachiyomi.domain.discovery

import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Provider routing is explicit: numerically equal catalogue IDs are never interchangeable. */
class FailoverAnimeCatalogRemote(
    private val primary: AnimeCatalogRemote,
    private val fallback: AnimeCatalogRemote,
) : AnimeCatalogRemote {
    override suspend fun fetch(request: CatalogRequest): CatalogPage {
        if (request.provider == "kitsu") return fallback.fetch(request)
        require(request.provider == null || request.provider == "anilist")
        return try {
            primary.fetch(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Explicit pages remain on the same provider; never mix pagination after recovery.
            if (request.provider != null) throw e
            fallback.fetch(request).let { page ->
                page.copy(notice = page.notice ?: "Catalogo alternativo Kitsu · AniList non disponibile")
            }
        }
    }

    override suspend fun details(id: CatalogId): CatalogAnime = when (id.provider) {
        "anilist" -> primary.details(id)
        "kitsu" -> fallback.details(id)
        else -> throw IllegalArgumentException("Catalogo non supportato: ${id.provider}")
    }
}
