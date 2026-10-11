package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.data.extensionstore.DefaultCatalogueDescriptor
import eu.kanade.tachiyomi.data.rin.RinAddonDeclaration
import eu.kanade.tachiyomi.data.rin.RinEntry
import eu.kanade.tachiyomi.data.rin.RinManifest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultCatalogueDescriptorTest {
    private val url = "https://example.test/index.json"
    private val signer = "a".repeat(64)
    private val digest = "b".repeat(64)
    private val labels = mapOf("en" to "Explore")
    private val manifest = RinManifest(
        schema = 2, packageName = "test.explore.addon", name = "Explore", versionCode = 1,
        versionName = "1.0.0", medium = "addon", extensionLib = 2.0,
        entryPoints = listOf("test.explore.addon.Entry"), sources = emptyList(), hashes = emptyMap(),
        addon = RinAddonDeclaration(2, "explore_feed", labels, labels, labels),
    )

    @Test fun `only the nominated imported Explore APK is approved`() {
        val descriptor = DefaultCatalogueDescriptor.parse(
            """{"url":"$url","signer":"$signer","exploreApkSha256":"$digest"}""",
        )
        val entry = RinEntry(manifest, "https://example.test/extension.apk", digest, signer, url, format = "apk")
        assertTrue(descriptor.permits(entry))
        assertFalse(descriptor.permits(entry.copy(repository = "https://other.test/index.json")))
        assertFalse(descriptor.permits(entry.copy(sha256 = "c".repeat(64))))
        assertFalse(descriptor.permits(entry.copy(signer = "c".repeat(64))))
        assertFalse(descriptor.permits(entry.copy(requiresAndroid = true)))
        assertFalse(descriptor.permits(entry.copy(manifest = manifest.copy(addon = null))))
    }

    @Test fun `rejects an insecure catalogue URL and malformed pins`() {
        assertThrows(IllegalArgumentException::class.java) {
            DefaultCatalogueDescriptor("http://example.test/index.json", signer, digest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DefaultCatalogueDescriptor(url, "unknown", digest)
        }
    }
}
