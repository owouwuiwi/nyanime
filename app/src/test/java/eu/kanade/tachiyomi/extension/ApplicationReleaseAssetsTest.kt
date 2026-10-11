package eu.kanade.tachiyomi.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.data.release.ApplicationReleaseAssets
import tachiyomi.data.release.GitHubAsset

class ApplicationReleaseAssetsTest {
    private fun select(names: List<String>, abis: List<String> = listOf("arm64-v8a")) =
        ApplicationReleaseAssets.select(names.map { GitHubAsset(it, it) }, abis)

    @Test
    fun extensionApksAndOtherAttachmentsNeverReplaceUniversalApplication() {
        val names = listOf(
            "app-universal-preview.apk",
            "sample-extension-v1.0.apk",
            "SHA256SUMS",
            "sample-extension-source-v1.0.zip",
        )
        assertEquals("app-universal-preview.apk", select(names))
        assertEquals("app-universal-preview.apk", select(names.reversed()))
        assertNull(select(names.drop(1)))
    }

    @Test
    fun selectsSupportedArchitectureInDeviceOrderBeforeUniversal() {
        val names = listOf("app-universal-preview.apk", "app-armeabi-v7a-preview.apk", "app-arm64-v8a-preview.apk")
        assertEquals("app-arm64-v8a-preview.apk", select(names, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("app-armeabi-v7a-preview.apk", select(names, listOf("armeabi-v7a", "arm64-v8a")))
        assertEquals("app-universal-preview.apk", select(names, listOf("riscv64")))
        assertNull(select(names.drop(1), listOf("riscv64")))
    }

    @Test
    fun selectsNyanimeReleasePackages() {
        assertEquals(
            "Nyanime-arm64-v8a-r8241.apk",
            select(listOf("Nyanime-r8241.apk", "Nyanime-arm64-v8a-r8241.apk")),
        )
        assertEquals("Nyanime-r8241.apk", select(listOf("Nyanime-r8241.apk")))
    }

    @Test
    fun differentApplicationIdentitiesNeverSelectEachOthersPackages() {
        val names =
            listOf(
                "Nyanime-0.41.0.3-arm64-v8a.apk",
                "Nyanime-Production-0.41.0.3-arm64-v8a.apk",
                "app-universal-preview.apk",
            )
        val assets = names.map { GitHubAsset(it, it) }
        assertEquals(names[0], ApplicationReleaseAssets.select(assets, listOf("arm64-v8a")))
        assertEquals(names[1], ApplicationReleaseAssets.select(assets, listOf("arm64-v8a"), "Nyanime-Production"))
        assertNull(
            ApplicationReleaseAssets.select(
                assets.filterNot {
                    it.name == names[1]
                },
                listOf("arm64-v8a"),
                "Nyanime-Production",
            ),
        )
    }

    @Test
    fun rejectsUnsignedArchivesChecksumsAndSimilarNames() {
        assertNull(
            select(
                listOf(
                    "app-arm64-v8a-preview.apk.sha256",
                    "app-arm64-v8a-release-unsigned.apk",
                    "other-arm64-v8a.apk",
                    "prefix-Nyanime-v1.0.apk",
                    "Nyanime-it.example-v16.4.apk",
                    "aniyomi-arm64-v8a-v0.18.2.apk",
                    "app-universal-preview.zip",
                ),
            ),
        )
    }
}
