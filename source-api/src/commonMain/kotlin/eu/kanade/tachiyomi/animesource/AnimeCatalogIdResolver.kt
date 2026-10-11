package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.SAnime

/** Optional source capability for verified lookup by a catalog ID. */
interface AnimeCatalogIdResolver {
    suspend fun findAnimeByCatalogId(namespace: String, id: Long): SAnime?
}

/** Direct manga links published by an anime source, without host-specific parsing in the app. */
interface RelatedMangaLinks {
    suspend fun relatedMangaLinks(animeUrl: String): List<String>
}
