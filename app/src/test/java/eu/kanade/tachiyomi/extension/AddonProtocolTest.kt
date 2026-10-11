package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.AddonActivation
import eu.kanade.domain.extension.AddonHostVersions
import eu.kanade.tachiyomi.data.addon.AddonExtensionCatalogue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddonProtocolTest {
    @Test fun hostBoundsAreOptionalInclusiveAndRejectMalformedRanges() {
        assertTrue(AddonHostVersions().supports(162))
        val minimum = AddonHostVersions(minimum = 162, minimumName = "0.29.0.5")
        assertEquals(AddonHostVersions.Status.TOO_OLD, minimum.status(161))
        assertTrue(minimum.supports(162))
        assertTrue(minimum.supports(200))
        val bounded = minimum.copy(maximum = 170, maximumName = "0.30.0")
        assertTrue(bounded.supports(170))
        assertEquals(AddonHostVersions.Status.TOO_NEW, bounded.status(171))
        assertTrue(AddonHostVersions(maximum = 170).supports(1))
        listOf(
            AddonHostVersions(minimum = 0),
            AddonHostVersions(maximum = -1),
            AddonHostVersions(minimum = 170, maximum = 162),
            AddonHostVersions(minimumName = "0.29.0.5"),
            AddonHostVersions(minimum = 162, minimumName = "x\n"),
            AddonHostVersions(maximum = Long.MAX_VALUE),
        )
            .forEach { assertEquals(AddonHostVersions.Status.INVALID, it.status(162)) }
    }

    @Test fun catalogueRetainsHostBoundsWithoutHidingIncompatibleEntries() {
        val bounded =
            entry.removeSuffix("}") +
                ",\"minHostVersionCode\":162,\"minHostVersionName\":\"0.29.0.5\",\"maxHostVersionCode\":170}"
        val parsed = AddonExtensionCatalogue.parseEntries(catalogue(bounded), repository, signer).single()
        assertTrue(parsed.hostVersions.supports(162))
        assertFalse(parsed.hostVersions.supports(161))
        assertEquals(170L, parsed.maxHostVersionCode)
        assertThrows(IllegalArgumentException::class.java) {
            AddonExtensionCatalogue.parseEntries(catalogue(bounded.replace(":170", ":160")), repository, signer)
        }
    }

    @Test fun moreActionsRequireTheNewApiAndDoNotReplaceTheLogoGesture() {
        assertNull(AddonActivation.parse("more_action", 0, 1))
        assertTrue(requireNotNull(AddonActivation.parse("more_action", 0, 2)).isMoreAction)
        assertNull(AddonActivation.parse("more_action", 1500, 2))
        assertNull(AddonActivation.parse("more_action", 0, 3))
        assertFalse(requireNotNull(AddonActivation.parse("hold_logo", 1500, 2)).isMoreAction)
    }

    @Test fun newActionSectionCoexistsWithExistingModesAndRejectsDuplicatePackages() {
        val action = entry.replace(
            "example.addon.reader",
            "example.addon.utility",
        ).replace("\"extensionLib\":\"1.0\"", "\"extensionLib\":\"2.0\"")
        val document =
            catalogue().trimEnd().removeSuffix("}") + ",\"addonActions\":{\"version\":1,\"extensions\":[$action]}}"
        val result = AddonExtensionCatalogue.parseEntries(document, repository, signer)
        assertEquals(listOf("1.0", "2.0"), result.map { it.extensionLib })
        assertThrows(IllegalArgumentException::class.java) {
            AddonExtensionCatalogue.parseEntries(
                document.replace("example.addon.utility", "example.addon.reader"),
                repository,
                signer,
            )
        }
    }

    private val signer = "a".repeat(64)
    private val repository = "https://catalogue.invalid/index.json"
    private val entry = """{"packageName":"example.addon.reader","name":"Reader mode","medium":"addon","versionCode":1,"versionName":"1.0.1","extensionLib":"1.0","sha256":"${"b".repeat(
        64,
    )}","nyanimeDistributionId":"example.reader","resources":{"apkUrl":"https://catalogue.invalid/reader.apk"}}"""
    private fun catalogue(entries: String = entry, version: Int = 1) = """{
        "name":"Test catalogue","badgeLabel":"Test","signingKey":"$signer",
        "contact":{"website":"https://catalogue.invalid/"},"media":["anime","manga","news"],
        "extensionList":{"extensions":[]},"addonList":{"version":$version,"extensions":[$entries]}
    }"""

    @Test fun boundedDeclarativeGestureRejectsUnknownCodeAndOutOfRangeValues() {
        assertNotNull(AddonActivation.parse("hold_logo", 1500))
        assertNull(AddonActivation.parse("javascript", 1500))
        assertNull(AddonActivation.parse("hold_logo", 1))
        assertNull(AddonActivation.parse("hold_logo", 3001))
    }

    @Test fun trustRequiresTheApprovedDistributionAndCurrentSignerOrValidRotation() {
        assertTrue(AddonActivation.trusted(setOf("a"), setOf("a"), setOf("a"), "one", "one"))
        assertTrue(AddonActivation.trusted(setOf("a"), setOf("b"), setOf("a", "b"), "one", "one"))
        assertFalse(AddonActivation.trusted(emptySet(), setOf("a"), setOf("a"), "one", "one"))
        assertFalse(AddonActivation.trusted(setOf("a"), setOf("b"), setOf("b"), "one", "one"))
        assertFalse(AddonActivation.trusted(setOf("a"), setOf("a"), setOf("a"), "one", "two"))
        assertFalse(AddonActivation.trusted(setOf("a"), setOf("a", "b"), setOf("a", "b"), "one", "one"))
    }

    @Test fun optionalCatalogueSectionPreservesLegacyMediaAndUsesPinnedSigner() {
        val result = AddonExtensionCatalogue.parseEntries(catalogue(), repository, signer).single()
        assertEquals("example.addon.reader", result.packageName)
        assertEquals(repository, result.repository)
        assertEquals(signer, result.signer)
        assertThrows(IllegalArgumentException::class.java) {
            AddonExtensionCatalogue.parseEntries(catalogue(), repository, "c".repeat(64))
        }
    }

    @Test fun unsupportedVersionDuplicatePackagesAndInsecureDownloadsAreRejected() {
        listOf(
            catalogue(version = 2),
            catalogue("$entry,$entry"),
            catalogue().replace("https://catalogue.invalid/reader.apk", "http://catalogue.invalid/reader.apk"),
        )
            .forEach { data ->
                assertThrows(IllegalArgumentException::class.java) {
                    AddonExtensionCatalogue.parseEntries(data, repository, signer)
                }
            }
    }

    @Test fun missingOptionalSectionDoesNotBreakExistingCatalogues() {
        val document = catalogue().substringBefore("\"addonList\"").trimEnd().removeSuffix(",") + "}"
        assertTrue(AddonExtensionCatalogue.parseEntries(document, repository, signer).isEmpty())
    }
}
