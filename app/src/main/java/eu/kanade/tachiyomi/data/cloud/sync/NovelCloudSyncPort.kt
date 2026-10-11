package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import it.lorelore.nyanime.novel.NovelBook
import it.lorelore.nyanime.novel.NovelRecord
import it.lorelore.nyanime.novel.NovelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.syncJson

/**
 * Only portable records use Cloud; no reader, book file, cache or source session is initialized.
 */
internal class NovelCloudSyncPort(
    context: Context,
    private val store: CloudSyncStore,
    private val preferences: CloudSyncPreferences,
    private val now: () -> Long,
    private val localStore: () -> NovelStore = { NovelStore.shared(context) },
    private val hasLocalData: () -> Boolean = {
        context.getDatabasePath(NovelStore.DATABASE_NAME).isFile
    },
) : CloudSyncDomainPort {
    private val local by lazy(localStore)
    private var permitted = false
    private var privateSources = emptySet<Long>()
    private var policy: String? = null

    override suspend fun configure(
        enabled: Boolean,
        incognito: Boolean,
        excludedSources: Set<Long>,
    ) {
        permitted = enabled && !incognito
        privateSources = excludedSources - 0L
    }

    private fun available() = permitted && hasLocalData()

    private fun cursor() = store.state("novel-cursor")?.toLongOrNull() ?: 0L

    private suspend fun book(record: NovelRecord): NovelBook? =
        if (record.kind == "book") {
            syncJson.decodeFromJsonElement<NovelBook>(record.payload)
        } else {
            record.bookId?.let { local.get(it) }
        }

    private suspend fun ref(record: NovelRecord): SyncRef? {
        val book = book(record)
        if (record.bookId != null && book?.id != record.bookId) return null
        return NovelCloudRecords.ref(record, book)
    }

    private fun allowed(record: NovelRecord, ref: SyncRef) =
        (record.kind != "appearance" || preferences.sharedReader().get()) &&
            ref.source !in privateSources &&
            !store.isExcluded(ref)

    override suspend fun seed() {
        capture()
    }

    override suspend fun capture(limit: Long): Int =
        withContext(Dispatchers.IO) {
            if (!available() || limit <= 0) return@withContext 0
            if (policy != store.state("exclusions")) {
                store.state("novel-cursor", "0")
                policy = store.state("exclusions")
            }
            var changes = 0
            for (change in local.changes(cursor(), limit.coerceAtMost(200).toInt())) {
                val record = change.record
                val reference = ref(record)
                val currentEdition =
                    record.kind != "position" ||
                        book(record)?.contentVersion ==
                        record.payload.jsonObject["contentVersion"]?.jsonPrimitive?.content
                if (reference != null && allowed(record, reference) && currentEdition) {
                    changes += captureRecord(record, reference)
                }
                store.state("novel-cursor", change.sequence.toString())
            }
            changes
        }

    private fun captureRecord(record: NovelRecord, reference: SyncRef): Int {
        val fields = mapOf("document" to syncJson.encodeToJsonElement(record))
        val key = "novel-intent/${reference.key()}"
        store.pendingIntents(key).firstOrNull()?.let { (_, intent) ->
            if (intent.record.fields["document"]?.resolved()?.value == fields["document"]) {
                store.applied(intent, fields)
                store.state(key, "")
            }
        }
        return store.capture(reference, fields, now())
    }

    override suspend fun apply(projection: CloudSyncStore.Projection): Boolean =
        withContext(Dispatchers.IO) {
            if (!permitted || projection.record.ref.kind != NovelCloudRecords.KIND) {
                return@withContext false
            }
            val fields = projection.record.fields.mapValues { it.value.resolved()?.value }
            val document =
                fields["document"]?.takeUnless { it == JsonNull } ?: return@withContext false
            val record = syncJson.decodeFromJsonElement<NovelRecord>(document)
            val reference = ref(record) ?: return@withContext false
            require(reference == projection.record.ref) { "Novel Cloud identity mismatch" }
            if (!allowed(record, reference)) return@withContext false
            capture()
            val before = local.record(record.kind, record.key)
            before?.let { captureRecord(it, reference) }
            if (store.record(reference) != projection.record) return@withContext false

            if (record.kind == "position") {
                val localBook = book(record) ?: return@withContext false
                val version =
                    record.payload.jsonObject.getValue("contentVersion").jsonPrimitive.content
                if (localBook.contentVersion != version) {
                    val bookRecord =
                        local.record("book", requireNotNull(record.bookId))
                            ?: return@withContext false
                    val bookRef = NovelCloudRecords.ref(bookRecord, localBook)
                    val remoteBook =
                        store
                            .record(bookRef)
                            ?.fields
                            ?.get("document")
                            ?.resolved()
                            ?.value
                            ?.takeUnless { it == JsonNull }
                            ?.let { syncJson.decodeFromJsonElement<NovelRecord>(it) }
                            ?.payload
                            ?.let { syncJson.decodeFromJsonElement<NovelBook>(it) }
                            ?: return@withContext false
                    if (remoteBook.contentVersion == version) {
                        return@withContext false // Wait for its book metadata.
                    }
                    // This point belongs to a superseded edition. Acknowledge without replacing a
                    // valid local point.
                    store.applied(
                        projection,
                        fields + ("document" to before?.let { syncJson.encodeToJsonElement(it) }),
                    )
                    return@withContext true
                }
            }

            val key = "novel-intent/${reference.key()}"
            store.state(key, syncJson.encodeToString(projection))
            if (!local.project(record, before)) {
                store.state(key, "")
                capture()
                return@withContext false
            }
            store.applied(projection, fields)
            store.state(key, "")
            true
        }

    override suspend fun pendingCount(): Long = if (available()) local.pending(cursor()) else 0
}

/** Stable identity includes the exact source reference via the book ID, never the human title. */
internal object NovelCloudRecords {
    const val KIND = "novel-metadata"

    fun ref(record: NovelRecord, book: NovelBook?): SyncRef {
        require(record.bookId == null || book?.id == record.bookId)
        return SyncRef(
            KIND,
            book?.origin?.sourceId ?: 0L,
            record.bookId ?: "global",
            "${record.kind}/${record.key}",
        )
    }
}
