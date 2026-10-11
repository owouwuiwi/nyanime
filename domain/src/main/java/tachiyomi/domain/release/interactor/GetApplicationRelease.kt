package tachiyomi.domain.release.interactor

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseVersion
import tachiyomi.domain.release.model.UpdateChannel
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant
import java.time.temporal.ChronoUnit

class GetApplicationRelease(
    private val service: ReleaseService,
    private val preferenceStore: PreferenceStore,
) {

    suspend fun await(arguments: Arguments): Result {
        val now = Instant.now()
        val lastChecked = preferenceStore.getLong(
            Preference.appStateKey("last_app_check_${arguments.repository}_${arguments.channel.key}"),
            0,
        )

        // Limit checks to once every 3 days at most
        if (!arguments.forceCheck &&
            now.isBefore(
                Instant.ofEpochMilli(lastChecked.get()).plus(3, ChronoUnit.DAYS),
            )
        ) {
            return Result.NoNewUpdate
        }

        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        lastChecked.set(now.toEpochMilli())

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.isPreview,
            arguments.commitCount,
            arguments.versionName,
            release.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private fun isNewVersion(
        isPreview: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        val candidate = ReleaseVersion.parse(versionTag)
        val installed = ReleaseVersion.installed(versionName)
        if (candidate != null && installed != null) return candidate > installed
        // Only installations of the old updater can consume the legacy compatibility alias.
        return if (isPreview && ReleaseVersion.parse(versionName) == null) {
            val newCommitCount = PREVIEW_TAG_REGEX
                .matchEntire(versionTag)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            newCommitCount != null && newCommitCount > commitCount
        } else {
            false
        }
    }

    data class Arguments(
        val isPreview: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        val forceCheck: Boolean = false,
        val channel: UpdateChannel = if (isPreview) UpdateChannel.INCLUDING_PREVIEWS else UpdateChannel.RECOMMENDED,
        val fallbackRepositories: List<String> = emptyList(),
        val assetPrefix: String = "Nyanime",
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }

    private companion object {
        val PREVIEW_TAG_REGEX = Regex("^r(\\d+)$", RegexOption.IGNORE_CASE)
    }
}
