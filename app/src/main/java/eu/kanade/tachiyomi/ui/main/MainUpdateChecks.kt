package eu.kanade.tachiyomi.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.extension.anime.api.AnimeExtensionApi
import eu.kanade.tachiyomi.extension.manga.api.MangaExtensionApi
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.util.system.updaterEnabled
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease

@Composable
internal fun MainUpdateChecks(navigator: Navigator) {
    val context = LocalContext.current

    // App updates
    LaunchedEffect(Unit) {
        if (updaterEnabled) {
            try {
                // Check the selected channel once per launch after the initial choice.
                val result = AppUpdateChecker().checkForUpdate(context, forceCheck = true)
                if (result is GetApplicationRelease.Result.NewUpdate) {
                    val updateScreen = NewUpdateScreen(
                        versionName = result.release.version,
                        changelogInfo = result.release.info,
                        releaseLink = result.release.releaseLink,
                        downloadLink = result.release.downloadLink,
                    )
                    navigator.push(updateScreen)
                }
            } catch (e: Exception) {
                context.logcat(LogPriority.ERROR, e)
            }
        }
    }

    // Extensions updates
    LaunchedEffect(Unit) {
        try {
            AnimeExtensionApi().checkForUpdates(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            context.logcat(LogPriority.ERROR, e)
        }
    }
    LaunchedEffect(Unit) {
        try {
            MangaExtensionApi().checkForUpdates(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            context.logcat(LogPriority.ERROR, e)
        }
    }
}
