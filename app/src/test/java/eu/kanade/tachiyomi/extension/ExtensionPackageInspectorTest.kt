package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ExtensionDistribution
import eu.kanade.domain.extension.ExtensionHomeSupport
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionPackageInspectorTest {
    private fun withApk(entries: Map<String, String>, block: (String) -> Unit) {
        val file = Files.createTempFile("extension-contract", ".apk").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
            block(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    @Test fun readsDeclarationsFromTheSelectedApkWithoutRemoteRequests() {
        withApk(
            mapOf(
                ExtensionHomeManifest.ASSET_PATH to
                    """{"version":1,"homes":[{"id":"catalogue","title":"Catalogue","source":{"name":"Example","lang":"it"},"sections":[{"id":"recent","title":"Recent"}]}]}""",
            ),
        ) {
            assertEquals("catalogue", ExtensionPackageInspector.homes(it).single().id)
        }
    }

    @Test fun compressedOversizedAssetsCannotAllocateAnUnboundedManifest() {
        withApk(mapOf("large.json" to " ".repeat(ExtensionPackageInspector.MAX_BYTES + 1))) {
            assertNull(ExtensionPackageInspector.readAsset(it, "large.json"))
        }
    }

    @Test fun absentAndMalformedContractsDoNotPreventOrdinaryBrowsing() {
        withApk(mapOf("unrelated.json" to "{}")) { assertTrue(ExtensionPackageInspector.homes(it).isEmpty()) }
        withApk(mapOf(ExtensionHomeManifest.ASSET_PATH to "not json")) {
            assertTrue(ExtensionPackageInspector.homes(it).isEmpty())
        }
    }

    @Test fun homeCapabilitiesAreBasedOnTheCompleteContractNotSourceVisibility() {
        val home = """
            {"version":1,"homes":[{
              "id":"catalogue","title":"Catalogue",
              "source":{"name":"Example","lang":"it"},
              "sections":[{"id":"recent","title":"Recent"},{"id":"popular","title":"Popular"}]
            }]}
        """.trimIndent()
        withApk(mapOf(ExtensionHomeManifest.ASSET_PATH to home)) { path ->
            assertEquals(ExtensionHomeSupport.READY, ExtensionPackageInspector.inspectContracts(path) { 2 }.home)
            assertEquals(ExtensionHomeSupport.PARTIAL, ExtensionPackageInspector.inspectContracts(path) { 1 }.home)
            val unsupported = ExtensionPackageInspector.inspectContracts(path) { 0 }
            assertEquals(ExtensionHomeSupport.INCOMPATIBLE, unsupported.home)
        }
        withApk(emptyMap()) { path ->
            assertEquals(ExtensionHomeSupport.NONE, ExtensionPackageInspector.inspectContracts(path) { 0 }.home)
        }
    }

    @Test fun malformedDistributionAndHomeDeclarationsDoNotBecomeOrdinaryOriginalApks() {
        val entries = mapOf(
            ExtensionDistribution.ASSET_PATH to "not json",
            ExtensionHomeManifest.ASSET_PATH to "not json",
        )
        withApk(entries) { path ->
            val metadata = ExtensionPackageInspector.inspectContracts(path) { 1 }
            assertTrue(metadata.invalidDistribution)
            assertEquals(ExtensionHomeSupport.INCOMPATIBLE, metadata.home)
        }
    }
}
