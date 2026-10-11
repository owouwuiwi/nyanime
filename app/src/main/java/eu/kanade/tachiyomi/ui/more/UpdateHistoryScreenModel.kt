package eu.kanade.tachiyomi.ui.more

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.GITHUB_REPO
import eu.kanade.tachiyomi.data.updater.LEGACY_GITHUB_REPO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.ReleaseHistoryEntry
import tachiyomi.domain.release.service.AppUpdatePreferences
import tachiyomi.domain.release.service.ReleaseService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class UpdateHistoryScreenModel : ScreenModel {
    data class State(
        val releases: List<ReleaseHistoryEntry> = emptyList(),
        val loading: Boolean = true,
        val unavailable: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var requested = false

    fun load(version: String, releaseLink: String) {
        if (requested) return
        requested = true
        screenModelScope.launch {
            // Only the configured publishers can enrich this page; an incoming link cannot redirect the API client.
            val repository = listOf(GITHUB_REPO, LEGACY_GITHUB_REPO).firstOrNull {
                releaseLink.startsWith("https://github.com/$it/releases/")
            }
            if (repository == null) {
                mutableState.value = State(loading = false)
                return@launch
            }
            try {
                val releases = withTimeoutOrNull(10_000) {
                    withContext(Dispatchers.IO) {
                        Injekt.get<ReleaseService>().history(
                            GetApplicationRelease.Arguments(
                                isPreview = false,
                                commitCount = BuildConfig.COMMIT_COUNT.toInt(),
                                versionName = BuildConfig.VERSION_NAME,
                                repository = repository,
                                channel = AppUpdatePreferences(Injekt.get()).channel(),
                                assetPrefix = BuildConfig.RELEASE_ASSET_PREFIX,
                            ),
                            version,
                        )
                    }
                }
                mutableState.value = State(releases.orEmpty(), loading = false, unavailable = releases == null)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = State(loading = false, unavailable = true)
            }
        }
    }
}
