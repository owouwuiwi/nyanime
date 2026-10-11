package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import nyanime.panels.GuidedPageRecord
import nyanime.panels.GuidedReadingStore
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.syncJson

/** Separate portable reading document; receiving it never moves an active reader. */
internal class GuidedCloudSyncPort(
    context: Context,
    private val store: CloudSyncStore,
    private val preferences: CloudSyncPreferences,
    private val now: () -> Long,
) : CloudSyncDomainPort {
    private val local by lazy { GuidedReadingStore.get(context) }
    private var permitted = false
    private var privateSources = emptySet<Long>()
    private var policy: String? = null
    override suspend fun configure(enabled: Boolean, incognito: Boolean, excludedSources: Set<Long>) {
        permitted = enabled && !incognito
        privateSources = excludedSources + 0L
    }
    private fun enabled() = permitted && preferences.sharedReader().get()
    private fun ref(record: GuidedPageRecord) = SyncRef(
        "manga-guided-page",
        record.ref.source,
        record.ref.title,
        SyncCheckpoints.hash(syncJson.encodeToString(record.ref)),
    )
    override suspend fun seed() {
        capture()
    }

    override suspend fun capture(limit: Long): Int = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext 0
        if (policy != store.state("exclusions")) {
            store.state("guided-cursor", "0")
            policy = store.state("exclusions")
        }
        var changes = 0
        for ((revision, record) in local.changes(cursor(), limit.toInt())) {
            val reference = ref(record)
            if (record.ref.source in privateSources || store.isExcluded(reference)) {
                store.state("guided-cursor", revision.toString())
                continue
            }
            changes += captureRecord(record)
            store.state("guided-cursor", revision.toString())
        }
        changes
    }
    override suspend fun apply(projection: CloudSyncStore.Projection): Boolean = withContext(Dispatchers.IO) {
        if (!enabled() || projection.record.ref.kind != "manga-guided-page") return@withContext false
        val reference = projection.record.ref
        if (store.isExcluded(reference) || reference.source in privateSources) return@withContext false
        capture()
        val fields = projection.record.fields.mapValues { it.value.resolved()?.value }
        val document = fields["document"]?.takeUnless { it == JsonNull } ?: return@withContext false
        val record = syncJson.decodeFromJsonElement(GuidedPageRecord.serializer(), document)
        require(record.valid() && ref(record) == reference)
        val key = "guided-intent/${reference.key()}"
        val before = local.get(record.ref)
        // This page may be beyond the current incremental batch. Capture its local edit before
        // applying the remote projection, then compare both the causal record and the local row.
        before?.let(::captureRecord)
        if (store.record(reference) != projection.record) return@withContext false
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

    private fun captureRecord(record: GuidedPageRecord): Int {
        val reference = ref(record)
        val fields = mapOf("document" to syncJson.encodeToJsonElement(record))
        val key = "guided-intent/${reference.key()}"
        store.pendingIntents(key).firstOrNull()?.let { (_, intent) ->
            if (intent.record.fields["document"]?.resolved()?.value == fields["document"]) {
                store.applied(intent, fields)
                store.state(key, "")
            }
        }
        return store.capture(reference, fields, now())
    }

    private fun cursor() = store.state("guided-cursor")?.toLongOrNull() ?: 0L
    override suspend fun pendingCount(): Long = if (enabled()) local.pending(cursor()) else 0
}
