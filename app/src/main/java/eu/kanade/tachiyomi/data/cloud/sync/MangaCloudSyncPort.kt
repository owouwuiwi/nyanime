package eu.kanade.tachiyomi.data.cloud.sync

import eu.kanade.domain.entries.manga.model.toSManga
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.backupMangaTrackMapper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import nyanime.telegram.sync.SyncActiveContent
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.syncJson
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.category.manga.repository.MangaCategoryRepository
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.MangaUpdate
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.track.manga.repository.MangaTrackRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

/** All references come from source contracts. This adapter never searches by a title name. */
internal class MangaCloudSyncPort(
    private val store: CloudSyncStore,
    private val active: SyncActiveContent,
    deviceName: () -> String,
    private val now: () -> Long,
    private val historyRecovery: CloudSyncHistoryRecovery,
    private val database: MangaDatabaseHandler = Injekt.get(),
    private val titles: MangaRepository = Injekt.get(),
    private val items: ChapterRepository = Injekt.get(),
    private val categories: MangaCategoryRepository = Injekt.get(),
    private val tracks: MangaTrackRepository = Injekt.get(),
) : CloudSyncDomainPort {
    private var capturedAt: Long? = null
    private fun captureTime(): Long = capturedAt ?: now()
    private val positions = SyncPositionCapture(store, deviceName, ::captureTime)
    private val privacy: eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState = Injekt.get()

    override suspend fun pendingCount() = database.await { cloudSyncQueries.pendingCount().executeAsOne() }

    override suspend fun configure(enabled: Boolean, incognito: Boolean, excludedSources: Set<Long>) {
        database.await(true) {
            cloudSyncQueries.configure(if (enabled) 1 else 0, if (incognito) 1 else 0)
            cloudSyncQueries.clearExcludedSources()
            excludedSources.forEach { cloudSyncQueries.excludeSource(it) }
        }
    }

    override suspend fun seed() {
        categories.getAllMangaCategories().filterNot {
            it.isSystemCategory
        }.forEach { captureCategory(it.id, initial = true) }
        val values = database.awaitList { cloudSyncQueries.seedReferences() }.mapNotNull {
            titles.getMangaByUrlAndSourceId(it.url, it.source)
        }
        values.forEach { title ->
            if (title.source == 0L || privacy.await(title.source)) return@forEach
            captureTitle(title.source, title.url, initial = true)
            items.getChapterByMangaId(title.id).forEach { value ->
                if (value.read || value.bookmark || value.lastPageRead > 0) {
                    captureItem(title.source, title.url, value.url, initial = true)
                }
            }
            captureMembership(title.source, title.url)
            captureSubscription(title.source, title.url)
            database.awaitList { historyQueries.getHistoryByMangaId(title.id) }.forEach { history ->
                val entry = items.getChapterById(history.chapter_id) ?: return@forEach
                captureHistory(title.source, title.url, entry.url)
            }
            tracks.getTracksByMangaId(title.id).forEach { captureTracking(title.source, title.url, it.trackerId) }
        }
    }

    override suspend fun capture(limit: Long): Int = database.await(true) {
        val pending = cloudSyncQueries.pending(limit, syncDirtyMapper).executeAsList()
        pending.forEach { row ->
            if (row.kind != "category" && (row.source == 0L || privacy.await(row.source))) {
                cloudSyncQueries.acknowledge(row.revision)
                return@forEach
            }
            capturedAt = (now() - (System.currentTimeMillis() - row.changedAt).coerceAtLeast(0)).coerceAtLeast(0)
            try {
                when (row.kind) {
                    "title" -> {
                        val ref = SyncRef("manga-title", row.source, row.title)
                        if (row.removed == 1L) {
                            if (store.record(ref) != null) store.capture(ref, removed, captureTime())
                        } else {
                            captureTitle(row.source, row.title)
                        }
                    }
                    "item" -> captureItem(row.source, row.title, row.item)
                    "category" -> captureCategory(row.item.toLong(), deleted = row.removed == 1L)
                    "membership" -> captureMembership(row.source, row.title)
                    "history" -> captureHistory(row.source, row.title, row.item, row.removed == 1L)
                    "tracking" -> captureTracking(row.source, row.title, row.item.toLong(), row.removed == 1L)
                    "subscription" -> captureSubscription(row.source, row.title, row.removed == 1L)
                    else -> return@await pending.indexOf(row)
                }
                cloudSyncQueries.acknowledge(row.revision)
            } finally {
                capturedAt = null
            }
        }
        pending.size
    }

    private suspend fun captureTitle(source: Long, url: String, initial: Boolean = false) {
        if (source == 0L || privacy.await(source)) return
        val value = titles.getMangaByUrlAndSourceId(url, source) ?: return
        val ref = SyncRef("manga-title", source, url)
        val data = PortableTitle.from(value)
        val fields = data.fields().let { if (initial && !value.favorite) it - "favorite" else it }
        val readded = value.favorite && store.baseline(ref, "favorite") == JsonPrimitive(false)
        val existence = if (store.record(ref)?.deleted == true && !readded) emptyMap() else present
        store.capture(ref, fields + existence, captureTime())
    }

    private suspend fun captureItem(source: Long, title: String, url: String, initial: Boolean = false) {
        val parent = titles.getMangaByUrlAndSourceId(title, source) ?: return
        val known = store.record(SyncRef("manga-title", source, title))
        if (known == null) captureTitle(source, title, initial = true)
        val value = items.getChapterByUrlAndMangaId(url, parent.id) ?: return
        val ref = SyncRef("manga-item", source, title, url)
        val fields = PortableItem.from(value).fields().let { data ->
            if (initial) {
                data -
                    (if (!value.read) setOf("completed") else emptySet()) -
                    (if (!value.bookmark) setOf("bookmark") else emptySet())
            } else {
                data
            }
        }
        store.capture(
            ref,
            fields +
                present +
                positions.fields(ref, value.lastPageRead, 0),
            captureTime(),
        )
    }

    private suspend fun categoryKey(id: Long, initial: Boolean = false): String = database.await(true) {
        cloudSyncQueries.categoryKey(id).executeAsOneOrNull() ?: run {
            val category = categories.getMangaCategory(id) ?: error("Category missing")
            val sameName = categories.getAllMangaCategories().count {
                it.name.trim().equals(category.name.trim(), true)
            }
            val shared = if (initial &&
                sameName == 1
            ) {
                initialCategoryKey("manga", category.name)
            } else {
                UUID.randomUUID().toString()
            }
            cloudSyncQueries.bindCategory(id, shared)
            shared
        }
    }

    private suspend fun captureCategory(id: Long, initial: Boolean = false, deleted: Boolean = false) {
        if (id <= 0) return
        if (deleted) {
            val key = database.await { cloudSyncQueries.categoryKey(id).executeAsOneOrNull() } ?: return
            store.capture(SyncRef("manga-category", title = key), removed, captureTime())
            return
        }
        val value = categories.getMangaCategory(id) ?: return
        val ref = SyncRef("manga-category", title = categoryKey(id, initial))
        val fields = syncJson.encodeToJsonElement(
            PortableCategory(value.name, value.order, value.flags, value.hidden),
        ) as JsonObject
        store.capture(ref, fields + present, captureTime())
    }

    private suspend fun captureMembership(source: Long, url: String) {
        val value = titles.getMangaByUrlAndSourceId(url, source) ?: return
        val keys = categories.getCategoriesByMangaId(value.id).filterNot { it.isSystemCategory }.map { category ->
            captureCategory(category.id)
            categoryKey(category.id)
        }
        val ref = SyncRef("manga-membership", source, url)
        val selected = keys.map { "category/$it" }.toSet()
        val observed = store.baselineFields(ref).filter { it.startsWith("category/") }
        store.capture(
            ref,
            (selected + observed).associateWith {
                JsonPrimitive(it in selected)
            } +
                present,
            captureTime(),
        )
    }

    private suspend fun captureHistory(source: Long, title: String, url: String, deleted: Boolean = false) {
        val ref = SyncRef("manga-history", source, title, url)
        if (deleted) {
            store.capture(ref, removed, captureTime())
            return
        }
        val parent = titles.getMangaByUrlAndSourceId(title, source) ?: return
        val entry = items.getChapterByUrlAndMangaId(url, parent.id) ?: return
        val history = database.awaitOneOrNull { cloudSyncQueries.historyEntry(entry.id) } ?: return
        if (store.record(SyncRef("manga-title", source, title)) == null) captureTitle(source, title, initial = true)
        if (store.record(SyncRef("manga-item", source, title, url)) == null) {
            captureItem(source, title, url, initial = true)
        }
        store.capture(ref, mapOf("at" to JsonPrimitive(history.last_read?.time ?: 0L)) + present, captureTime())
    }

    override suspend fun repairHistoryMetadata(refs: List<SyncRef>) {
        for (ref in refs.filter { it.kind == "manga-history" }) {
            val source = ref.source ?: continue
            if (!privacy.await(source) && !store.isExcluded(ref) && store.record(ref)?.deleted == false) {
                val parent = titles.getMangaByUrlAndSourceId(ref.title, source)
                val entry = parent?.let { items.getChapterByUrlAndMangaId(ref.item, it.id) }
                val itemRef = ref.copy(kind = "manga-item")
                if (entry != null && store.record(itemRef) == null) {
                    val metadata = PortableItem.from(entry)
                    if (runCatching { metadata.validate() }.isSuccess) {
                        if (store.record(ref.copy(kind = "manga-title", item = "")) == null) {
                            captureTitle(source, ref.title, initial = true)
                        }
                        store.capture(itemRef, (metadata.fields() - setOf("completed", "bookmark")) + present, now())
                    }
                }
            }
        }
    }

    private suspend fun captureTracking(source: Long, title: String, service: Long, deleted: Boolean = false) {
        val ref = SyncRef("manga-tracking", source, title, service.toString())
        if (deleted) {
            store.capture(ref, removed, captureTime())
            return
        }
        val parent = titles.getMangaByUrlAndSourceId(title, source) ?: return
        val track = database.awaitList { manga_syncQueries.getTracksByMangaId(parent.id, backupMangaTrackMapper) }
            .firstOrNull { it.syncId.toLong() == service } ?: return
        // Remote library IDs belong to the tracker's account, not to a portable title association.
        track.libraryId = 0
        if (store.record(SyncRef("manga-title", source, title)) == null) captureTitle(source, title, initial = true)
        store.capture(ref, mapOf("link" to syncJson.encodeToJsonElement(track)) + present, captureTime())
    }

    private suspend fun captureSubscription(source: Long, url: String, deleted: Boolean = false) {
        val ref = SyncRef("manga-subscription", source, url)
        if (deleted) {
            store.capture(ref, removed, captureTime())
            return
        }
        val title = titles.getMangaByUrlAndSourceId(url, source) ?: return
        val value = database.awaitOneOrNull { releaseMonitorQueries.getSubscription(title.id) } ?: return
        if (store.record(SyncRef("manga-title", source, url)) == null) captureTitle(source, url, initial = true)
        store.capture(
            ref,
            mapOf(
                "mode" to JsonPrimitive(value.mode),
                "availability" to JsonPrimitive(value.availability),
                "reminder" to JsonPrimitive(value.reminder),
            ) +
                present,
            captureTime(),
        )
    }

    override suspend fun apply(projection: CloudSyncStore.Projection): Boolean {
        val record = projection.record
        val ref = record.ref
        if (ref.kind !in setOf(
                "manga-title",
                "manga-item",
                "manga-history",
                "manga-category",
                "manga-membership",
                "manga-tracking",
                "manga-subscription",
            )
        ) {
            return false
        }
        val parentRef = SyncRef("manga-title", ref.source, ref.title)
        if (active.contains(parentRef)) return false
        if (ref.kind == "manga-history" && !record.deleted) recoverHistoryItem(projection)
        var actualFields = record.fields.mapValues { it.value.resolved()?.value }
        val applied = database.await(true) {
            // Serialize the final local observations with the remote database projection.
            capture()
            if (cloudSyncQueries.pendingCount().executeAsOne() > 0) return@await false
            if (store.record(ref) != record) return@await false
            cloudSyncQueries.suppress(1)
            try {
                when (ref.kind) {
                    "manga-subscription" -> {
                        val title = titles.getMangaByUrlAndSourceId(ref.title, requireNotNull(ref.source))
                            ?: return@await false
                        if (record.deleted) {
                            cloudSyncQueries.deleteSubscription(title.id)
                        } else {
                            val values = record.objectValues()
                            val mode = values["mode"]?.jsonPrimitive?.content ?: return@await false
                            val availability = values["availability"]?.jsonPrimitive?.longOrNull ?: return@await false
                            val reminder = values["reminder"]?.jsonPrimitive?.longOrNull ?: return@await false
                            require(mode in setOf("AUTO", "FOLLOW", "IGNORE"))
                            require(availability in 0L..1L && reminder in 0L..1L)
                            releaseMonitorQueries.setSubscription(title.id, mode, availability, reminder)
                            releaseMonitorQueries.makeDue(title.id)
                        }
                    }
                    "manga-history" -> {
                        val parent =
                            titles.getMangaByUrlAndSourceId(ref.title, requireNotNull(ref.source))
                                ?: return@await record.deleted
                        val entry = items.getChapterByUrlAndMangaId(ref.item, parent.id) ?: return@await record.deleted
                        val at = if (record.deleted) {
                            0L
                        } else {
                            record.objectValues()["at"]?.jsonPrimitive?.longOrNull
                                ?: return@await false
                        }
                        historyQueries.upsert(entry.id, java.util.Date(at), 0)
                    }
                    "manga-tracking" -> {
                        val parent =
                            titles.getMangaByUrlAndSourceId(ref.title, requireNotNull(ref.source)) ?: return@await false
                        val service = ref.item.toLong()
                        if (record.deleted) {
                            tracks.delete(parent.id, service)
                        } else {
                            val link = record.objectValues()["link"] ?: return@await false
                            val value = syncJson.decodeFromJsonElement(BackupTracking.serializer(), link)
                            require(value.syncId.toLong() == service)
                            val existing = tracks.getTracksByMangaId(parent.id).firstOrNull { it.trackerId == service }
                            tracks.insertManga(
                                value.getTrackImpl().copy(mangaId = parent.id, libraryId = existing?.libraryId),
                            )
                        }
                    }
                    "manga-title" -> {
                        val existing = titles.getMangaByUrlAndSourceId(ref.title, requireNotNull(ref.source))
                        if (record.deleted) {
                            if (existing != null) titles.updateManga(MangaUpdate(existing.id, favorite = false))
                            actualFields = actualFields + ("favorite" to JsonPrimitive(false))
                        } else {
                            if (!record.fields.keys.containsAll(
                                    setOf("name", "added", "viewer", "flags"),
                                )
                            ) {
                                return@await false
                            }
                            val portable = syncJson.decodeFromJsonElement(
                                PortableTitle.serializer(),
                                record.objectValues(),
                            )
                            val value = portable.manga(ref, existing ?: Manga.create())
                            val id = existing?.id ?: titles.insertManga(value) ?: error("Unable to restore title")
                            cloudSyncQueries.applyTitle(
                                id = id, name = value.title, favorite = value.favorite, artist = value.artist,
                                author = value.author, description = value.description,
                                genres = value.genre,
                                status = value.status, cover = value.thumbnailUrl, added = value.dateAdded,
                                viewer = value.viewerFlags, flags = value.chapterFlags,
                            )
                        }
                    }
                    "manga-item" -> {
                        val parent =
                            titles.getMangaByUrlAndSourceId(ref.title, requireNotNull(ref.source)) ?: return@await false
                        if (!record.fields.keys.containsAll(
                                setOf("name", "number", "order"),
                            )
                        ) {
                            return@await false
                        }
                        val portable = syncJson.decodeFromJsonElement(PortableItem.serializer(), record.objectValues())
                        val existing = items.getChapterByUrlAndMangaId(ref.item, parent.id)
                        val value = portable.chapter(
                            ref,
                            parent.id,
                            existing ?: tachiyomi.domain.items.chapter.model.Chapter.create(),
                        )
                        val position = record.positions().firstOrNull()
                        actualFields = positions.projectedBaseline(
                            ref,
                            actualFields,
                            position?.position ?: value.lastPageRead,
                            0,
                        )
                        val id = existing?.id ?: items.addAllChapters(listOf(value)).single().id
                        cloudSyncQueries.applyItem(
                            id = id, name = value.name, scanlator = value.scanlator, number = value.chapterNumber,
                            sourceOrder = value.sourceOrder, completed = value.read, bookmark = value.bookmark,
                            position = position?.position ?: value.lastPageRead, dateUpload = value.dateUpload,
                        )
                    }
                    "manga-category" -> {
                        val local = cloudSyncQueries.localCategory(ref.title).executeAsOneOrNull()
                        if (record.deleted) {
                            if (local != null) categories.deleteMangaCategory(local)
                        } else {
                            val value = syncJson.decodeFromJsonElement(
                                PortableCategory.serializer(),
                                record.objectValues(),
                            )
                            require(value.name.length in 1..512)
                            val id = local ?: run {
                                categories.insertMangaCategory(
                                    Category(0, value.name, value.order, value.flags, value.hidden),
                                )
                                val created = categoriesQueries.selectLastInsertedRowId().executeAsOne()
                                cloudSyncQueries.bindCategory(created, ref.title)
                                created
                            }
                            categories.updatePartialMangaCategory(
                                CategoryUpdate(id, value.name, value.order, value.flags, value.hidden),
                            )
                        }
                    }
                    "manga-membership" -> {
                        val parent =
                            titles.getMangaByUrlAndSourceId(ref.title, requireNotNull(ref.source)) ?: return@await false
                        val keys = record.fields.filter { (key, value) ->
                            key.startsWith("category/") && value.resolved()?.value == JsonPrimitive(true)
                        }.keys.map { it.removePrefix("category/") }
                        val ids = keys.map { key ->
                            if (store.record(SyncRef("manga-category", title = key))?.deleted == true) return@map null
                            cloudSyncQueries.localCategory(key).executeAsOneOrNull()
                                ?: return@await false
                        }
                        titles.setMangaCategories(parent.id, ids.filterNotNull())
                    }
                    else -> return@await false
                }
                true
            } finally {
                cloudSyncQueries.suppress(0)
            }
        }
        // A crash here repeats an idempotent projection; never acknowledge before the source commit.
        if (applied) store.applied(projection, actualFields)
        return applied
    }

    private suspend fun recoverHistoryItem(projection: CloudSyncStore.Projection) {
        val ref = projection.record.ref
        val itemRef = ref.copy(kind = "manga-item")
        if (store.record(itemRef) != null || store.isExcluded(ref)) return
        val sourceId = ref.source ?: return
        val parent = titles.getMangaByUrlAndSourceId(ref.title, sourceId) ?: return
        val existing = items.getChapterByUrlAndMangaId(ref.item, parent.id)
        val metadata = if (existing != null) {
            PortableItem.from(existing)
        } else {
            val engine = Injekt.get<tachiyomi.domain.source.manga.service.MangaSourceManager>().get(sourceId) ?: return
            val recovered = historyRecovery.recover(ref) {
                engine.getMangaUpdate(
                    parent.toSManga(),
                    emptyList(),
                    fetchDetails = false,
                    fetchChapters = true,
                ).chapters.mapIndexed { index, value ->
                    RecoveredSyncItem(
                        value.url,
                        PortableItem(
                            value.name,
                            value.chapter_number.toDouble(),
                            index.toLong(),
                            scanlator = value.scanlator,
                            dateUpload = value.date_upload.coerceAtLeast(0),
                            filler = false,
                        ),
                    )
                }
            } ?: return
            recovered.metadata.also { it.validate() }
        }
        val parentRef = ref.copy(kind = "manga-title", item = "")
        val saved = database.await(true) {
            if (active.contains(parentRef) ||
                store.isExcluded(ref) ||
                store.record(ref) != projection.record ||
                store.record(itemRef) != null
            ) {
                return@await false
            }
            if (titles.getMangaByUrlAndSourceId(ref.title, sourceId)?.id != parent.id) return@await false
            if (items.getChapterByUrlAndMangaId(ref.item, parent.id) == null) {
                cloudSyncQueries.suppress(1)
                try {
                    items.addAllChapters(listOf(metadata.chapter(itemRef, parent.id)))
                } finally {
                    cloudSyncQueries.suppress(0)
                }
            }
            true
        }
        if (saved) {
            // Metadata repairs do not fabricate progress, reading sessions or completion.
            store.capture(itemRef, (metadata.fields() - setOf("completed", "bookmark")) + present, now())
        }
    }
}
