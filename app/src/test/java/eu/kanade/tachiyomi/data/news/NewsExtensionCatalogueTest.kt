package eu.kanade.tachiyomi.data.news

import eu.kanade.domain.extension.ExtensionDistribution
import eu.kanade.domain.extension.ExtensionPackageMetadata
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NewsExtensionCatalogueTest {
    private val signer = "a".repeat(64)
    private val repository = "https://catalogue.example/index.json"
    private fun document(key: String = signer, apk: String = "https://catalogue.example/apk/sample.apk"): String = """
        {
          "name":"Sample catalogue","badgeLabel":"Sample","signingKey":"$key",
          "contact":{"website":"https://catalogue.example/"},"media":["anime","manga","news"],
          "extensionList":{"extensions":[
            {"packageName":"example.video","medium":"anime"},
            {"packageName":"example.reader","medium":"manga"},
            {"packageName":"example.publisher","medium":"news","name":"Sample publisher",
             "versionCode":2,"versionName":"1.0.2","extensionLib":"1.0",
             "resources":{"apkUrl":"$apk"},"sha256":"${"b".repeat(64)}",
             "nyanimeDistributionId":"sample.news"}
          ]}
        }
    """.trimIndent()

    @Test fun readsOnlyNewsAndBindsTheImportedCertificate() {
        val entries = NewsExtensionCatalogue.parseEntries(document(), repository, signer)
        assertEquals(listOf("example.publisher"), entries.map { it.packageName })
        assertEquals(signer, entries.single().signer)
        assertEquals(repository, entries.single().repository)
        assertEquals("Sample catalogue", entries.single().repositoryName)
    }

    @Test fun offersReflectTheObservedSnapshotIncludingManualInstalledExtensions() {
        val catalogue = NewsExtensionCatalogue(mockk(), mockk())
        val entries = NewsExtensionCatalogue.parseEntries(document(), repository, signer)
        val installed = listOf(
            NewsExtension(
                "example.publisher",
                "Sample publisher",
                "1.0.1",
                1,
                ExtensionPackageMetadata(
                    signers = setOf(signer),
                    distribution = ExtensionDistribution(id = "sample.news", label = "Sample"),
                ),
                compatible = true,
                trusted = true,
            ),
        )
        assertTrue(catalogue.availableFor(installed, NewsExtensionCatalogue.State()).isEmpty())
        val observed = NewsExtensionCatalogue.State(entries = entries)
        assertEquals(entries, catalogue.availableFor(installed, observed))
        assertTrue(catalogue.availableFor(installed, observed.copy(unavailable = setOf(repository))).isEmpty())
    }

    @Test fun rejectsAReplacedSignerOrAnUnprotectedDownload() {
        assertTrue(
            runCatching {
                NewsExtensionCatalogue.parseEntries(document("c".repeat(64)), repository, signer)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                NewsExtensionCatalogue.parseEntries(
                    document(apk = "http://catalogue.example/a.apk"),
                    repository,
                    signer,
                )
            }.isFailure,
        )
    }

    @Test fun legacyVideoAndMangaDocumentsDoNotNeedNewsMetadata() {
        assertTrue(NewsExtensionCatalogue.parseEntries("[]", repository, signer).isEmpty())
        assertTrue(
            NewsExtensionCatalogue.parseEntries(
                """{"extensionList":{"extensions":[]}}""",
                repository,
                signer,
            ).isEmpty(),
        )
    }
}
