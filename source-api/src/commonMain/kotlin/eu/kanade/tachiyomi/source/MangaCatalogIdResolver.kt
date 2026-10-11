package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SManga

/** Optional capability for sources that can resolve an external catalog ID without title search. */
interface MangaCatalogIdResolver {
    /** Return only an entry whose identity is verified against [namespace] and [id]. */
    suspend fun findMangaByCatalogId(namespace: String, id: Long): SManga?
}

/** A source resolves only links it recognizes; callers verify catalog identity from the result. */
interface MangaCatalogLinkResolver {
    suspend fun findMangaByCatalogLink(url: String): SManga?
}
