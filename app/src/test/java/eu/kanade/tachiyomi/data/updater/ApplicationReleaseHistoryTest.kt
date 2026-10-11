package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.release.ApplicationReleaseHistory
import tachiyomi.data.release.GitHubAsset
import tachiyomi.data.release.GithubRelease
import tachiyomi.domain.release.model.UpdateChannel

class ApplicationReleaseHistoryTest {
    private fun release(version: String, preview: Boolean = false, draft: Boolean = false, assets: Boolean = true) =
        GithubRelease(
            version,
            "Notes",
            "https://example.invalid/releases/$version",
            if (assets) {
                listOf(
                    GitHubAsset(
                        "Nyanime-${version.removePrefix("v")}-arm64-v8a.apk",
                        "https://example.invalid/app.apk",
                    ),
                )
            } else {
                emptyList()
            },
            preview,
            draft,
        )

    @Test
    fun `history keeps every applicable step and excludes installed aliases drafts and incompatible artifacts`() {
        val notes = ApplicationReleaseHistory.select(
            listOf(
                release("v0.41.0.3"),
                release("v0.42.0.0"),
                release("v0.41.0.2"),
                release("r8630"),
                release("v0.41.0.5", draft = true),
                release("v0.41.0.4", assets = false),
                release("v0.41.0.6", preview = true),
                release("v0.41.0.3"),
            ),
            "0.41.0.2",
            "0.42.0.0",
            UpdateChannel.RECOMMENDED,
            listOf("arm64-v8a"),
            "Nyanime",
        )
        assertEquals(listOf("0.42.0.0", "0.41.0.3"), notes.map { it.version })
    }

    @Test
    fun `preview history honors the selected channel and upper version boundary`() {
        val notes = ApplicationReleaseHistory.select(
            listOf(release("v0.41.0.6", preview = true), release("v0.43.0.0")),
            "0.41.0.2",
            "0.42.0.0",
            UpdateChannel.INCLUDING_PREVIEWS,
            listOf("arm64-v8a"),
            "Nyanime",
        )
        assertEquals(listOf("0.41.0.6"), notes.map { it.version })
    }
}
