package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.data.layer.MangaLayerSelection
import eu.kanade.tachiyomi.data.layer.MangaLayerTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import nyanime.extension.api.layer.MangaLayerV1
import nyanime.extension.internal.MangaLayerDispatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceLayerCompositionTest {
    private fun offer(packageName: String, capability: String, requires: Set<String> = emptySet()) =
        MangaLayerSelection.Offer(
            packageName,
            MangaLayerTarget(
                packageName = "test.source.base",
                sourceId = 42,
                signer = "a".repeat(64),
                minVersionCode = 10,
                capabilities = setOf(capability),
                requires = if (requires.isEmpty()) emptyMap() else mapOf(capability to requires),
            ),
        )

    @Test
    fun separateAuthorsCanSupplyHomeAndCatalogTogether() {
        val home = offer("test.layer.home", "home", setOf("catalog"))
        val catalog = offer("test.layer.catalog", "catalog")
        assertEquals(mapOf("home" to home, "catalog" to catalog), MangaLayerSelection.resolve(listOf(home, catalog), emptyMap()))
        assertTrue(MangaLayerSelection.resolve(listOf(home), emptyMap()).isEmpty())
    }

    @Test
    fun conflictRequiresChoiceAndAnUnavailableChoiceNeverSilentlyChangesPublishers() {
        val first = offer("test.layer.first", "catalog")
        val second = offer("test.layer.second", "catalog")
        assertTrue(MangaLayerSelection.resolve(listOf(first, second), emptyMap()).isEmpty())
        assertEquals(mapOf("catalog" to first), MangaLayerSelection.resolve(listOf(first, second), mapOf("catalog" to first.packageName)))
        assertTrue(MangaLayerSelection.resolve(listOf(second), mapOf("catalog" to first.packageName)).isEmpty())
        assertTrue(MangaLayerSelection.resolve(listOf(second), mapOf("catalog" to "")).isEmpty())
    }

    @Test
    fun dependencyCyclesFailClosedWithoutAffectingAnotherCapability() {
        val home = offer("test.layer.home", "home", setOf("catalog"))
        val catalog = offer("test.layer.catalog", "catalog", setOf("home"))
        val reader = offer("test.layer.reader", "reader")
        assertEquals(mapOf("reader" to reader), MangaLayerSelection.resolve(listOf(home, catalog, reader), emptyMap()))
    }

    @Test
    fun onlyThePinnedPublisherAndCompatibleVersionsCanAttach() {
        val target = offer("test.layer.one", "catalog").target.copy(maxVersionCode = 20)
        val first = "a".repeat(64)
        val rotated = "b".repeat(64)
        assertTrue(target.matches(10, setOf(first), emptySet()))
        assertTrue(target.matches(20, setOf(rotated), setOf(first, rotated)))
        assertFalse(target.matches(21, setOf(first), emptySet()))
        assertFalse(target.matches(15, setOf(rotated), emptySet()))
        assertFalse(target.matches(15, setOf(first, rotated), emptySet()))
    }

    @Test
    fun linkageFailureReturnsToBaseAndCancellationRemainsCancellation() = runBlocking {
        var failures = 0
        val layer = object : MangaLayerV1 {}
        val broken = MangaLayerDispatch.Binding(layer) { failures++ }
        assertNull(broken.await<String> { throw NoSuchMethodError("synthetic") })
        assertTrue(broken.incompatible)
        assertNull(broken.await { "should never execute" })
        assertEquals(1, failures)

        val cancelled = MangaLayerDispatch.Binding(layer) { failures++ }
        try {
            cancelled.await<String> { throw CancellationException("synthetic") }
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(cancelled.incompatible)
            assertEquals(1, failures)
        }
    }
}
