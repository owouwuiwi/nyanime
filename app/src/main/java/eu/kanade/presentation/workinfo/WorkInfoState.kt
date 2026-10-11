package eu.kanade.presentation.workinfo

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.workinfo.WorkBinding
import eu.kanade.tachiyomi.data.workinfo.WorkIdentity
import eu.kanade.tachiyomi.data.workinfo.WorkIdentityMatcher
import eu.kanade.tachiyomi.data.workinfo.WorkInfoPreferences
import eu.kanade.tachiyomi.data.workinfo.WorkInfoRepository
import eu.kanade.tachiyomi.data.workinfo.WorkInformation
import eu.kanade.tachiyomi.data.workinfo.WorkReference
import eu.kanade.tachiyomi.data.workinfo.WorkResolution
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.networkStateFlow
import kotlinx.coroutines.CancellationException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Stable
internal class WorkInfoState(val identity: WorkIdentity) {
    var enabled by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var information by mutableStateOf<WorkInformation?>(null)
    var resolution by mutableStateOf(WorkResolution())
    var failed by mutableStateOf(false)
    var choosing by mutableStateOf(false)
    var network by mutableStateOf(false)
    var incognito by mutableStateOf(false)
    var language by mutableStateOf("en")
    var revision by mutableIntStateOf(0)
    var sessionBinding by mutableStateOf<WorkBinding?>(null)

    fun select(reference: WorkReference?) {
        sessionBinding = WorkBinding(reference)
        if (!incognito) Injekt.get<WorkInfoPreferences>().associate(identity, reference)
        information = null
        choosing = false
        revision++
    }
}

@Composable
internal fun rememberWorkInfo(identity: WorkIdentity, sourceId: Long, manga: Boolean): WorkInfoState {
    val state = remember(identity) { WorkInfoState(identity) }
    val preferences = remember { Injekt.get<WorkInfoPreferences>() }
    val consentPreference = remember { preferences.consent() }
    val consent by consentPreference.changes().collectAsState(consentPreference.get())
    val policy = rememberWorkInfoPolicy(sourceId, manga)
    val repository = remember { Injekt.get<WorkInfoRepository>() }

    LaunchedEffect(consent, policy, state.revision, identity) {
        state.enabled = consent == WorkInfoPreferences.ENABLED
        state.network = policy.network
        state.incognito = policy.incognito
        state.language = policy.language
        if (!state.enabled) {
            state.information = null
            state.loading = false
            return@LaunchedEffect
        }
        state.loading = true
        state.failed = false
        try {
            val binding = state.sessionBinding ?: preferences.binding(identity)
            val resolution = repository.resolve(identity, binding, policy.language, policy.network, !policy.incognito)
            state.resolution = resolution
            val reference = resolution.reference
            if (reference != null) {
                val cached = repository.cachedInformation(reference, policy.language)
                    ?.takeIf { binding != null || WorkIdentityMatcher.compatible(identity, it.work) }
                state.information = cached ?: state.information?.takeIf {
                    it.work.reference == reference &&
                        (binding != null || WorkIdentityMatcher.compatible(identity, it.work))
                }
                val information = repository.information(reference, policy.language, policy.network, !policy.incognito)
                if (binding == null &&
                    !WorkIdentityMatcher.compatible(identity, information.work)
                ) {
                    state.information = null
                    state.resolution = WorkResolution(candidates = listOf(information.work))
                } else {
                    state.information = information
                    if (binding == null) repository.rememberResolution(identity, information, !policy.incognito)
                }
            } else {
                state.information = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state.failed = true
        } finally {
            state.loading = false
        }
    }
    if (consent == WorkInfoPreferences.UNKNOWN) {
        AlertDialog(
            onDismissRequest = { consentPreference.set(WorkInfoPreferences.DISABLED) },
            title = { Text(stringResource(R.string.work_info_prompt)) },
            text = { Text(stringResource(R.string.work_info_prompt_body)) },
            confirmButton = {
                TextButton(onClick = { consentPreference.set(WorkInfoPreferences.ENABLED) }) {
                    Text(stringResource(R.string.work_info_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { consentPreference.set(WorkInfoPreferences.DISABLED) }) {
                    Text(stringResource(R.string.work_info_later))
                }
            },
        )
    }
    return state
}

internal data class WorkInfoPolicy(val network: Boolean, val incognito: Boolean, val language: String)

@Composable
internal fun rememberWorkInfoPolicy(
    sourceId: Long? = null,
    manga: Boolean = false,
    privateSession: Boolean = false,
): WorkInfoPolicy {
    val base = remember { Injekt.get<BasePreferences>() }
    val downloaded by remember { base.downloadedOnly() }.changes().collectAsState(base.downloadedOnly().get())
    val sourceIncognito = remember(sourceId, manga) {
        if (manga) {
            Injekt.get<GetMangaIncognitoState>().subscribe(
                sourceId,
            )
        } else {
            Injekt.get<GetAnimeIncognitoState>().subscribe(sourceId)
        }
    }
    val initialIncognito = remember(sourceId, manga) {
        if (manga) {
            Injekt.get<GetMangaIncognitoState>().await(
                sourceId,
            )
        } else {
            Injekt.get<GetAnimeIncognitoState>().await(sourceId)
        }
    }
    val incognito by sourceIncognito.collectAsState(initialIncognito)
    val context = LocalContext.current
    val network by remember(context) { context.networkStateFlow() }.collectAsState(context.activeNetworkState())
    val language = LocalConfiguration.current.locales[0]?.language?.takeIf { it in setOf("it", "en") } ?: "en"
    return WorkInfoPolicy(network.isOnline && !downloaded, incognito || privateSession, language)
}
