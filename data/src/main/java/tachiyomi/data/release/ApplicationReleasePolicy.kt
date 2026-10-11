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
        allowGraph: Boolean = true,
    ): GithubRelease? =
        releases.asSequence()
            .filter { !it.draft && (channel == UpdateChannel.INCLUDING_PREVIEWS || !it.prerelease) }
            .mapNotNull { release -> ReleaseVersion.parse(release.version)?.let { it to release } }
            .filter { (_, release) ->
                ApplicationReleaseAssets.selectUpdate(
                    release.assets,
                    supportedAbis,
                    assetPrefix,
                    release.version,
                    allowGraph,
                ) !=
                    null
            }
            .maxByOrNull { it.first }?.second
}
