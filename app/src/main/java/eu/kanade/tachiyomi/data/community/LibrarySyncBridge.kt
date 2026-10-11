@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.community

import eu.kanade.tachiyomi.BuildConfig
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

/** SQL triggers capture changes atomically with ordinary library writes, not from a player timer. */
internal class LibrarySyncBridge(
    private val anime: AnimeDatabaseHandler = Injekt.get(),
    private val manga: MangaDatabaseHandler = Injekt.get(),
) {
    suspend fun capture(enabled: Boolean) {
        val capture = if (enabled && (BuildConfig.COMMUNITY_ENABLED || BuildConfig.PERSONAL_SYNC_ENABLED)) 1L else 0L
        anime.await { communitySyncQueries.capture(capture) }
        manga.await { communitySyncQueries.capture(capture) }
    }
    suspend fun videoIds(ref: SyncReference): Pair<Long, Long>? {
        if (ref.manga || ref.itemUrl.isBlank()) return null
        return anime.await {
            val title = communitySyncQueries.findTitle(ref.source, ref.titleUrl).executeAsList().singleOrNull()
            val episode = communitySyncQueries.findItem(
                ref.source,
                ref.titleUrl,
                ref.itemUrl,
            ).executeAsList().singleOrNull()
            if (title != null && episode != null) title to episode else null
        }
    }
    suspend fun itemIds(ref: SyncReference): Pair<Long, Long>? {
        if (!ref.manga) return videoIds(ref)
        if (ref.itemUrl.isBlank()) return null
        return manga.await {
            val title = communitySyncQueries.findTitle(ref.source, ref.titleUrl).executeAsList().singleOrNull()
            val chapter = communitySyncQueries.findItem(
                ref.source,
                ref.titleUrl,
                ref.itemUrl,
            ).executeAsList().singleOrNull()
            if (title != null && chapter != null) title to chapter else null
        }
    }
    suspend fun seed() {
        anime.await(true) {
            communitySyncQueries.seedCategories()
            communitySyncQueries.seedTitles()
            communitySyncQueries.seedItems()
        }
        manga.await(true) {
            communitySyncQueries.seedCategories()
            communitySyncQueries.seedTitles()
            communitySyncQueries.seedItems()
        }
    }
    suspend fun drain(persist: (SyncRecord) -> Unit, revision: (Long) -> SyncRevision) {
        val animeRows = anime.await { communitySyncQueries.drain().executeAsList() }
        for (row in animeRows) {
            persist(
                SyncRecord(
                    SyncReference(
                        false,
                        row.source,
                        row.title_url,
                        row.item_url,
                    ),
                    revision(row.changed_at), row.title, row.item, row.artwork,
                    row.favorite != 0L, row.seen != 0L, row.bookmark != 0L, row.position, row.duration, row.number, row.history_at,
                    decodeCategories(row.categories), row.deleted != 0L, fields(row.fields),
                ),
            )
            anime.await { communitySyncQueries.acknowledge(row.seq) }
        }
        val mangaRows = manga.await { communitySyncQueries.drain().executeAsList() }
        for (row in mangaRows) {
            persist(
                SyncRecord(
                    SyncReference(
                        true,
                        row.source,
                        row.title_url,
                        row.item_url,
                    ),
                    revision(row.changed_at), row.title, row.item, row.artwork,
                    row.favorite != 0L, row.seen != 0L, row.bookmark != 0L, row.position, row.duration, row.number, row.history_at,
                    decodeCategories(row.categories), row.deleted != 0L, fields(row.fields),
                ),
            )
            manga.await { communitySyncQueries.acknowledge(row.seq) }
        }
    }
    private fun decodeCategories(
        value: String,
    ): List<SyncCategory> = if (value.isBlank()) {
        emptyList()
    } else {
        value.split(',').map {
            SyncCategory(it.substringBefore(':'), it.substringAfter(':').lowercase().hexBytes().decodeToString())
        }
    }
    private fun fields(value: String) = if (value.contains("all")) {
        SyncField.entries.toSet()
    } else {
        value.split(',').mapNotNull { name ->
            SyncField.entries.find {
                it.name ==
                    name
            }
        }.toSet()
    }
    suspend fun library(): List<SyncRecord> {
        val revision = SyncRevision(0, 0, "0".repeat(32))
        return anime.await {
            communitySyncQueries.getCommunityTitles().executeAsList().map {
                SyncRecord(
                    SyncReference(false, it.source, it.url),
                    revision,
                    title = it.title,
                    artwork = it.thumbnail_url.orEmpty(),
                    favorite = it.favorite,
                )
            }
        } +
            manga.await {
                communitySyncQueries.getCommunityTitles().executeAsList().map {
                    SyncRecord(
                        SyncReference(true, it.source, it.url),
                        revision,
                        title = it.title,
                        artwork = it.thumbnail_url.orEmpty(),
                        favorite = it.favorite,
                    )
                }
            }
    }

    /** Applying never calls a source or native player; extension loading stays in the existing guarded path. */
    suspend fun apply(record: SyncRecord): Boolean {
        require(record.valid(System.currentTimeMillis()))
        if (!record.ref.titleUrl.startsWith("nyanime:category:")) {
            val present = if (record.ref.manga) {
                Injekt.get<tachiyomi.domain.source.manga.service.MangaSourceManager>().get(record.ref.source) !=
                    null
            } else {
                Injekt.get<tachiyomi.domain.source.anime.service.AnimeSourceManager>().get(record.ref.source) != null
            }
            if (!present) return false
        }
        return if (record.ref.manga) applyManga(record) else applyAnime(record)
    }
    suspend fun candidates(title: SyncRecord): List<SyncRecord> = if (title.ref.manga) {
        manga.await {
            val id = communitySyncQueries.findTitle(title.ref.source, title.ref.titleUrl).executeAsOneOrNull()
            if (id == null) {
                emptyList()
            } else {
                chaptersQueries.getChaptersByMangaId(id, 0L).executeAsList().map {
                    title.copy(ref = title.ref.copy(itemUrl = it.url), item = it.name, number = it.chapter_number)
                }
            }
        }
    } else {
        anime.await {
            val id = communitySyncQueries.findTitle(title.ref.source, title.ref.titleUrl).executeAsOneOrNull()
            if (id == null) {
                emptyList()
            } else {
                episodesQueries.getEpisodesByAnimeId(id).executeAsList().map {
                    title.copy(ref = title.ref.copy(itemUrl = it.url), item = it.name, number = it.episode_number)
                }
            }
        }
    }
    private suspend fun applyAnime(r: SyncRecord): Boolean = anime.await(true) {
        val q = communitySyncQueries
        val fields = r.clocks.keys.ifEmpty { r.edits }
        if (q.findTitle(r.ref.source, r.ref.titleUrl).executeAsList().size > 1) return@await false
        val capture = q.captureEnabled().executeAsOne()
        q.capture(0)
        try {
            if (r.ref.source == 0L && r.ref.titleUrl.startsWith("nyanime:category:")) {
                val uuid = r.ref.titleUrl.substringAfterLast(':')
                val existing = q.findCategoryIdentity(uuid).executeAsOneOrNull()
                if (q.removedCategoryIdentity(uuid).executeAsOneOrNull() != null) return@await true
                if (r.deleted) {
                    q.markRemovedCategoryIdentity(uuid)
                    if (existing !=
                        null
                    ) {
                        categoriesQueries.delete(existing)
                        q.removeCategoryIdentity(existing)
                    }
                    return@await true
                }
                val category = existing ?: q.findCategory(r.title).executeAsOneOrNull() ?: run {
                    categoriesQueries.insert(r.title, r.position, 0)
                    q.findCategory(r.title).executeAsOne()
                }
                q.attachCategoryIdentity(uuid, category)
                q.applyCategory(r.title, r.position, if (r.bookmark) 1 else 0, category)
                return@await true
            }
            q.importTitle(
                r.ref.source,
                r.ref.titleUrl,
                r.title,
                r.artwork,
                r.ref.itemUrl.isEmpty() && r.favorite && !r.deleted,
                System.currentTimeMillis(),
            )
            val parent = q.findTitle(r.ref.source, r.ref.titleUrl).executeAsOne()
            if (r.ref.itemUrl.isEmpty()) {
                q.applyTitle(r.favorite && !r.deleted, r.ref.source, r.ref.titleUrl)
                q.clearCategories(parent)
                r.categories.distinctBy { it.id }.forEach { remote ->
                    if (q.removedCategoryIdentity(remote.id).executeAsOneOrNull() != null) return@forEach
                    val name = remote.name
                    val category =
                        q.findCategoryIdentity(remote.id).executeAsOneOrNull()
                            ?: q.findCategory(name).executeAsOneOrNull()
                            ?: run {
                                categoriesQueries.insert(
                                    name,
                                    categoriesQueries.getCategories().executeAsList().size.toLong(),
                                    0,
                                )
                                q.findCategory(name).executeAsOne()
                            }
                    q.attachCategoryIdentity(remote.id, category)
                    q.attachCategory(parent, category)
                }
            } else {
                if (q.findItem(r.ref.source, r.ref.titleUrl, r.ref.itemUrl).executeAsList().size > 1) return@await false
                q.importItem(parent, r.ref.itemUrl, r.item, r.number)
                q.applyItem(
                    r.seen.takeIf { SyncField.Seen in fields },
                    r.bookmark.takeIf { SyncField.Bookmark in fields },
                    r.position.takeIf { SyncField.Progress in fields },
                    r.duration.takeIf { SyncField.Progress in fields },
                    r.ref.itemUrl,
                    r.ref.source,
                    r.ref.titleUrl,
                )
                if (SyncField.History in
                    fields
                ) {
                    q.setHistory(
                        q.findItem(r.ref.source, r.ref.titleUrl, r.ref.itemUrl).executeAsOne(),
                        Date(r.history),
                    )
                }
            }
            true
        } finally {
            q.restoreFlags()
            q.restoreItemFlags()
            q.capture(capture)
        }
    }
    private suspend fun applyManga(r: SyncRecord): Boolean = manga.await(true) {
        val q = communitySyncQueries
        val fields = r.clocks.keys.ifEmpty { r.edits }
        if (q.findTitle(r.ref.source, r.ref.titleUrl).executeAsList().size > 1) return@await false
        val capture = q.captureEnabled().executeAsOne()
        q.capture(0)
        try {
            if (r.ref.source == 0L && r.ref.titleUrl.startsWith("nyanime:category:")) {
                val uuid = r.ref.titleUrl.substringAfterLast(':')
                val existing = q.findCategoryIdentity(uuid).executeAsOneOrNull()
                if (q.removedCategoryIdentity(uuid).executeAsOneOrNull() != null) return@await true
                if (r.deleted) {
                    q.markRemovedCategoryIdentity(uuid)
                    if (existing !=
                        null
                    ) {
                        categoriesQueries.delete(existing)
                        q.removeCategoryIdentity(existing)
                    }
                    return@await true
                }
                val category = existing ?: q.findCategory(r.title).executeAsOneOrNull() ?: run {
                    categoriesQueries.insert(r.title, r.position, 0)
                    q.findCategory(r.title).executeAsOne()
                }
                q.attachCategoryIdentity(uuid, category)
                q.applyCategory(r.title, r.position, if (r.bookmark) 1 else 0, category)
                return@await true
            }
            q.importTitle(
                r.ref.source,
                r.ref.titleUrl,
                r.title,
                r.artwork,
                r.ref.itemUrl.isEmpty() && r.favorite && !r.deleted,
                System.currentTimeMillis(),
            )
            val parent = q.findTitle(r.ref.source, r.ref.titleUrl).executeAsOne()
            if (r.ref.itemUrl.isEmpty()) {
                q.applyTitle(r.favorite && !r.deleted, r.ref.source, r.ref.titleUrl)
                q.clearCategories(parent)
                r.categories.distinctBy { it.id }.forEach { remote ->
                    if (q.removedCategoryIdentity(remote.id).executeAsOneOrNull() != null) return@forEach
                    val name = remote.name
                    val category =
                        q.findCategoryIdentity(remote.id).executeAsOneOrNull()
                            ?: q.findCategory(name).executeAsOneOrNull()
                            ?: run {
                                categoriesQueries.insert(
                                    name,
                                    categoriesQueries.getCategories().executeAsList().size.toLong(),
                                    0,
                                )
                                q.findCategory(name).executeAsOne()
                            }
                    q.attachCategoryIdentity(remote.id, category)
                    q.attachCategory(parent, category)
                }
            } else {
                if (q.findItem(r.ref.source, r.ref.titleUrl, r.ref.itemUrl).executeAsList().size > 1) return@await false
                q.importItem(parent, r.ref.itemUrl, r.item, r.number)
                q.applyItem(
                    r.seen.takeIf { SyncField.Seen in fields },
                    r.bookmark.takeIf { SyncField.Bookmark in fields },
                    r.position.takeIf { SyncField.Progress in fields },
                    r.ref.itemUrl,
                    r.ref.source,
                    r.ref.titleUrl,
                )
                if (SyncField.History in
                    fields
                ) {
                    q.setHistory(
                        q.findItem(r.ref.source, r.ref.titleUrl, r.ref.itemUrl).executeAsOne(),
                        Date(r.history),
                    )
                }
            }
            true
        } finally {
            q.restoreFlags()
            q.restoreItemFlags()
            q.capture(capture)
        }
    }
}
