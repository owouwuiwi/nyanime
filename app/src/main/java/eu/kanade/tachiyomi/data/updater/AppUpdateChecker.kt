package eu.kanade.tachiyomi.data.updater

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.service.AppUpdatePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AppUpdateChecker {

    private val getApplicationRelease: GetApplicationRelease by injectLazy()

    suspend fun checkForUpdate(context: Context, forceCheck: Boolean = false): GetApplicationRelease.Result {
        // Disabling app update checks for older Android versions that we're going to drop support for
        // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        //    return GetApplicationRelease.Result.OsTooOld
        // }

        return withIOContext {
            val preferences = AppUpdatePreferences(Injekt.get())
            val requestedChannel = preferences.channel()
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    false,
                    BuildConfig.COMMIT_COUNT.toInt(),
                    BuildConfig.VERSION_NAME,
                    GITHUB_REPO,
                    forceCheck,
                    requestedChannel,
                    fallbackRepositories = listOf(LEGACY_GITHUB_REPO),
                    assetPrefix = BuildConfig.RELEASE_ASSET_PREFIX,
                ),
            )

            // A slow response must not notify a preview after the user selects Recommended.
            if (preferences.channel() != requestedChannel) {
                return@withIOContext GetApplicationRelease.Result.NoNewUpdate
            }

            when (result) {
                is GetApplicationRelease.Result.NewUpdate -> AppUpdateNotifier(context).promptUpdate(
                    result.release,
                )
                else -> {}
            }

            result
        }
    }
}

/** GitHub repository that publishes the fork's signed APK releases. */
const val GITHUB_REPO = "owouwuiwi/nyanime"

/** Former publisher address retained for already installed clients and migration fallback. */
const val LEGACY_GITHUB_REPO = "noire342/nyanime"

val RELEASE_TAG = "v${BuildConfig.VERSION_NAME}"

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
