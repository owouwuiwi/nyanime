package eu.kanade.tachiyomi.extension.manga

import android.content.Context
import android.graphics.drawable.Drawable
import eu.kanade.domain.extension.ExtensionUpdateCandidate
import eu.kanade.domain.extension.ExtensionUpdatePolicy
import eu.kanade.domain.extension.ExtensionUpdateRepository
import eu.kanade.domain.extension.ExtensionUpdateStatus
import eu.kanade.domain.extension.manga.interactor.TrustMangaExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.ExtensionUpdatePreferences
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.manga.api.MangaExtensionApi
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaLoadResult
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionInstaller
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionLoader
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.manga.model.StubMangaSource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * The manager of extensions installed as another apk which extend the available sources. It handles
 * the retrieval of remotely available extensions as well as installing, updating and removing them.
 * To avoid malicious distribution, every extension must be signed and it will only be loaded if its
 * signature is trusted, otherwise the user will be prompted with a warning to trust it before being
 * loaded.
 */
class MangaExtensionManager(
    private val context: Context,
    private val preferences: SourcePreferences = Injekt.get(),
    private val trustExtension: TrustMangaExtension = Injekt.get(),
) {

    val scope = CoroutineScope(SupervisorJob())

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * API where all the available extensions can be found.
     */
    private val api = MangaExtensionApi()

    /**
     * The installer which installs, updates and uninstalls the extensions.
     */
    private val installer by lazy { MangaExtensionInstaller(context) }

    private val updatePreferences = ExtensionUpdatePreferences(Injekt.get(), "manga")
    var unavailableRepositories: Set<String> = emptySet()
        private set

    private val iconMap = mutableMapOf<String, Drawable>()

    private val installedExtensionsMapFlow = MutableStateFlow(emptyMap<String, MangaExtension.Installed>())
    val installedExtensionsFlow = installedExtensionsMapFlow.mapExtensions(scope)

    private val availableExtensionsMapFlow = MutableStateFlow(emptyMap<String, MangaExtension.Available>())
    val availableExtensionsFlow = availableExtensionsMapFlow.mapExtensions(scope)

    private val untrustedExtensionsMapFlow = MutableStateFlow(emptyMap<String, MangaExtension.Untrusted>())
    val untrustedExtensionsFlow = untrustedExtensionsMapFlow.mapExtensions(scope)

    init {
        initExtensions()
        MangaExtensionInstallReceiver(InstallationListener()).register(context)
    }

    private var subLanguagesEnabledOnFirstRun = preferences.enabledLanguages().isSet()

    fun getExtensionPackage(sourceId: Long): String? {
        return installedExtensionsFlow.value.find { extension ->
            extension.sources.any { it.id == sourceId }
        }
            ?.pkgName
    }

    fun getExtensionPackageAsFlow(sourceId: Long): Flow<String?> {
        return installedExtensionsFlow.map { extensions ->
            extensions.find { extension ->
                extension.sources.any { it.id == sourceId }
            }
                ?.pkgName
        }
    }

    fun getAppIconForSource(sourceId: Long): Drawable? {
        val pkgName = installedExtensionsMapFlow.value.values
            .find { ext ->
                ext.sources.any { it.id == sourceId }
            }
            ?.pkgName
            ?: return null

        return iconMap[pkgName] ?: iconMap.getOrPut(pkgName) {
            val rin = eu.kanade.tachiyomi.data.rin.RinStorage(context).record(pkgName)
            rin?.let { eu.kanade.tachiyomi.data.rin.RinLoader.icon(context, it) }
                ?: MangaExtensionLoader.getMangaExtensionPackageInfoFromPkgName(context, pkgName)?.applicationInfo
                    ?.loadIcon(context.packageManager) ?: return null
        }
    }

    private var availableExtensionsSourcesData: Map<Long, StubMangaSource> = emptyMap()

    private fun setupAvailableExtensionsSourcesDataMap(extensions: List<MangaExtension.Available>) {
        if (extensions.isEmpty()) return
        availableExtensionsSourcesData = extensions
            .flatMap { ext -> ext.sources.map { it.toStubSource() } }
            .associateBy { it.id }
    }

    fun getSourceData(id: Long) = availableExtensionsSourcesData[id]

    /**
     * Loads and registers the installed extensions.
     */
    private fun initExtensions() {
        val extensions = MangaExtensionLoader.loadMangaExtensions(context)

        installedExtensionsMapFlow.value = extensions
            .filterIsInstance<MangaLoadResult.Success>()
            .associate { it.extension.pkgName to it.extension.withUpdateCheck() }

        untrustedExtensionsMapFlow.value = extensions
            .filterIsInstance<MangaLoadResult.Untrusted>()
            .associate { it.extension.pkgName to it.extension }

        updatePendingUpdatesCount()
        _isInitialized.value = true
    }

    /**
     * Finds the available extensions in the [api] and updates [availableExtensionsMapFlow].
     */
    suspend fun findAvailableExtensions() {
        val extensions: List<MangaExtension.Available> = try {
            api.findExtensions()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            withUIContext { context.toast(MR.strings.extension_api_error) }
            emptyList()
        }

        enableAdditionalSubLanguages(extensions)

        availableExtensionsMapFlow.value = extensions.associateBy { it.pkgName + "|" + it.repoUrl }
        unavailableRepositories = api.unavailableRepositories
        updatedInstalledExtensionsStatuses(extensions)
        setupAvailableExtensionsSourcesDataMap(extensions)
    }

    /**
     * Enables the additional sub-languages in the app first run. This addresses
     * the issue where users still need to enable some specific languages even when
     * the device language is inside that major group. As an example, if a user
     * has a zh device language, the app will also enable zh-Hans and zh-Hant.
     *
     * If the user have already changed the enabledLanguages preference value once,
     * the new languages will not be added to respect the user enabled choices.
     */
    private fun enableAdditionalSubLanguages(extensions: List<MangaExtension.Available>) {
        if (subLanguagesEnabledOnFirstRun || extensions.isEmpty()) {
            return
        }

        // Use the source lang as some aren't present on the extension level.
        val availableLanguages = extensions
            .flatMap(MangaExtension.Available::sources)
            .distinctBy(MangaExtension.Available.MangaSource::lang)
            .map(MangaExtension.Available.MangaSource::lang)

        val deviceLanguage = Locale.getDefault().language
        val defaultLanguages = preferences.enabledLanguages().defaultValue()
        val languagesToEnable = availableLanguages.filter {
            it != deviceLanguage && it.startsWith(deviceLanguage)
        }

        preferences.enabledLanguages().set(defaultLanguages + languagesToEnable)
        subLanguagesEnabledOnFirstRun = true
    }

    /**
     * Sets the update field of the installed extensions with the given [availableExtensions].
     *
     * @param availableExtensions The list of extensions given by the [api].
     */
    private fun updatedInstalledExtensionsStatuses(availableExtensions: List<MangaExtension.Available>) {
        installedExtensionsMapFlow.value = installedExtensionsMapFlow.value.mapValues { (_, extension) ->
            assessed(extension, availableExtensions)
        }
        updatePendingUpdatesCount()
    }

    fun assessUpdates(
        extensions: List<MangaExtension.Installed>,
        candidates: List<MangaExtension.Available>,
        unavailable: Set<String>,
    ): List<MangaExtension.Installed> {
        unavailableRepositories = unavailable
        availableExtensionsMapFlow.value = candidates.associateBy { it.pkgName + "|" + it.repoUrl }
        updatedInstalledExtensionsStatuses(candidates)
        return extensions.map { assessed(it, candidates) }
    }

    private fun candidate(extension: MangaExtension.Available) = ExtensionUpdateCandidate(
        packageName = extension.pkgName,
        versionCode = extension.versionCode,
        repository = extension.repoUrl,
        signer = extension.expectedSigner?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) },
        compatibleApi = extension.libVersion in MangaExtensionLoader.SUPPORTED_LIB_VERSIONS,
        distributionId = extension.distributionId,
        repositoryAliases = extension.repositoryAliases,
    )

    private fun decide(extension: MangaExtension.Installed, candidates: List<MangaExtension.Available>) =
        ExtensionUpdatePolicy.resolve(
            packageName = extension.pkgName,
            versionCode = extension.versionCode,
            metadata = extension.metadata,
            candidates = candidates.map(::candidate),
            keepVersion = updatePreferences.keep(extension.pkgName, extension.metadata),
            boundRepository = updatePreferences.repository(extension.pkgName, extension.metadata),
            unavailableRepositories = unavailableRepositories,
        )

    private fun assessed(
        extension: MangaExtension.Installed,
        candidates: List<MangaExtension.Available>,
    ): MangaExtension.Installed {
        val decision = decide(extension, candidates)
        val chosen = decision.candidate?.let { selected -> candidates.firstOrNull { candidate(it) == selected } }
        if (chosen != null) updatePreferences.bind(extension.pkgName, extension.metadata, candidate(chosen).repository)
        return extension.copy(
            hasUpdate = decision.status == ExtensionUpdateStatus.AVAILABLE,
            isObsolete = false,
            updateStatus = decision.status,
            keepVersion = updatePreferences.keep(extension.pkgName, extension.metadata),
            repoUrl = chosen?.repoUrl ?: extension.repoUrl,
            repoName = chosen?.repoName ?: extension.repoName,
        )
    }

    fun updateRepositories(extension: MangaExtension.Installed): List<ExtensionUpdateRepository> {
        if (extension.metadata.distribution?.updatePolicy != "manual" ||
            updatePreferences.keep(extension.pkgName, extension.metadata)
        ) {
            return emptyList()
        }
        return availableExtensionsMapFlow.value.values.mapNotNull { available ->
            val repository = available.repoUrl
            val decision = ExtensionUpdatePolicy.resolve(
                extension.pkgName,
                extension.versionCode,
                extension.metadata,
                listOf(candidate(available)),
                boundRepository = repository,
                unavailableRepositories = unavailableRepositories,
            )
            if (decision.candidate ==
                null
            ) {
                null
            } else {
                ExtensionUpdateRepository(available.repoName ?: repository, repository)
            }
        }.distinctBy { it.url }
    }

    fun bindUpdateRepository(extension: MangaExtension.Installed, repository: String): Boolean {
        if (updateRepositories(extension).none { it.url == repository }) return false
        updatePreferences.bind(extension.pkgName, extension.metadata, repository)
        updatedInstalledExtensionsStatuses(availableExtensionsMapFlow.value.values.toList())
        return true
    }

    fun approvedManualRepository(extension: MangaExtension.Installed): String? =
        updatePreferences.repository(extension.pkgName, extension.metadata)
            .takeUnless { updatePreferences.keep(extension.pkgName, extension.metadata) }

    /**
     * Returns a flow of the installation process for the given extension. It will complete
     * once the extension is installed or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be installed.
     */
    fun installExtension(extension: MangaExtension.Available): Flow<InstallStep> {
        val installed = installedExtensionsMapFlow.value[extension.pkgName]
        if (installed != null && !isInstallationAllowed(extension.pkgName, extension.versionCode, extension.repoUrl)) {
            return kotlinx.coroutines.flow.flowOf(InstallStep.Error)
        }
        return installer.downloadAndInstall(extension.apkUrl, extension)
    }

    /**
     * Returns a flow of the installation process for the given extension. It will complete
     * once the extension is updated or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be updated.
     */
    fun updateExtension(extension: MangaExtension.Installed): Flow<InstallStep> {
        val decision = decide(extension, availableExtensionsMapFlow.value.values.toList())
        if (decision.status != ExtensionUpdateStatus.AVAILABLE) return kotlinx.coroutines.flow.flowOf(InstallStep.Error)
        val availableExt = availableExtensionsMapFlow.value.values.firstOrNull {
            candidate(it) == decision.candidate
        } ?: return kotlinx.coroutines.flow.flowOf(InstallStep.Error)
        return installExtension(availableExt)
    }

    fun isInstallationAllowed(packageName: String, versionCode: Long, repository: String?): Boolean {
        val installed = installedExtensionsMapFlow.value[packageName] ?: return true
        val decision = decide(installed, availableExtensionsMapFlow.value.values.toList())
        return decision.status == ExtensionUpdateStatus.AVAILABLE &&
            decision.candidate?.versionCode == versionCode &&
            decision.candidate?.repository == repository
    }

    fun cancelInstallUpdateExtension(extension: MangaExtension) {
        installer.cancelInstall(extension.pkgName)
    }

    /**
     * Sets to "installing" status of an extension installation.
     *
     * @param downloadId The id of the download.
     */
    fun setInstalling(downloadId: Long) {
        installer.updateInstallStep(downloadId, InstallStep.Installing)
    }

    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        installer.updateInstallStep(downloadId, step)
    }

    /**
     * Uninstalls the extension that matches the given package name.
     *
     * @param extension The extension to uninstall.
     */
    fun uninstallExtension(extension: MangaExtension) {
        val rin = eu.kanade.tachiyomi.data.rin.RinRepository.get(context)
        if (rin.storage.record(extension.pkgName) != null) {
            rin.remove(extension.pkgName)
        } else {
            installer.uninstallApk(extension.pkgName)
        }
    }

    /**
     * Adds the given extension to the list of trusted extensions. It also loads in background the
     * now trusted extensions.
     *
     * @param extension the extension to trust
     */
    suspend fun trust(extension: MangaExtension.Untrusted) {
        untrustedExtensionsMapFlow.value[extension.pkgName] ?: return

        trustExtension.trust(extension.pkgName, extension.versionCode, extension.signatureHash)

        untrustedExtensionsMapFlow.value -= extension.pkgName

        MangaExtensionLoader.loadMangaExtensionFromPkgName(context, extension.pkgName)
            .let { it as? MangaLoadResult.Success }
            ?.let { registerNewExtension(it.extension) }
    }

    /**
     * Registers the given extension in this and the source managers.
     *
     * @param extension The extension to be registered.
     */
    private fun registerNewExtension(extension: MangaExtension.Installed) {
        installedExtensionsMapFlow.value += extension
    }

    /**
     * Registers the given updated extension in this and the source managers previously removing
     * the outdated ones.
     *
     * @param extension The extension to be registered.
     */
    private fun registerUpdatedExtension(extension: MangaExtension.Installed) {
        installedExtensionsMapFlow.value += extension
    }

    /**
     * Unregisters the extension in this and the source managers given its package name. Note this
     * method is called for every uninstalled application in the system.
     *
     * @param pkgName The package name of the uninstalled application.
     */
    private fun unregisterExtension(pkgName: String) {
        installedExtensionsMapFlow.value -= pkgName
        untrustedExtensionsMapFlow.value -= pkgName
    }

    /**
     * Listener which receives events of the extensions being installed, updated or removed.
     */
    private inner class InstallationListener : MangaExtensionInstallReceiver.Listener {

        override fun onExtensionInstalled(extension: MangaExtension.Installed) {
            registerNewExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
        }

        override fun onExtensionUpdated(extension: MangaExtension.Installed) {
            registerUpdatedExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
        }

        override fun onExtensionUntrusted(extension: MangaExtension.Untrusted) {
            installedExtensionsMapFlow.value -= extension.pkgName
            untrustedExtensionsMapFlow.value += extension
            updatePendingUpdatesCount()
        }

        override fun onPackageUninstalled(pkgName: String) {
            MangaExtensionLoader.uninstallPrivateExtension(context, pkgName)
            if (eu.kanade.tachiyomi.data.rin.RinStorage(context).record(pkgName)?.manifest?.medium == "manga") return
            unregisterExtension(pkgName)
            updatePendingUpdatesCount()
        }
    }

    /**
     * Extension method to set the update field of an installed extension.
     */
    private fun MangaExtension.Installed.withUpdateCheck(): MangaExtension.Installed =
        assessed(this, availableExtensionsMapFlow.value.values.toList())

    private fun updatePendingUpdatesCount() {
        val pendingUpdateCount = installedExtensionsMapFlow.value.values.count { it.hasUpdate }
        preferences.mangaExtensionUpdatesCount().set(pendingUpdateCount)
        if (pendingUpdateCount == 0) {
            ExtensionUpdateNotifier(context).dismiss()
        }
    }

    private operator fun <T : MangaExtension> Map<String, T>.plus(extension: T) = plus(extension.pkgName to extension)

    private fun <T : MangaExtension> StateFlow<Map<String, T>>.mapExtensions(
        scope: CoroutineScope,
    ): StateFlow<List<T>> {
        return map { it.values.toList() }.stateIn(scope, SharingStarted.Lazily, value.values.toList())
    }
}
