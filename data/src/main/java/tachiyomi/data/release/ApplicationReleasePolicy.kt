package tachiyomi.data.release

import tachiyomi.domain.release.model.ReleaseVersion
import tachiyomi.domain.release.model.UpdateChannel

/** Reject unrelated platforms and legacy aliases; release order is not numeric version order. */
object ApplicationReleasePolicy {
    fun select(
        releases: List<GithubRelease>,
        channel: UpdateChannel,
        supportedAbis: List<String>,
        assetPrefix: String = "Nyanime",
    ): GithubRelease? =
        releases.asSequence()
            .filter { !it.draft && (channel == UpdateChannel.INCLUDING_PREVIEWS || !it.prerelease) }
            .mapNotNull { release -> ReleaseVersion.parse(release.version)?.let { it to release } }
            .filter { (_, release) ->
                ApplicationReleaseAssets.select(release.assets, supportedAbis, assetPrefix) !=
                    null
            }
            .maxByOrNull { it.first }?.second
}
