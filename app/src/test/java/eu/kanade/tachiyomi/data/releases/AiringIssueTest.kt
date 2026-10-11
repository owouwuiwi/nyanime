package eu.kanade.tachiyomi.data.releases

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SourceHomePresentation
import tachiyomi.domain.entries.anime.model.Anime

class AiringIssueTest {
    @Test fun titlesWithoutCatalogIdsAreNotFailuresIncludingOldUnresolvedCaches() {
        assertNull(AiringIssue.reason(null, hasId = false))
        assertNull(AiringIssue.reason(AiringCache(attemptedAt = 100, status = "UNRESOLVED"), hasId = false))
        assertNull(AiringIssue.reason(AiringCache(attemptedAt = 100, status = "UNAVAILABLE"), hasId = false))
        assertFalse(AiringCatalogReference.choose(-1, 0, null, null).hasId)
    }

    @Test fun aPendingVerificationIsDifferentFromAnInvalidIdOrConnectionFailure() {
        assertEquals(AiringIssue.Reason.PENDING, AiringIssue.reason(null, hasId = true))
        assertEquals(AiringIssue.Reason.PENDING, AiringIssue.reason(AiringCache(), hasId = true))
        assertEquals(AiringIssue.Reason.UNVERIFIED_ID, AiringIssue.reason(AiringCache(attemptedAt = 100), hasId = true))
        assertEquals(
            AiringIssue.Reason.UNAVAILABLE,
            AiringIssue.reason(AiringCache(status = "UNAVAILABLE"), hasId = true),
        )
        assertNull(AiringIssue.reason(AiringCache(status = "AVAILABLE"), hasId = true))
        assertNull(AiringIssue.reason(AiringCache(status = "UNANNOUNCED"), hasId = true))
    }

    @Test fun anExtensionProvidedCatalogReferenceDoesNotRequireALoggedInTracker() {
        val entry = Anime.create()
        val reference = AiringCatalogReference.from(
            entry.copy(memo = SourceHomePresentation(catalogIds = mapOf("anilist" to 20L)).attachTo(entry.memo)),
            emptyList(),
        )
        assertTrue(reference.hasId)
        assertEquals(20L, reference.anilistId)
    }
}
