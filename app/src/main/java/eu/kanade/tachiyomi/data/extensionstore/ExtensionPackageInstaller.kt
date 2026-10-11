package eu.kanade.tachiyomi.data.extensionstore

import android.content.Context
import android.content.Intent
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.ExtensionExecutionTrust
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.extension.ExtensionApkValidator
import eu.kanade.tachiyomi.extension.ExtensionPackageArchive
import eu.kanade.tachiyomi.extension.ExtensionPackageInspector
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.storage.getUriCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

enum class StoreInstallMode { SYSTEM, PRIVATE }

/** One verified artifact, two backends. Download/verification cannot execute unapproved code. */
internal class ExtensionPackageInstaller(private val context: Context, private val rin: RinRepository) {
    suspend fun install(
        entry: StoreEntry,
        mode: StoreInstallMode,
        progress: (Float?) -> Unit,
        catalogueConsent: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        check(!Injekt.get<BasePreferences>().downloadedOnly().get())
        require(mode == StoreInstallMode.SYSTEM || entry.canImport)
        val url = requireNotNull(entry.candidateUrl)
        require(eu.kanade.tachiyomi.data.rin.RinArchive.validBundleUrl(url))
        val previous = rin.storage.record(entry.packageName)
        require(entry.rinRecord == null || previous?.sha256 == entry.rinRecord.sha256)
        val originalSystem = systemIdentity(entry.packageName)
        val file = File.createTempFile("extension-", ".apk", context.cacheDir)
        var handedToAndroid = false
        try {
            Injekt.get<NetworkHelper>().client.newCall(GET(url)).awaitSuccess().use { response ->
                val total = response.body.contentLength()
                require(total <= ExtensionPackageArchive.MAX_BYTES)
                response.body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var count = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            require(count <= ExtensionPackageArchive.MAX_BYTES)
                            output.write(buffer, 0, read)
                            progress(if (total > 0) (count.toFloat() / total).coerceIn(0f, 1f) else null)
                        }
                    }
                }
            }
            entry.candidateHash?.let { require(ExtensionPackageArchive.sha256(file) == it) }
            val inspected = ExtensionPackageArchive.inspect(context, file)
            val unchanged = rin.storage.record(entry.packageName) == previous &&
                systemIdentity(entry.packageName) == originalSystem
            require(unchanged) {
                "The extension changed while downloading"
            }
            val installed = previous?.let {
                val path = rin.storage.file(it).absolutePath
                val contracts = ExtensionPackageInspector.inspectContracts(path) { home -> home.sections.size }
                contracts.copy(signers = it.publisherSigners, signerHistory = it.publisherHistory)
            } ?: entry.installedMetadata
            val metadata = ExtensionPackageInspector.inspect(inspected.info) { 0 }
            val equivalent = if (entry.candidateVersion == entry.installedVersion) {
                if (previous?.format == "apk") {
                    previous.sha256
                } else {
                    runCatching {
                        val info = context.packageManager.getPackageInfo(
                            entry.packageName,
                            ExtensionPackageArchive.FLAGS,
                        )
                        ExtensionPackageArchive.sha256(File(requireNotNull(info.applicationInfo?.sourceDir)))
                    }.getOrNull()
                }
            } else {
                null
            }
            val expected = ExtensionApkValidator.Expected(
                packageName = entry.packageName,
                versionCode = requireNotNull(entry.candidateVersion),
                signer = entry.candidateSigner,
                distributionId = entry.candidateDistribution ?: metadata.distribution?.id,
                repository = entry.repository,
                installedVersion = entry.installedVersion,
                libVersion = requireNotNull(entry.candidateApi),
                installed = installed,
                approvedManualRepository = entry.repository,
                kind = ExtensionApkValidator.Kind.valueOf(entry.medium.name),
                equivalentApkHash = equivalent,
                associatedOrigin = previous?.repository == entry.repository && entry.imported,
            )
            require(ExtensionApkValidator.validate(context, file, expected))
            val keys = (
                Injekt.get<AnimeExtensionStoreRepository>()
                    .getAll().filter { it.indexUrl == entry.repository }.map { it.signingKey.lowercase() } +
                    Injekt.get<MangaExtensionRepoRepository>()
                        .getAll().filter { it.baseUrl == entry.repository }
                        .map { it.signingKeyFingerprint.lowercase() }
                )
                .distinct()
            require(keys.size == 1 && (catalogueConsent == null || keys.single() == catalogueConsent))
            ExtensionExecutionTrust(Injekt.get()).approve(
                entry.packageName,
                metadata,
                entry.repository,
                catalogueConsent,
            )
            if (mode == StoreInstallMode.SYSTEM && !inspected.requiresAndroid) {
                val staged = rin.storage.stage(
                    file,
                    metadata.signers.first(),
                    ExtensionPackageArchive.sha256(file),
                    entry.repository.orEmpty(),
                ).copy(trusted = true, catalogueConsent = catalogueConsent)
                val sources = eu.kanade.tachiyomi.data.rin.RinLoader.instantiate(context, staged)
                val actualIds = sources.mapNotNull {
                    when (it) {
                        is eu.kanade.tachiyomi.animesource.AnimeSource -> it.id
                        is eu.kanade.tachiyomi.source.MangaSource -> it.id
                        else -> null
                    }
                }.toSet()
                val previousIds = previous?.manifest?.knownSourceIds ?: (
                    (entry.anime as? eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Installed)
                        ?.sources.orEmpty().map { it.id } +
                        (entry.manga as? eu.kanade.tachiyomi.extension.manga.model.MangaExtension.Installed)
                            ?.sources.orEmpty().map { it.id }
                    ).toSet()
                if (staged.manifest.medium in setOf("anime", "manga")) require(actualIds.containsAll(previousIds))
                if (staged.manifest.medium == "tv") {
                    Injekt.get<eu.kanade.tachiyomi.data.tv.TvServices>().validatePackage(context, staged)
                }
                if (staged.manifest.medium == "news") {
                    Injekt.get<eu.kanade.tachiyomi.data.news.NewsRepository>().registry.preflight(inspected.info)
                }
            }
            if (mode == StoreInstallMode.PRIVATE) {
                require(!inspected.requiresAndroid)
                val signer = ExtensionPackageInspector.inspect(inspected.info) { 0 }.signers.first()
                val catalogueKey = catalogueConsent
                val staged = rin.storage.stage(
                    file,
                    signer,
                    ExtensionPackageArchive.sha256(file),
                    entry.repository.orEmpty(),
                )
                    .copy(catalogueConsent = if (previous == null) catalogueKey else previous.catalogueConsent)
                rin.activate(staged, previous, requireUnchanged = true)
                true
            } else {
                check(file.setReadOnly())
                withContext(Dispatchers.Main.immediate) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(
                            file.getUriCompat(context),
                            "application/vnd.android.package-archive",
                        )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }
                handedToAndroid = true
                false
            }
        } finally {
            // Android may open the granted URI later. Retain only artifacts handed to its installer.
            if (!handedToAndroid) file.delete()
        }
    }

    private fun systemIdentity(packageName: String): Pair<Long, Set<String>>? = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, ExtensionPackageArchive.FLAGS)
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info) to
            ExtensionPackageInspector.inspect(info) { 0 }.signers
    }.getOrNull()
}
