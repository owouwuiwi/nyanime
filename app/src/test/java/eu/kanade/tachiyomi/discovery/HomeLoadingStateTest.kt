package eu.kanade.tachiyomi.discovery

import eu.kanade.presentation.discovery.awaitingContent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.SectionState

class HomeLoadingStateTest {
    @Test
    fun `first request displays a placeholder while awaiting a result`() {
        assertTrue(SectionState<List<String>>().awaitingContent)
    }

    @Test
    fun `cached content stays visible during refresh and on failure`() {
        assertFalse(SectionState(data = listOf("Title"), loading = true).awaitingContent)
        assertFalse(SectionState(data = listOf("Title"), stale = true, error = "Unavailable").awaitingContent)
    }

    @Test
    fun `completed empty result is not a loading placeholder`() {
        assertFalse(SectionState(data = emptyList<String>(), loading = false).awaitingContent)
    }

    @Test
    fun `failed initial request leaves the placeholder so its error and retry can be shown`() {
        assertFalse(SectionState<List<String>>(loading = false, error = "Unavailable").awaitingContent)
    }
}
