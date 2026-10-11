package eu.kanade.tachiyomi.extension.manga.api

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionUpdate
import eu.kanade.tachiyomi.extension.ExtensionUpdateCheckGate
import eu.kanade.tachiyomi.extension.ExtensionUpdateKind
import eu.kanade.tachiyomi.extension.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaLoadResult
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.catalogueRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.domain.extensionrepo.manga.interactor.GetMangaExtensionRepo
import mihon.domain.extensionrepo.manga.interactor.UpdateMangaExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.NetworkMangaExtensionStore
import mihon.domain.extensionrepo.service.ExtensionRepoService
import okio.BufferedSource
import okio.buffer
import okio.gzip
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy

internal class MangaExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()
    private val getExtensionRepo: GetMangaExtensionRepo by injectLazy()
    private val updateExtensionRepo: UpdateMangaExtensionRepo by injectLazy()
    private val extensionRepoService: ExtensionRepoService by injectLazy()
    private val extensionManager: MangaExtensionManager by injectLazy()
    private val json: Json by injectLazy()
    private val protoBuf: ProtoBuf by injectLazy()

    private val updateGate by lazy { ExtensionUpdateCheckGate(preferenceStore) }

    var unavailableRepositories: Set<String> = emptySet()
        private set
    private val failures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun findExtensions(): List<MangaExtension.Available> {
        failures.clear()
        return withIOContext {
            getExtensionRepo.getAll()
                .map { async { getExtensions(it) } }
                .awaitAll()
                .flatten()
                .also { unavailableRepositories = failures.toSet() }
        }
    }

    private suspend fun getExtensions(extRepo: ExtensionRepo): List<MangaExtension.Available> {
        val repoBaseUrl = extRepo.baseUrl
        return try {
            fetchExtensionList(resolveIndexUrl(repoBaseUrl))
                .filter { it.libVersion in MangaExtensionLoader.SUPPORTED_LIB_VERSIONS }
                .map {
                    it.copy(
                        expectedSigner = extRepo.signingKeyFingerprint,
                        repoName = extRepo.name,
                        repoUrl = extRepo.baseUrl,
                    )
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.add(extRepo.baseUrl)
            logcat(LogPriority.ERROR, e) { "Failed to get extensions from $repoBaseUrl" }
            emptyList()
        }
    }

    /**
     * A repo's stored url is either a direct link to its extension list ("index.min.json" or
     * "index.pb"), or, for repos added before this existed, a bare base url. Bare urls, and
     * urls still pointing at "index.min.json", are re-checked against "repo.json"'s
     * "index_v2" on every call, so a repo that migrates to the newer format after being
     * added keeps working without the user having to remove and re-add it. A "index.pb" url
     * is already the final, self-describing format and needs no such check.
     */
    private suspend fun resolveIndexUrl(repoBaseUrl: String): String {
        if (repoBaseUrl.endsWith(".pb")) return repoBaseUrl

        val repoDir = if (repoBaseUrl.endsWith(".json")) repoBaseUrl.substringBeforeLast('/') else repoBaseUrl
        val indexV2 = extensionRepoService.fetchIndexUrl(repoDir)
        if (indexV2 != null) return indexV2

        return if (repoBaseUrl.endsWith(".json")) repoBaseUrl else "$repoBaseUrl/index.min.json"
    }

    /**
     * Repos publish extensions either as a plain JSON array at "index.min.json" (the legacy
     * format), or, once migrated to Mihon's newer format, as a JSON object or gzip-compressed
     * protobuf message ("index.pb").
     */
    private suspend fun fetchExtensionList(indexUrl: String): List<MangaExtension.Available> {
        val response = networkService.client.newCall(catalogueRequest(indexUrl)).awaitSuccess()
        val repoDir = indexUrl.substringBeforeLast('/')
        return response.body.source().decompressIfGzipped().use { source ->
            when (source.peek().readByte()) {
                // "[..."
                0x5B.toByte() -> json.decodeFromBufferedSource<List<ExtensionJsonObject>>(source)
                    .toExtensions(repoDir)
                // "{..."
                0x7B.toByte() -> json.decodeFromBufferedSource<NetworkMangaExtensionStore>(source)
                    .extensionList!!
                    .toExtensions(repoDir)
                else -> protoBuf.decodeFromByteArray<NetworkMangaExtensionStore>(source.readByteArray())
                    .extensionList!!
                    .toExtensions(repoDir)
            }
        }
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == 0x1f8b
            } catch (_: Exception) {
                false
            }
        }

        return if (isGzip) gzip().buffer() else this
    }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<MangaExtension.Installed>? = updateGate.run(ExtensionUpdateKind.MANGA, complete = {
        unavailableRepositories.isEmpty()
    }) {
        // Update extension repo details
        updateExtensionRepo.awaitAll()

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions()
        }

        val installedExtensions = MangaExtensionLoader.loadMangaExtensions(context)
            .filterIsInstance<MangaLoadResult.Success>()
            .map { it.extension }

        val assessed = extensionManager.assessUpdates(installedExtensions, extensions, unavailableRepositories)
        val extensionsWithUpdate = assessed.filter { it.hasUpdate }
        val updates = extensionsWithUpdate.mapNotNull { installed ->
            val chosen = extensions.filter {
                it.pkgName == installed.pkgName &&
                    it.repoUrl == installed.repoUrl
            }.maxByOrNull { it.versionCode } ?: return@mapNotNull null
            ExtensionUpdate(
                installed.pkgName,
                chosen.versionCode,
                chosen.libVersion,
                installed.name,
                chosen.repoUrl,
                chosen.expectedSigner,
            )
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(updates)
        }

        extensionsWithUpdate
    }

    private fun List<ExtensionJsonObject>.toExtensions(repoUrl: String): List<MangaExtension.Available> {
        return this.map {
            MangaExtension.Available(
                name = it.name.substringAfter("Tachiyomi: "),
                pkgName = it.pkg,
                versionName = it.version,
                versionCode = it.code,
                libVersion = it.version.substringBeforeLast('.').toDouble(),
                lang = it.lang,
                isNsfw = it.nsfw == 1,
                sources = it.sources?.map(extensionSourceMapper).orEmpty(),
                apkUrl = "$repoUrl/apk/${it.apk}",
                iconUrl = "$repoUrl/icon/${it.pkg}.png",
                repoUrl = repoUrl,
                distributionId = it.nyanimeDistributionId,
                advertisedHomeVersion = it.nyanimeHomeVersion,
                apkSha256 = it.sha256,
            )
        }
    }

    private fun NetworkMangaExtensionStore.ExtensionList.toExtensions(repoUrl: String): List<MangaExtension.Available> {
        return extensions.filter {
            eu.kanade.domain.extension.ExtensionCatalogueMedium.accepts(it.medium, "manga")
        }.map { extension ->
            val lang = extension.sources.map { it.language }.toSet()
            MangaExtension.Available(
                name = extension.name,
                pkgName = extension.packageName,
                versionName = extension.versionName,
                versionCode = extension.versionCode,
                libVersion = extension.extensionLib.toDouble(),
                lang = if (lang.size == 1) lang.first() else "all",
                isNsfw = extension.contentWarning >= NetworkMangaExtensionStore.ContentWarning.MIXED,
                sources = extension.sources.map { source ->
                    MangaExtension.Available.MangaSource(
                        id = source.id,
                        lang = source.language,
                        name = source.name,
                        baseUrl = source.homeUrl,
                    )
                },
                apkUrl = extension.resources.apkUrl,
                iconUrl = extension.resources.iconUrl,
                repoUrl = repoUrl,
                distributionId = extension.nyanimeDistributionId,
                repositoryAliases = extension.repositoryAliases,
                advertisedHomeVersion = extension.nyanimeHomeVersion,
                apkSha256 = extension.sha256,
            )
        }
    }
}

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<ExtensionSourceJsonObject>?,
    val nyanimeDistributionId: String? = null,
    val nyanimeHomeVersion: Int? = null,
    val sha256: String? = null,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private val extensionSourceMapper: (ExtensionSourceJsonObject) -> MangaExtension.Available.MangaSource = {
    MangaExtension.Available.MangaSource(
        id = it.id,
        lang = it.lang,
        name = it.name,
        baseUrl = it.baseUrl,
    )
}
