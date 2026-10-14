package eu.kanade.tachiyomi.data.cloud.sync

import android.content.Context
import eu.kanade.tachiyomi.data.rin.RinManifest
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.data.rin.RinSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.encodeToJsonElement
import mihon.domain.extension.anime.model.AnimeExtensionStore
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import mihon.domain.extensionrepo.model.ExtensionRepo
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.SyncRef
import nyanime.telegram.sync.syncJson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Only a user's installed inventory replicates; catalogue availability is never an install request. */
internal class CloudSyncCataloguesPort(
    context: Context,
    private val store: CloudSyncStore,
    private val preferences: CloudSyncPreferences,
    private val now: () -> Long,
    private val anime: AnimeExtensionStoreRepository = Injekt.get(),
    private val manga: MangaExtensionRepoRepository = Injekt.get(),
) : CloudSyncDomainPort {
    private val rin = RinRepository.get(context)
    private val sourcePreferences = eu.kanade.domain.source.service.SourcePreferences(Injekt.get<PreferenceStore>())
    private var refreshed = 0L
    private var previousValues = emptyMap<SyncRef, Map<String, kotlinx.serialization.json.JsonElement?>>()
    private var policy: String? = null

    override suspend fun configure(enabled: Boolean, incognito: Boolean, excludedSources: Set<Long>) = Unit
    override suspend fun seed() {
        capture()
    }

    override suspend fun capture(limit: Long): Int {
        if (!preferences.sharedCatalogues().get()) return 0
        val currentPolicy = store.state("exclusions")
        if (currentPolicy != policy) {
            previousValues = emptyMap()
            policy = currentPolicy
        }
        val values = buildMap {
            anime.getAll().forEach {
                val data = PortableCatalogue(
                    "anime", it.indexUrl, it.name, it.badgeLabel, it.signingKey, it.contact.website,
                    it.contact.discord, it.isLegacy, it.extensionListUrl,
                )
                put(catalogueRef(data), mapOf("catalogue" to syncJson.encodeToJsonElement(data)) + present)
            }
            manga.getAll().forEach {
                val data =
                    PortableCatalogue("manga", it.baseUrl, it.name, it.shortName, it.signingKeyFingerprint, it.website)
                put(catalogueRef(data), mapOf("catalogue" to syncJson.encodeToJsonElement(data)) + present)
            }
            val excluded =
                sourcePreferences.incognitoAnimeExtensions().get() + sourcePreferences.incognitoMangaExtensions().get()
            rin.installed.value.filter { it.manifest.packageName !in excluded }.forEach {
                val data = PortableRin(
                    it.manifest.packageName,
                    it.repository,
                    it.signer,
                    it.manifest.medium,
                    it.manifest.knownSourceIds,
                )
                put(
                    SyncRef("rin", title = it.manifest.packageName),
                    mapOf("package" to syncJson.encodeToJsonElement(data), "enabled" to JsonPrimitive(it.enabled)) +
                        present,
                )
            }
        }
        var observed = observed()
        store.pendingIntents("catalogue-intent/").forEach { (key, intent) ->
            val actual = values[intent.record.ref]
            val desired = intent.record.fields.mapValues { it.value.resolved()?.value }
            if (if (intent.record.deleted) actual == null else actual == desired) {
                store.applied(intent, desired)
                observed = if (intent.record.deleted) observed - intent.record.ref else observed + intent.record.ref
                remember(observed)
            }
            store.state(key, "")
        }
        var changes = 0
        values.forEach { (ref, fields) ->
            if (previousValues[ref] != fields) changes += store.capture(ref, fields, now())
        }
        // The initial absence of an extension/catalogue does not delete it on the other phone.
        for (ref in observed - values.keys) {
            if (ref.kind == "rin" &&
                ref.title in
                sourcePreferences.incognitoAnimeExtensions().get() + sourcePreferences.incognitoMangaExtensions().get()
            ) {
                continue
            }
            changes += store.capture(ref, removed, now())
        }
        if (observed != values.keys) remember(values.keys)
        previousValues = values
        return changes
    }

    override suspend fun apply(projection: CloudSyncStore.Projection): Boolean {
        val record = projection.record
        if (record.ref.kind !in setOf("catalogue", "rin") || !preferences.sharedCatalogues().get()) return false
        if (RinSessions.busy.value || eu.kanade.tachiyomi.data.addon.RinAddonRuntime.busy.value) return false
        capture()
        if (store.record(record.ref) != record) return false
        val fields = record.fields.mapValues { it.value.resolved()?.value }
        val intentKey = "catalogue-intent/${record.ref.key()}"
        store.state(intentKey, syncJson.encodeToString(projection))
        if (record.ref.kind == "catalogue") {
            val value = fields["catalogue"] ?: return false
            val data = syncJson.decodeFromJsonElement(PortableCatalogue.serializer(), value)
            data.validate()
            require(record.ref == catalogueRef(data))
            if (record.deleted) {
                if (data.medium == "anime") anime.remove(data.url) else manga.deleteRepo(data.url)
            } else if (data.medium == "anime") {
                val existing = anime.getAll().firstOrNull { it.indexUrl == data.url }
                require(existing == null || existing.signingKey.equals(data.signer, ignoreCase = true))
                anime.upsert(
                    AnimeExtensionStore(
                        data.url,
                        data.name,
                        data.label ?: data.name,
                        data.signer,
                        AnimeExtensionStore.Contact(data.website, data.discord),
                        data.legacy,
                        data.extensionList,
                    ),
                )
            } else {
                val existing = manga.getRepo(data.url)
                require(existing == null || existing.signingKeyFingerprint.equals(data.signer, ignoreCase = true))
                manga.upsertRepo(ExtensionRepo(data.url, data.name, data.label, data.website, data.signer))
            }
            refreshed = 0
        } else {
            if (record.ref.title in sourcePreferences.incognitoAnimeExtensions().get() +
                sourcePreferences.incognitoMangaExtensions().get()
            ) {
                return false
            }
            val data =
                fields["package"]?.let { syncJson.decodeFromJsonElement(PortableRin.serializer(), it) } ?: return false
            data.validate()
            require(data.packageName == record.ref.title)
            val previous = rin.installed.value.firstOrNull { it.manifest.packageName == data.packageName }
            if (previous != null) {
                require(previous.signer == data.signer && previous.manifest.medium == data.medium)
                require(previous.manifest.preservesSourceIds(data.sources))
            }
            if (record.deleted) {
                if (previous != null) withContext(Dispatchers.Main.immediate) { rin.remove(data.packageName) }
                if (rin.installed.value.any { it.manifest.packageName == data.packageName }) return false
            } else {
                val enabled = (fields["enabled"] as? JsonPrimitive)?.booleanOrNull ?: return false
                if (previous == null) {
                    // A sideloaded package has no download origin. Keep its state pending
                    // until the same verified package is imported locally, without requiring a catalogue.
                    if (data.repository.isEmpty()) return false
                    if (android.os.SystemClock.elapsedRealtime() - refreshed > 60_000 || refreshed == 0L) {
                        rin.refresh(scheduleUpdates = false)
                        refreshed = android.os.SystemClock.elapsedRealtime()
                    }
                    val entry = rin.entries.value.filter {
                        it.manifest.packageName == data.packageName &&
                            it.repository == data.repository &&
                            it.signer == data.signer &&
                            it.manifest.medium == data.medium &&
                            it.manifest.preservesSourceIds(data.sources) &&
                            RinRepository.compatible(it.manifest)
                    }.maxByOrNull { it.manifest.versionCode } ?: return false
                    if (rin.legacyPresent(data.packageName)) {
                        // Android uninstall still requires the existing, explicit migration flow.
                        return false
                    }
                    val prepared = rin.prepare(entry)
                    // A concurrent local Store action always gets another reconciliation pass.
                    if (rin.installed.value.any { it.manifest.packageName == data.packageName }) return false
                    rin.activate(prepared.copy(enabled = enabled), preserveEnabled = false)
                } else if (previous.enabled != enabled) {
                    rin.setEnabled(data.packageName, enabled)
                }
            }
        }
        store.applied(projection, fields)
        store.state(intentKey, "")
        remember(if (record.deleted) observed() - record.ref else observed() + record.ref)
        return true
    }

    private fun observed(): Set<SyncRef> = store.state("catalogue-observed")
        ?.let { syncJson.decodeFromString<Set<SyncRef>>(it) }.orEmpty()

    private fun remember(refs: Set<SyncRef>) = store.state("catalogue-observed", syncJson.encodeToString(refs))

    private fun catalogueRef(data: PortableCatalogue) =
        SyncRef("catalogue", title = data.medium, item = SyncCheckpoints.hash(data.url))
}

@Serializable
internal data class PortableCatalogue(
    val medium: String,
    val url: String,
    val name: String,
    val label: String?,
    val signer: String,
    val website: String,
    val discord: String? = null,
    val legacy: Boolean = false,
    val extensionList: String? = null,
) {
    fun validate() {
        require(medium in setOf("anime", "manga") && name.length in 1..200)
        require(url.toHttpUrlOrNull()?.scheme == "https" && url.length <= 4096)
        require(signer.matches(Regex("[a-fA-F0-9]{64}")))
        require(website.length <= 4096 && (extensionList?.length ?: 0) <= 4096)
    }
}

@Serializable
internal data class PortableRin(
    val packageName: String,
    val repository: String,
    val signer: String,
    val medium: String,
    val sources: Set<Long>,
) {
    fun validate() {
        require(packageName.length in 1..256 && packageName.matches(Regex("[A-Za-z][A-Za-z0-9_.]+")))
        require(repository.length <= 4096 && (repository.isEmpty() || repository.toHttpUrlOrNull()?.scheme == "https"))
        require(signer.matches(Regex("[a-f0-9]{64}")))
        require(medium in RinManifest.supportedMedia && sources.size <= 128 && 0L !in sources)
    }
}
