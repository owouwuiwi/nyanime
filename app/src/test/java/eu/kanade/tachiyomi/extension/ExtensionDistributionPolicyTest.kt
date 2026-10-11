package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ExtensionDistribution
import eu.kanade.domain.extension.ExtensionHomeSupport
import eu.kanade.domain.extension.ExtensionPackageMetadata
import eu.kanade.domain.extension.ExtensionUpdateCandidate
import eu.kanade.domain.extension.ExtensionUpdatePolicy
import eu.kanade.domain.extension.ExtensionUpdateStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ExtensionDistributionPolicyTest {
    private val original = "a".repeat(64)
    private val local = "b".repeat(64)
    private val repo = "https://catalogue.invalid/index.json"
    private val metadata = ExtensionPackageMetadata(signers = setOf(original), home = ExtensionHomeSupport.NONE)
    private val candidate = ExtensionUpdateCandidate("example.extension", 12, repo, original, true)
    private fun resolve(
        meta: ExtensionPackageMetadata = metadata,
        candidates: List<ExtensionUpdateCandidate> = listOf(candidate),
        keep: Boolean = false,
        bound: String? = null,
        failures: Set<String> = emptySet(),
    ) =
        ExtensionUpdatePolicy.resolve("example.extension", 10, meta, candidates, keep, bound, failures)

    @Test fun catalogueMigrationKeepsSignerAndDistributionChecks() {
        val oldUrl = "https://catalogue.invalid/previous/index.json"
        val distribution =
            ExtensionDistribution(id = "example", label = "Example", updatePolicy = "repository", repository = oldUrl)
        val meta = metadata.copy(distribution = distribution)
        val migrated = candidate.copy(distributionId = "example", repositoryAliases = setOf(oldUrl))
        assertEquals(ExtensionUpdateStatus.AVAILABLE, resolve(meta, listOf(migrated)).status)
        assertEquals(
            ExtensionUpdateStatus.DIFFERENT_DISTRIBUTION,
            resolve(meta, listOf(migrated.copy(signer = local))).status,
        )
        assertEquals(
            ExtensionUpdateStatus.DIFFERENT_DISTRIBUTION,
            resolve(meta, listOf(migrated.copy(distributionId = "other"))).status,
        )
        assertEquals(ExtensionUpdateStatus.PROTECTED, resolve(meta, listOf(migrated), keep = true).status)
    }

    @Test fun newerOriginalIsEligibleOnlyWithTheMatchingSigner() {
        assertEquals(ExtensionUpdateStatus.AVAILABLE, resolve().status)
        assertEquals(
            ExtensionUpdateStatus.DIFFERENT_DISTRIBUTION,
            resolve(metadata.copy(signers = setOf(local))).status,
        )
    }

    @Test fun explicitManualDistributionIsNeverReplacedEvenWithTheSameSigner() {
        val meta = metadata.copy(distribution = ExtensionDistribution(id = "local", label = "Local"))
        assertEquals(ExtensionUpdateStatus.MANUAL, resolve(meta).status)
        assertNull(resolve(meta).candidate)
    }

    @Test fun manualUpdatesRequireAnExplicitSignerBoundRepositoryChoice() {
        val manual = metadata.copy(distribution = ExtensionDistribution(id = "local", label = "Local"))
        val update = candidate.copy(distributionId = "local")
        assertEquals(ExtensionUpdateStatus.MANUAL, resolve(manual, listOf(update)).status)
        assertEquals(ExtensionUpdateStatus.AVAILABLE, resolve(manual, listOf(update), bound = repo).status)
        assertEquals(ExtensionUpdateStatus.PROTECTED, resolve(manual, listOf(update), keep = true, bound = repo).status)
        assertEquals(
            ExtensionUpdateStatus.DIFFERENT_DISTRIBUTION,
            resolve(manual, listOf(update.copy(signer = local)), bound = repo).status,
        )
        assertEquals(
            ExtensionUpdateStatus.DIFFERENT_DISTRIBUTION,
            resolve(manual, listOf(update.copy(repository = "https://other.invalid/index.json")), bound = repo).status,
        )
    }

    @Test fun manualMigrationChecksTheDownloadedApkPublisherRepositoryAndHome() {
        val old = metadata.copy(
            home = ExtensionHomeSupport.READY,
            distribution = ExtensionDistribution(id = "local", label = "Local"),
        )
        val updated = old.copy(
            distribution = old.distribution!!.copy(updatePolicy = "repository", repository = repo),
        )
        org.junit.jupiter.api.Assertions.assertTrue(ExtensionUpdatePolicy.permitsManualTransition(old, updated, repo))
        for (invalid in listOf(
            updated.copy(signers = setOf(local)),
            updated.copy(home = ExtensionHomeSupport.NONE),
            updated.copy(distribution = updated.distribution!!.copy(id = "other")),
            old,
        )) {
            org.junit.jupiter.api.Assertions.assertFalse(
                ExtensionUpdatePolicy.permitsManualTransition(old, invalid, repo),
            )
        }
        org.junit.jupiter.api.Assertions.assertFalse(ExtensionUpdatePolicy.permitsManualTransition(old, updated, null))
    }

    @Test fun legacyHomeApksAreProtectedWithoutInferringTheirPublisherFromTheName() {
        for (home in listOf(
            ExtensionHomeSupport.READY,
            ExtensionHomeSupport.PARTIAL,
            ExtensionHomeSupport.INCOMPATIBLE,
        )) {
            assertEquals(ExtensionUpdateStatus.MANUAL, resolve(metadata.copy(home = home)).status)
        }
        assertEquals(
            ExtensionUpdateStatus.AVAILABLE,
            resolve(metadata.copy(home = ExtensionHomeSupport.READY), bound = repo).status,
        )
    }

    @Test fun signerHistoryDoesNotAllowReplacingTheCurrentCertificateWithAnOldOne() {
        val rotated = metadata.copy(signers = setOf(local), signerHistory = setOf(original, local))
        assertEquals(ExtensionUpdateStatus.DIFFERENT_DISTRIBUTION, resolve(rotated).status)
    }

    @Test fun versionProtectionOverridesCatalogueAvailabilityAndSurvivesVersionChanges() {
        assertEquals(ExtensionUpdateStatus.PROTECTED, resolve(keep = true).status)
    }

    @Test fun anotherDistributionWithTheSameCertificateIsNotAnUpdate() {
        val meta = metadata.copy(
            distribution = ExtensionDistribution(
                id = "custom",
                label = "Custom",
                updatePolicy = "repository",
                repository = repo,
            ),
        )
        assertEquals(ExtensionUpdateStatus.DIFFERENT_DISTRIBUTION, resolve(meta).status)
        assertEquals(
            ExtensionUpdateStatus.AVAILABLE,
            resolve(meta, listOf(candidate.copy(distributionId = "custom"))).status,
        )
    }

    @Test fun aLibraryChangeDoesNotAuthorizeADowngradeOrAnUnsupportedApi() {
        assertEquals(
            ExtensionUpdateStatus.CURRENT,
            resolve(candidates = listOf(candidate.copy(versionCode = 9))).status,
        )
        assertEquals(
            ExtensionUpdateStatus.CURRENT,
            resolve(candidates = listOf(candidate.copy(versionCode = 10))).status,
        )
        assertEquals(
            ExtensionUpdateStatus.NOT_IN_CATALOGUE,
            resolve(candidates = listOf(candidate.copy(compatibleApi = false))).status,
        )
    }

    @Test fun duplicateRepositoriesAreNotSelectedByIterationOrder() {
        val second = candidate.copy(repository = "https://second.invalid/index.json", versionCode = 20)
        for (candidates in listOf(listOf(candidate, second), listOf(second, candidate))) {
            assertEquals(ExtensionUpdateStatus.AMBIGUOUS, resolve(candidates = candidates).status)
            assertEquals(candidate, resolve(candidates = candidates, bound = repo).candidate)
        }
    }

    @Test fun multipleVersionsFromOneOriginResolveToTheLatestOnly() {
        val latest = candidate.copy(versionCode = 14)
        assertEquals(latest, resolve(candidates = listOf(latest, candidate, candidate)).candidate)
    }

    @Test fun missingFingerprintsAndMalformedDeclarationsRemainUnverified() {
        assertEquals(
            ExtensionUpdateStatus.UNVERIFIED,
            resolve(candidates = listOf(candidate.copy(signer = null))).status,
        )
        assertEquals(ExtensionUpdateStatus.UNVERIFIED, resolve(metadata.copy(invalidDistribution = true)).status)
        assertEquals(ExtensionUpdateStatus.UNVERIFIED, resolve(metadata.copy(signers = emptySet())).status)
    }

    @Test fun repositoryFailureIsNotObsolescenceOrAReplacementOpportunity() {
        assertEquals(
            ExtensionUpdateStatus.REPOSITORY_UNAVAILABLE,
            resolve(candidates = emptyList(), bound = repo, failures = setOf(repo)).status,
        )
        assertEquals(ExtensionUpdateStatus.NOT_IN_CATALOGUE, resolve(candidates = emptyList()).status)
        assertEquals(
            ExtensionUpdateStatus.MANUAL,
            resolve(
                metadata.copy(home = ExtensionHomeSupport.READY),
                candidates = emptyList(),
                failures = setOf(repo),
            ).status,
        )
    }

    @Test fun unavailableRepositoryCannotSupplyAStaleCachedUpdate() {
        assertEquals(ExtensionUpdateStatus.REPOSITORY_UNAVAILABLE, resolve(bound = repo, failures = setOf(repo)).status)
        assertNull(resolve(bound = repo, failures = setOf(repo)).candidate)
    }

    @Test fun packageIdentityCannotBeConfusedWithASourceRowOrAnotherPackage() {
        assertEquals(
            ExtensionUpdateStatus.NOT_IN_CATALOGUE,
            resolve(candidates = listOf(candidate.copy(packageName = "example.extension-123"))).status,
        )
    }

    @Test fun distributionContractRejectsUnsupportedAndAmbiguousPolicy() {
        assertNotNull(
            ExtensionDistribution.parse("""{"version":1,"id":"local","label":"Local","updatePolicy":"manual"}"""),
        )
        assertNull(ExtensionDistribution.parse("""{"version":2,"id":"local","label":"Local"}"""))
        assertNull(ExtensionDistribution.parse("""{"id":"local","label":"Local","updatePolicy":"repository"}"""))
        assertNull(ExtensionDistribution.parse("""{"id":"local","label":"Local","updatePolicy":"anything"}"""))
    }
}
