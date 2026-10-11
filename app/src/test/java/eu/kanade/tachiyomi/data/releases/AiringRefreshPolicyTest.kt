package eu.kanade.tachiyomi.data.releases

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiringRefreshPolicyTest {
    private val now = 10 * ReleasePolicy.DAY

    @Test fun failureAfterARecentSuccessfulVerificationIsRetried() {
        val cache = AiringCache(now - ReleasePolicy.HOUR, now - 10 * ReleasePolicy.MINUTE, "UNAVAILABLE")
        assertTrue(AiringRefreshPolicy.shouldRefresh(cache, now, hasUpcoming = true))
        assertTrue(AiringRefreshPolicy.shouldRefresh(cache, now, hasUpcoming = false))
    }

    @Test fun rapidReopeningDoesNotHammerTheCatalogueAfterAFailure() {
        val cache = AiringCache(now - ReleasePolicy.HOUR, now - 4 * ReleasePolicy.MINUTE, "UNAVAILABLE")
        assertFalse(AiringRefreshPolicy.shouldRefresh(cache, now, hasUpcoming = true))
        assertTrue(AiringRefreshPolicy.shouldRefresh(cache, now + ReleasePolicy.MINUTE, hasUpcoming = true))
    }

    @Test fun successfulRetryRestoresScheduleFreshness() {
        val cache = AiringCache(now, now, "AVAILABLE")
        assertFalse(AiringRefreshPolicy.shouldRefresh(cache, now + 10 * ReleasePolicy.MINUTE, hasUpcoming = true))
        assertTrue(AiringRefreshPolicy.shouldRefresh(cache, now + 6 * ReleasePolicy.HOUR, hasUpcoming = true))
    }

    @Test fun passedLastBroadcastIsRecheckedEvenWhileTheMetadataIsFresh() {
        val cache = AiringCache(now - ReleasePolicy.HOUR, now - 10 * ReleasePolicy.MINUTE, "AVAILABLE")
        assertTrue(AiringRefreshPolicy.shouldRefresh(cache, now, hasUpcoming = false))
    }

    @Test fun unannouncedSchedulesCanBeReusedWithoutPretendingTheyFailed() {
        val cache = AiringCache(now - ReleasePolicy.HOUR, now - 10 * ReleasePolicy.MINUTE, "UNANNOUNCED")
        assertFalse(AiringRefreshPolicy.shouldRefresh(cache, now, hasUpcoming = false))
    }

    @Test fun unlinkedAndNeverCheckedTitlesRemainEligible() {
        assertTrue(AiringRefreshPolicy.shouldRefresh(AiringCache(), now, hasUpcoming = false))
        val cache = AiringCache(now - ReleasePolicy.HOUR, now - 10 * ReleasePolicy.MINUTE, "UNRESOLVED")
        assertTrue(AiringRefreshPolicy.shouldRefresh(cache, now, hasUpcoming = true))
    }

    @Test fun settingTheClockBackDoesNotFreezeRetriesOrKeepSchedulesFreshForever() {
        val cache = AiringCache(now + ReleasePolicy.HOUR, now + ReleasePolicy.HOUR, "AVAILABLE")
        assertTrue(AiringRefreshPolicy.shouldRefresh(cache, now, hasUpcoming = true))
    }

    @Test fun explicitRefreshCanRecheckAFreshSchedule() {
        val cache = AiringCache(now, now, "AVAILABLE")
        assertTrue(AiringRefreshPolicy.shouldRefresh(cache, now, hasUpcoming = true, force = true))
    }
}
