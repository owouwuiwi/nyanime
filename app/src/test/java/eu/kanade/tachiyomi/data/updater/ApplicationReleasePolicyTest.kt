package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.release.ApplicationReleaseAssets
import tachiyomi.data.release.ApplicationReleasePolicy
import tachiyomi.data.release.GitHubAsset
import tachiyomi.data.release.GithubRelease
import tachiyomi.domain.release.model.UpdateChannel

class ApplicationReleasePolicyTest {
    private val abis = listOf("arm64-v8a")
    private fun release(version: String, preview: Boolean = false, draft: Boolean = false) = GithubRelease(
        version,
        "notes",
        "https://example.test/$version",
        listOf(GitHubAsset("Nyanime-${version.removePrefix("v")}-arm64-v8a.apk", "https://example.test/$version.apk")),
        preview,
        draft,
    )

    @Test
    fun recommendedExcludesPreviewsAndDraftsWhilePreviewIncludesBothChannels() {
        val publications = listOf(release("v0.19.0.10", true), release("v0.20.0.0", draft = true), release("v0.19.0.8"))
        assertEquals(
            "v0.19.0.8",
            ApplicationReleasePolicy.select(publications, UpdateChannel.RECOMMENDED, abis)?.version,
        )
        assertEquals(
            "v0.19.0.10",
            ApplicationReleasePolicy.select(publications, UpdateChannel.INCLUDING_PREVIEWS, abis)?.version,
        )
    }

    @Test
    fun releaseOrderAndUnrelatedPlatformsDoNotOverrideTheNewestCompatibleVersion() {
        val publications = listOf(release("v0.19.0.9"), release("tv-r9999"), release("v0.19.0.10"))
        assertEquals(
            "v0.19.0.10",
            ApplicationReleasePolicy.select(publications, UpdateChannel.RECOMMENDED, abis)?.version,
        )
        assertNull(ApplicationReleasePolicy.select(publications, UpdateChannel.RECOMMENDED, listOf("x86")))
    }

    @Test
    fun legacyClientsStillFindTheLatestBridgeAfter999NumericPublications() {
        val releases = (999 downTo 0).flatMap { number ->
            val numeric = release("v0.19.0.$number")
            val bridge = numeric.copy(
                version = "r${9000 + number}",
                assets = listOf(GitHubAsset("app-arm64-v8a-preview.apk", "https://example.test/$number.apk")),
                prerelease = true,
            )
            listOf(numeric, bridge)
        }
        // Replay the selector shipped before numeric versions, with its original 20-release window.
        val oldCandidate = releases.take(20).first { legacyDownload(it.assets, abis) != null }
        assertEquals("r9999", oldCandidate.version)
        assertEquals("https://example.test/999.apk", legacyDownload(oldCandidate.assets, abis))
        assertNull(legacyDownload(releases.first().assets, abis))
        assertTrue(oldCandidate.version.substring(1).toInt() > 8999)
        assertEquals(
            "v0.19.0.999",
            ApplicationReleasePolicy.select(releases.take(100), UpdateChannel.RECOMMENDED, abis)?.version,
        )
        assertEquals(
            "https://example.test/v0.19.0.999.apk",
            ApplicationReleaseAssets.select(releases.first().assets, abis),
        )
    }

    @Test
    fun bridgeAliasesDoNotBecomeANewUpdateForNumericClients() {
        val bridge = release(
            "r9999",
        ).copy(assets = listOf(GitHubAsset("app-universal-preview.apk", "https://example.test/bridge.apk")))
        assertNull(ApplicationReleasePolicy.select(listOf(bridge), UpdateChannel.INCLUDING_PREVIEWS, abis))
    }

    /** Frozen pre-numeric asset contract: do not replace this with the current selector. */
    private fun legacyDownload(assets: List<GitHubAsset>, supportedAbis: List<String>): String? {
        val legacyAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val stableTag = Regex("[vr][0-9][A-Za-z0-9.+-]*")
        val packages = assets.mapNotNull { asset ->
            val name = asset.name
            val abi = when {
                name == "app-universal-preview.apk" -> "universal"
                name.startsWith("Nyanime-") &&
                    name.endsWith(".apk") &&
                    name.removePrefix("Nyanime-").removeSuffix(".apk").matches(stableTag) -> "universal"
                else -> legacyAbis.firstOrNull { abi ->
                    name == "app-$abi-preview.apk" ||
                        (
                            name.startsWith("Nyanime-$abi-") &&
                                name.endsWith(".apk") &&
                                name.removePrefix("Nyanime-$abi-").removeSuffix(".apk").matches(stableTag)
                            )
                } ?: return@mapNotNull null
            }
            abi to asset.downloadLink
        }.toMap()
        return supportedAbis.firstNotNullOfOrNull { packages[it] } ?: packages["universal"]
    }
}
