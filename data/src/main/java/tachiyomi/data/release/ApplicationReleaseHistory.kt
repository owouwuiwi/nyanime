package tachiyomi.data.release

import tachiyomi.domain.release.model.ReleaseHistoryEntry
import tachiyomi.domain.release.model.ReleaseVersion
import tachiyomi.domain.release.model.UpdateChannel

/** History excludes compatibility aliases and releases this installation cannot consume. */
object ApplicationReleaseHistory {
    fun select(
        releases: List<GithubRelease>,
        installedVersion: String,
        throughVersion: String,
        channel: UpdateChannel,
        supportedAbis: List<String>,
        assetPrefix: String,
    ): List<ReleaseHistoryEntry> {
        val installed = ReleaseVersion.installed(installedVersion) ?: return emptyList()
        val through = ReleaseVersion.parse(throughVersion) ?: return emptyList()
        return releases.asSequence()
            .filter { !it.draft && (channel == UpdateChannel.INCLUDING_PREVIEWS || !it.prerelease) }
            .mapNotNull { release -> ReleaseVersion.parse(release.version)?.let { it to release } }
            .filter { (version, release) ->
                version > installed &&
                    version <= through &&
                    ApplicationReleaseAssets.selectUpdate(
                        release.assets,
                        supportedAbis,
                        assetPrefix,
                        release.version,
                    ) !=
                    null
            }
            .distinctBy { it.first }
            .sortedByDescending { it.first }
            .map { (version, release) ->
                ReleaseHistoryEntry(version.toString(), release.info, release.prerelease, release.publishedAt)
            }.toList()
    }
}
