package eu.kanade.tachiyomi.data.addon

import android.content.Context
import android.content.Intent
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.AddonHostVersions
import eu.kanade.domain.extension.ExtensionUpdateCandidate
import eu.kanade.domain.extension.ExtensionUpdatePolicy
import eu.kanade.domain.extension.ExtensionUpdateStatus
import eu.kanade.domain.extension.UnifiedExtensionCatalogue
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionApkValidator
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.catalogueRequest
import eu.kanade.tachiyomi.util.storage.getUriCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest

/** Addons read the same user-imported catalogues as the player and reader. */
class AddonExtensionCatalogue(private val context: Context, private val network: NetworkHelper) {
    private val refreshLock = Mutex()
    private val installLock = Mutex()
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!refreshLock.tryLock()) return@withContext
        try {
            val repositories = (
                Injekt.get<AnimeExtensionStoreRepository>().getAll().map { it.indexUrl to it.signingKey } +
                    Injekt.get<MangaExtensionRepoRepository>().getAll().map { it.baseUrl to it.signingKeyFingerprint }
                ).filter {
                UnifiedExtensionCatalogue.validUrl(it.first)
            }.groupBy({ it.first }, { it.second.lowercase() })
            val urls = repositories.keys
            mutable.update { it.copy(loading = true, hasCatalogues = urls.isNotEmpty()) }
            if (Injekt.get<BasePreferences>().downloadedOnly().get()) {
                mutable.update { it.copy(loading = false) }
                return@withContext
            }
            val entries = mutableListOf<Entry>()
            val failed = mutableSetOf<String>()
            urls.forEach { url ->
                try {
                    val content = network.client.newCall(catalogueRequest(url)).awaitSuccess().use { response ->
                        val source = response.body.source()
                        require(!source.request(UnifiedExtensionCatalogue.MAX_BYTES + 1))
                        source.readUtf8()
                    }
                    val signers = repositories.getValue(url).distinct()
                    require(signers.size == 1)
                    entries += parseEntries(content, url, signers.single())
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    failed += url
                }
            }
            // Keep cached entries of unavailable catalogues, but never offer their downloads as verified current data.
            mutable.update { old ->
                old.copy(
                    entries = (entries + old.entries.filter { it.repository in failed }).distinctBy {
                        it.packageName to it.repository
                    }.sortedBy { it.name },
                    unavailable = failed,
                    loading = false,
                )
            }
        } finally {
            mutable.update { it.copy(loading = false) }
            refreshLock.unlock()
        }
    }

    fun availableFor(installed: List<AddonExtension>, snapshot: State): List<Entry> = snapshot.entries.filter { entry ->
        if (entry.repository in snapshot.unavailable) return@filter false
        val old = installed.firstOrNull { it.packageName == entry.packageName } ?: return@filter true
        decision(entry, old) == ExtensionUpdateStatus.AVAILABLE
    }.groupBy { it.packageName }.values.mapNotNull { matches ->
        matches.singleOrNull() // Conflicting publishers require a choice; never silently pick a signer.
    }

    private fun decision(entry: Entry, old: AddonExtension): ExtensionUpdateStatus = ExtensionUpdatePolicy.resolve(
        old.packageName,
        old.versionCode,
        old.metadata,
        listOf(
            ExtensionUpdateCandidate(
                entry.packageName,
                entry.versionCode,
                entry.repository,
                entry.signer,
                compatibleApi =
                entry.extensionLib in setOf("1.0", "2.0") &&
                    entry.hostVersions.supports(BuildConfig.VERSION_CODE.toLong()),
                distributionId = entry.nyanimeDistributionId,
                repositoryAliases = entry.repositoryAliases,
            ),
        ),
        boundRepository = entry.repository.takeIf { old.metadata.distribution?.updatePolicy == "manual" },
    ).status

    suspend fun install(entry: Entry, registry: AddonRegistry) = withContext(Dispatchers.IO) {
        if (!installLock.tryLock()) return@withContext
        val staged = File(context.cacheDir, "addon-extension-${entry.packageName}-${entry.versionCode}.apk")
        try {
            check(entry.hostVersions.supports(BuildConfig.VERSION_CODE.toLong()))
            check(!Injekt.get<BasePreferences>().downloadedOnly().get())
            check(entry in state.value.entries && entry.repository !in state.value.unavailable)
            mutable.update { it.copy(installing = entry.packageName, progress = null, error = null) }
            val old = registry.state.value.firstOrNull { it.packageName == entry.packageName }
            check(old == null || decision(entry, old) == ExtensionUpdateStatus.AVAILABLE)
            if (staged.exists()) check(staged.delete())
            val digest = MessageDigest.getInstance("SHA-256")
            network.client.newCall(GET(entry.resources.apkUrl)).awaitSuccess().use { response ->
                val body = response.body
                val total = body.contentLength()
                require(total <= MAX_APK_BYTES)
                body.byteStream().use { input ->
                    staged.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var count = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            require(count <= MAX_APK_BYTES)
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            if (total >
                                0
                            ) {
                                mutable.update { it.copy(progress = (count.toFloat() / total).coerceIn(0f, 1f)) }
                            }
                        }
                    }
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == entry.sha256)
            val live = registry.state.value.firstOrNull { it.packageName == entry.packageName }
            require(
                ExtensionApkValidator.validate(
                    context,
                    staged,
                    ExtensionApkValidator.Expected(
                        entry.packageName,
                        entry.versionCode,
                        entry.signer,
                        entry.nyanimeDistributionId,
                        repository = entry.repository,
                        installedVersion = live?.versionCode,
                        libVersion = entry.extensionLib.toDouble(),
                        installed = live?.metadata,
                        approvedManualRepository = entry.repository.takeIf {
                            live?.metadata?.distribution?.updatePolicy == "manual"
                        },
                        kind = ExtensionApkValidator.Kind.ADDON,
                        addonHostVersions = entry.hostVersions,
                    ),
                ),
            )
            staged.setReadOnly()
            withContext(Dispatchers.Main) {
                // The visible Install/Update action is the explicit consent, including manual-to-repository transitions.
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(
                        staged.getUriCompat(context),
                        "application/vnd.android.package-archive",
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }
        } catch (cancel: CancellationException) {
            staged.delete()
            throw cancel
        } catch (_: Exception) {
            staged.delete()
            mutable.update { it.copy(error = entry.packageName) }
        } finally {
            mutable.update { it.copy(installing = null, progress = null) }
            installLock.unlock()
        }
    }

    @Serializable
    data class Entry(
        val packageName: String,
        val name: String,
        val versionCode: Long,
        val versionName: String,
        val extensionLib: String,
        val medium: String? = null,
        val resources: Resources,
        val sha256: String,
        val nyanimeDistributionId: String? = null,
        val repositoryAliases: Set<String> = emptySet(),
        val repository: String = "",
        val repositoryName: String = "",
        val signer: String = "",
        val sources: List<Source> = emptyList(),
        val descriptions: Map<String, String> = emptyMap(),
        val minHostVersionCode: Long? = null,
        val maxHostVersionCode: Long? = null,
        val minHostVersionName: String? = null,
        val maxHostVersionName: String? = null,
        // Older standalone addon indexes imply registered Android components.
        val requiresAndroid: Boolean = true,
    ) {
        val hostVersions get() = AddonHostVersions(
            minHostVersionCode,
            maxHostVersionCode,
            minHostVersionName,
            maxHostVersionName,
        )
    }

    @Serializable data class Source(val language: String = "")

    @Serializable data class Resources(val apkUrl: String, val iconUrl: String? = null)
    data class State(
        val entries: List<Entry> = emptyList(),
        val unavailable: Set<String> = emptySet(),
        val hasCatalogues: Boolean = false,
        val loading: Boolean = false,
        val installing: String? = null,
        val progress: Float? = null,
        val error: String? = null,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
        internal fun parseEntries(content: String, repository: String, pinnedSigner: String): List<Entry> {
            require(content.toByteArray().size <= UnifiedExtensionCatalogue.MAX_BYTES)
            // Legacy catalogues need not declare any addon entries; their video metadata is not an addon schema.
            val document = json.parseToJsonElement(content) as? JsonObject ?: return emptyList()
            val declaresAddon = document["media"]?.jsonArray?.any { it.jsonPrimitive.content == "addon" } == true
            val optional = document["addonList"] as? JsonObject
            val actions = document["addonActions"] as? JsonObject
            if (!declaresAddon && optional == null && actions == null) return emptyList()
            require(optional == null || optional["version"]?.jsonPrimitive?.content == "1")
            require(actions == null || actions["version"]?.jsonPrimitive?.content == "1")
            val catalogue = UnifiedExtensionCatalogue.parse(content)
            require(catalogue.signingKey == pinnedSigner)
            val extensions = (if (declaresAddon) document["extensionList"] as? JsonObject else optional)
                ?.getValue("extensions")?.jsonArray.orEmpty() +
                actions?.getValue("extensions")?.jsonArray.orEmpty()
            require(
                extensions.map { (it as JsonObject)["packageName"]?.jsonPrimitive?.content }.distinct().size ==
                    extensions.size,
            )
            return extensions.filter { (it as JsonObject)["medium"]?.jsonPrimitive?.content == "addon" }.map { value ->
                val entry = json.decodeFromJsonElement<Entry>(value)
                require(entry.extensionLib in setOf("1.0", "2.0") && entry.versionCode > 0)
                require(entry.hostVersions.valid)
                require(entry.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
                require(entry.name.isNotBlank() && entry.name.length <= 128)
                require(
                    entry.descriptions.size <= 50 &&
                        entry.descriptions.all { (locale, text) ->
                            locale.length <= 35 &&
                                locale.matches(Regex("[A-Za-z0-9-]+")) &&
                                text.isNotBlank() &&
                                text.length <= 4096
                        },
                )
                require(entry.sha256.matches(Regex("[a-f0-9]{64}")))
                require(webUrl(entry.resources.apkUrl))
                require(entry.nyanimeDistributionId != null)
                require(entry.repositoryAliases.all(UnifiedExtensionCatalogue::validUrl))
                entry.copy(repository = repository, repositoryName = catalogue.name, signer = pinnedSigner)
            }
        }
        private fun webUrl(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null
        }.getOrDefault(false)
    }
}
