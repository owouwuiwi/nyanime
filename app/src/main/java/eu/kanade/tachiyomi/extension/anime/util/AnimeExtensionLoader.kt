package eu.kanade.tachiyomi.extension.anime.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dalvik.system.PathClassLoader
import eu.kanade.domain.extension.anime.interactor.TrustAnimeExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeFilters
import eu.kanade.tachiyomi.extension.ExtensionPackageInspector
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AnimeLoadResult
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.storage.copyAndSetReadOnlyTo
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File

/**
 * Class that handles the loading of the extensions installed in the system.
 */
@SuppressLint("PackageManagerGetSignatures")
internal object AnimeExtensionLoader {

    private val preferences: SourcePreferences by injectLazy()
    private val trustExtension: TrustAnimeExtension by injectLazy()
    private val loadNsfwSource by lazy {
        preferences.showNsfwSource().get()
    }

    private const val EXTENSION_FEATURE = "tachiyomi.animeextension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.animeextension.class"
    private const val METADATA_SOURCE_FACTORY = "tachiyomi.animeextension.factory"
    private const val METADATA_NSFW = "tachiyomi.animeextension.nsfw"
    private const val METADATA_HAS_README = "tachiyomi.animeextension.hasReadme"
    private const val METADATA_HAS_CHANGELOG = "tachiyomi.animeextension.hasChangelog"
    private const val METADATA_TORRENT = "tachiyomi.animeextension.torrent"

    private const val METADATA_NAME = "aniyomix.name"
    private const val METADATA_EXTENSION_LIB = "aniyomix.extensionLib"
    private const val METADATA_CONTENT_WARNING = "aniyomix.contentWarning"
    private const val METADATA_IS_TORRENT = "aniyomix.torrent"

    val SUPPORTED_LIB_VERSIONS = listOf(14.0, 16.0, 17.0)

    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private const val PRIVATE_EXTENSION_EXTENSION = "ext"

    private fun getPrivateExtensionDir(context: Context) = File(context.filesDir, "exts")

    suspend fun installPrivateExtensionFile(context: Context, file: File): Boolean {
        val extension = context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PACKAGE_FLAGS,
        )
            ?.takeIf { isPackageAnExtension(it) } ?: return false
        val currentExtension = getAnimeExtensionPackageInfoFromPkgName(
            context,
            extension.packageName,
        )

        if (currentExtension != null) {
            if (PackageInfoCompat.getLongVersionCode(extension) <
                PackageInfoCompat.getLongVersionCode(currentExtension)
            ) {
                logcat(LogPriority.ERROR) { "Installed extension version is higher. Downgrading is not allowed." }
                return false
            }

            val extensionSignatures = getSignatures(extension)
            if (extensionSignatures.isNullOrEmpty()) {
                logcat(LogPriority.ERROR) { "Extension to be installed is not signed." }
                return false
            }

            if (!extensionSignatures.containsAll(getSignatures(currentExtension)!!)) {
                logcat(LogPriority.ERROR) { "Installed extension signature is not matched." }
                return false
            }
        }

        return try {
            val inspected = eu.kanade.tachiyomi.extension.ExtensionPackageArchive.inspect(context, file)
            val metadata = ExtensionPackageInspector.inspect(inspected.info) { 0 }
            val repository = eu.kanade.tachiyomi.data.rin.RinRepository.get(context)
            val record = repository.storage.stage(
                file,
                metadata.signers.first(),
                eu.kanade.tachiyomi.extension.ExtensionPackageArchive.sha256(file),
                repository.storage.record(extension.packageName)?.repository.orEmpty(),
            )
            val previous = repository.storage.record(extension.packageName)
            repository.activateLocal(eu.kanade.tachiyomi.data.rin.RinLocalImport(record, previous))
            true
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            logcat(LogPriority.ERROR, error) { "Private extension retained its previous version" }
            false
        }
    }

    fun uninstallPrivateExtension(context: Context, pkgName: String) {
        eu.kanade.tachiyomi.data.rin.RinRepository.get(context).remove(pkgName)
        val flags = context.getSharedPreferences("extension_retired_imports_v1", Context.MODE_PRIVATE)
        flags.edit().putStringSet("packages", flags.getStringSet("packages", emptySet()).orEmpty() + pkgName).apply()
    }

    /**
     * Return a list of all the available extensions initialized concurrently.
     *
     * @param context The application context.
     */
    fun loadExtensions(context: Context): List<AnimeLoadResult> {
        val pkgManager = context.packageManager

        val installedPkgs = eu.kanade.tachiyomi.extension.ExtensionPackageDiscovery.candidates(
            context,
            PACKAGE_FLAGS,
            setOf("tachiyomi.animeextension"),
        )

        val sharedExtPkgs = installedPkgs
            .asSequence()
            .filter { isPackageAnExtension(it) }
            .map { AnimeExtensionInfo(packageInfo = it, isShared = true) }

        val privateExtPkgs = getPrivateExtensionDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter {
                it.isFile &&
                    it.extension == PRIVATE_EXTENSION_EXTENSION &&
                    it.nameWithoutExtension !in
                    context.getSharedPreferences("extension_retired_imports_v1", Context.MODE_PRIVATE)
                        .getStringSet("packages", emptySet()).orEmpty()
            }
            ?.mapNotNull {
                // Just in case, since Android 14+ requires them to be read-only
                if (it.canWrite()) {
                    it.setReadOnly()
                }

                val path = it.absolutePath
                pkgManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
                    ?.apply { applicationInfo!!.fixBasePaths(path) }
            }
            ?.filter { isPackageAnExtension(it) }
            ?.map { AnimeExtensionInfo(packageInfo = it, isShared = false) }
            ?: emptySequence()

        val extPkgs = (sharedExtPkgs + privateExtPkgs)
            // Remove duplicates. Shared takes priority than private by default
            .distinctBy { it.packageInfo.packageName }
            // Compare version number
            .mapNotNull { sharedPkg ->
                val privatePkg = privateExtPkgs
                    .singleOrNull { it.packageInfo.packageName == sharedPkg.packageInfo.packageName }
                selectExtensionPackage(sharedPkg, privatePkg)
            }
            .toList()

        val records = eu.kanade.tachiyomi.data.rin.RinStorage(context).records()
            .filter { it.manifest.medium == "anime" }
        val activeRecords = records.filter { record ->
            preferRecord(
                context,
                record,
                extPkgs.firstOrNull {
                    it.packageInfo.packageName ==
                        record.manifest.packageName
                },
            )
        }
        val rin = activeRecords.map { eu.kanade.tachiyomi.data.rin.RinLoader.anime(context, it) }
        val rinPackages = activeRecords.map { it.manifest.packageName }.toSet()
        val apkPackages = extPkgs.filterNot { it.packageInfo.packageName in rinPackages }
        if (apkPackages.isEmpty()) return rin

        // Load each extension concurrently and wait for completion
        return runBlocking {
            val deferred = apkPackages.map {
                async { loadExtension(context, it) }
            }
            deferred.awaitAll() + rin
        }
    }

    /**
     * Attempts to load an extension from the given package name. It checks if the extension
     * contains the required feature flag before trying to load it.
     */
    suspend fun loadExtensionFromPkgName(context: Context, pkgName: String): AnimeLoadResult {
        eu.kanade.tachiyomi.data.rin.RinStorage(context).record(pkgName)?.let {
            val apk = getAnimeExtensionInfoFromPkgName(context, pkgName)
            if (preferRecord(context, it, apk)) return eu.kanade.tachiyomi.data.rin.RinLoader.anime(context, it)
        }
        val extensionPackage = getAnimeExtensionInfoFromPkgName(context, pkgName)
        if (extensionPackage == null) {
            logcat(LogPriority.ERROR) { "Extension package is not found ($pkgName)" }
            return AnimeLoadResult.Error
        }
        return loadExtension(context, extensionPackage)
    }

    fun getAnimeExtensionPackageInfoFromPkgName(context: Context, pkgName: String): PackageInfo? {
        return getAnimeExtensionInfoFromPkgName(context, pkgName)?.packageInfo
    }

    suspend fun inspectForMigration(context: Context, pkgName: String): AnimeLoadResult {
        val info = getAnimeExtensionInfoFromPkgName(context, pkgName) ?: return AnimeLoadResult.Error
        return loadExtension(context, info, includeHidden = true)
    }

    private fun getAnimeExtensionInfoFromPkgName(context: Context, pkgName: String): AnimeExtensionInfo? {
        val privateExtensionFile = File(
            getPrivateExtensionDir(context),
            "$pkgName.$PRIVATE_EXTENSION_EXTENSION",
        )
        val privatePkg = if (privateExtensionFile.isFile) {
            context.packageManager.getPackageArchiveInfo(
                privateExtensionFile.absolutePath,
                PACKAGE_FLAGS,
            )
                ?.takeIf { isPackageAnExtension(it) }
                ?.let {
                    it.applicationInfo!!.fixBasePaths(privateExtensionFile.absolutePath)
                    AnimeExtensionInfo(
                        packageInfo = it,
                        isShared = false,
                    )
                }
        } else {
            null
        }

        val sharedPkg = try {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
                .takeIf { isPackageAnExtension(it) }
                ?.let {
                    AnimeExtensionInfo(
                        packageInfo = it,
                        isShared = true,
                    )
                }
        } catch (error: PackageManager.NameNotFoundException) {
            null
        }

        return selectExtensionPackage(sharedPkg, privatePkg)
    }

    /**
     * Loads an extension
     *
     * @param context The application context.
     * @param extensionInfo The extension to load.
     */
    private suspend fun loadExtension(
        context: Context,
        extensionInfo: AnimeExtensionInfo,
        includeHidden: Boolean = false,
    ): AnimeLoadResult {
        val pkgManager = context.packageManager

        val pkgInfo = extensionInfo.packageInfo
        val appInfo = pkgInfo.applicationInfo!!
        val pkgName = pkgInfo.packageName

        val extName = appInfo.metaData.getString(METADATA_NAME)
            ?: pkgManager.getApplicationLabel(appInfo).toString().substringAfter("Aniyomi: ")
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        if (versionName.isNullOrEmpty()) {
            logcat(LogPriority.WARN) { "Missing versionName for extension $extName" }
            return AnimeLoadResult.Error
        }

        // Validate lib version
        val libVersion = appInfo.metaData.getInt(METADATA_EXTENSION_LIB)
            .takeUnless { it == 0 }
            ?.toString()
            ?.toDouble()
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
        if (libVersion == null || libVersion !in SUPPORTED_LIB_VERSIONS) {
            logcat(LogPriority.WARN) {
                "Lib version is $libVersion, while only version(s) ${SUPPORTED_LIB_VERSIONS.joinToString()} are supported"
            }
            return AnimeLoadResult.Error
        }

        val signatures = getSignatures(pkgInfo)
        val execution = eu.kanade.domain.extension.ExtensionExecutionTrust(uy.kohesive.injekt.Injekt.get())
        if (signatures.isNullOrEmpty()) {
            logcat(LogPriority.WARN) { "Package $pkgName isn't signed" }
            return AnimeLoadResult.Error
        } else if (!(trustExtension.isTrusted(pkgInfo, signatures) && execution.allowsLegacy(pkgName)) &&
            !execution.permits(
                pkgName,
                ExtensionPackageInspector.inspect(pkgInfo) { 0 },
            )
        ) {
            val extension = AnimeExtension.Untrusted(
                extName,
                pkgName,
                versionName,
                versionCode,
                libVersion,
                signatures.last(),
            )
            logcat(LogPriority.WARN, message = { "Extension $pkgName isn't trusted" })
            return AnimeLoadResult.Untrusted(extension)
        }

        val isNsfw = appInfo.metaData.getInt(METADATA_CONTENT_WARNING) > 0 ||
            appInfo.metaData.getInt(METADATA_NSFW) == 1
        if (!includeHidden && !loadNsfwSource && isNsfw) {
            logcat(LogPriority.WARN) { "NSFW extension $pkgName not allowed" }
            return AnimeLoadResult.Error
        }

        val isTorrent = appInfo.metaData.getBoolean(METADATA_IS_TORRENT) ||
            appInfo.metaData.getInt(METADATA_TORRENT) == 1
        val classLoader = try {
            ChildFirstPathClassLoader(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Extension load error: $extName ($pkgName)" }
            return AnimeLoadResult.Error
        }

        val baseSources = appInfo.metaData.getString(METADATA_SOURCE_CLASS)!!
            .split(";")
            .map {
                val sourceClass = it.trim()
                if (sourceClass.startsWith(".")) {
                    pkgInfo.packageName + sourceClass
                } else {
                    sourceClass
                }
            }
            .flatMap {
                try {
                    when (val obj = Class.forName(it, false, classLoader).getDeclaredConstructor().newInstance()) {
                        is AnimeSource -> listOf(obj)
                        is AnimeSourceFactory -> obj.createSources()
                        else -> throw Exception("Unknown source class type: ${obj.javaClass}")
                    }
                } catch (e: LinkageError) {
                    try {
                        val fallBackClassLoader = PathClassLoader(appInfo.sourceDir, null, context.classLoader)
                        when (
                            val obj = Class.forName(
                                it,
                                false,
                                fallBackClassLoader,
                            ).getDeclaredConstructor().newInstance()
                        ) {
                            is AnimeSource -> {
                                listOf(obj)
                            }
                            is AnimeSourceFactory -> obj.createSources()
                            else -> throw Exception("Unknown source class type: ${obj.javaClass}")
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: VirtualMachineError) {
                        throw e
                    } catch (e: ThreadDeath) {
                        throw e
                    } catch (e: Throwable) {
                        logcat(LogPriority.ERROR, e) { "Extension load error: $extName ($it)" }
                        return AnimeLoadResult.Error
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (e: ThreadDeath) {
                    throw e
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Extension load error: $extName ($it)" }
                    return AnimeLoadResult.Error
                }
            }

        val optional = appInfo.metaData.getString("nyanime.optional.factory")?.split(';')?.map {
            it.trim().let { name -> if (name.startsWith('.')) pkgName + name else name }
        }.orEmpty()
        val sources = eu.kanade.tachiyomi.extension.ExtensionOptionalCapabilities.attach(
            classLoader,
            optional,
            baseSources,
        )
            .map { it as AnimeSource }

        val langs = sources.map { it.lang }.toSet()
        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        val extension = AnimeExtension.Installed(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = lang,
            isNsfw = isNsfw,
            isTorrent = isTorrent,
            sources = sources,
            pkgFactory = appInfo.metaData.getString(METADATA_SOURCE_FACTORY),
            icon = appInfo.loadIcon(pkgManager),
            isShared = extensionInfo.isShared,
            metadata = withIOContext {
                ExtensionPackageInspector.inspect(pkgInfo) { manifest ->
                    sources.singleOrNull { it.name == manifest.source.name && it.lang == manifest.source.lang }
                        ?.let { source -> ExtensionHomeFilters.sections(manifest, source.getFilterList()).size } ?: 0
                }
            },
        )
        return AnimeLoadResult.Success(extension)
    }

    /**
     * Choose which extension package to use based on version code
     *
     * @param shared extension installed to system
     * @param private extension installed to data directory
     */
    private fun selectExtensionPackage(shared: AnimeExtensionInfo?, private: AnimeExtensionInfo?): AnimeExtensionInfo? {
        when {
            private == null && shared != null -> return shared
            shared == null && private != null -> return private
            shared == null && private == null -> return null
        }

        val entries = listOfNotNull(shared, private)
        val baseline = shared ?: private!!
        val pins = ExtensionPackageInspector.inspect(baseline.packageInfo) { 0 }.signers
        val candidates = entries.map { entry ->
            val metadata = ExtensionPackageInspector.inspect(entry.packageInfo) { 0 }
            val api =
                entry.packageInfo.applicationInfo?.metaData?.getInt("aniyomix.extensionLib", 0)?.takeIf {
                    it > 0
                }?.toDouble()
                    ?: entry.packageInfo.versionName?.substringBeforeLast('.')?.toDoubleOrNull()
            eu.kanade.tachiyomi.extension.ExtensionPackageChoice.Candidate(
                if (entry.isShared) "system" else "private",
                PackageInfoCompat.getLongVersionCode(entry.packageInfo),
                metadata.signers,
                metadata.signerHistory,
                api in SUPPORTED_LIB_VERSIONS && !metadata.invalidDistribution,
            )
        }
        val chosen = eu.kanade.tachiyomi.extension.ExtensionPackageChoice.select(
            candidates,
            if (baseline.isShared) "system" else "private",
            pins,
        ) ?: return null
        return entries.first { (if (it.isShared) "system" else "private") == chosen.key }
    }

    private fun preferRecord(
        context: Context,
        record: eu.kanade.tachiyomi.data.rin.RinRecord,
        apk: AnimeExtensionInfo?,
    ): Boolean {
        if (!eu.kanade.tachiyomi.data.rin.RinRepository.compatible(record.manifest, record.format)) return false
        if (apk == null) return true
        if (!eu.kanade.tachiyomi.extension.ExtensionPackageConsent.trusted(record)) return false
        if (!record.enabled) return true
        val metadata = ExtensionPackageInspector.inspect(apk.packageInfo) { 0 }
        val api =
            apk.packageInfo.applicationInfo?.metaData?.getInt("aniyomix.extensionLib", 0)?.takeIf { it > 0 }?.toDouble()
                ?: apk.packageInfo.versionName?.substringBeforeLast('.')?.toDoubleOrNull()
        val selected = eu.kanade.tachiyomi.extension.ExtensionPackageChoice.select(
            listOf(
                eu.kanade.tachiyomi.extension.ExtensionPackageChoice.Candidate(
                    "imported",
                    record.manifest.versionCode,
                    record.publisherSigners,
                    record.publisherHistory,
                ),
                eu.kanade.tachiyomi.extension.ExtensionPackageChoice.Candidate(
                    "apk",
                    PackageInfoCompat.getLongVersionCode(apk.packageInfo),
                    metadata.signers,
                    metadata.signerHistory,
                    api in SUPPORTED_LIB_VERSIONS && !metadata.invalidDistribution,
                ),
            ),
            if (record.isShared) "apk" else "imported",
            record.publisherSigners,
        )
        return selected?.key == "imported"
    }

    /**
     * Returns true if the given package is an extension.
     *
     * @param pkgInfo The package info of the application.
     */
    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        return pkgInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }
    }

    /**
     * Returns the signatures of the package or null if it's not signed.
     *
     * @param pkgInfo The package info of the application.
     * @return List SHA256 digest of the signatures
     */
    private fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo!!
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
            ?.map { Hash.sha256(it.toByteArray()) }
            ?.toList()
    }

    /**
     * On Android 13+ the ApplicationInfo generated by getPackageArchiveInfo doesn't
     * have sourceDir which breaks assets loading (used for getting icon here).
     */
    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) {
            sourceDir = apkPath
        }
        if (publicSourceDir == null) {
            publicSourceDir = apkPath
        }
    }

    private data class AnimeExtensionInfo(
        val packageInfo: PackageInfo,
        val isShared: Boolean,
    )
}
