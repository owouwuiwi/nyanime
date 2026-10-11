package it.lorelore.nyanime.sources

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceRevisionRegistryTest {
    @Test fun logicalSourceEqualityCannotAuthorizeAnObsoleteCodeObject() = runTest {
        data class EqualSource(val id: Long)
        val registry = SourceRevisionRegistry()
        val old = LoadedSourceRevision(31, EqualSource(31), "a")
        val replacement = LoadedSourceRevision(31, EqualSource(31), "a")
        assertEquals(old.source, replacement.source)
        registry.publish("anime", listOf(old))
        registry.publish("anime", listOf(replacement))
        assertFalse(registry.current("anime", old))
        assertTrue(registry.current("anime", replacement))
    }

    @Test fun aLateResponseCannotApplyAfterAReplacementOrRemoval() = runTest {
        for (replacement in listOf(emptyList(), listOf(LoadedSourceRevision(31, Any(), "new-bytes")))) {
            val registry = SourceRevisionRegistry()
            val old = LoadedSourceRevision(31, Any(), "old-bytes")
            registry.publish("anime", listOf(old))
            val ticket = registry.capture("anime", 31, old.source)!!
            registry.publish("anime", replacement)
            var applied = false
            try {
                registry.apply("anime", ticket) { applied = true }
            } catch (_: SourceReplacedException) { }
            assertFalse(applied)
            assertFalse(registry.current("anime", ticket))
        }
    }

    @Test fun publicationWaitsForTheEntireDatabaseApplication() = runTest {
        val registry = SourceRevisionRegistry()
        val old = LoadedSourceRevision(31, Any(), "a")
        val new = LoadedSourceRevision(31, Any(), "b")
        registry.publish("manga", listOf(old))
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val applying = async {
            registry.apply("manga", old) {
                order += "begin"
                release.await()
                order += "end"
            }
        }
        runCurrent()
        val publishing = async { registry.publish("manga", listOf(new)) { order += "publish" } }
        runCurrent()
        assertTrue(registry.current("manga", old))
        release.complete(Unit)
        applying.await()
        publishing.await()
        assertEquals(listOf("begin", "end", "publish"), order)
        assertTrue(registry.current("manga", new))
    }

    @Test fun mediaAndSourceIdentitiesRemainIndependent() = runTest {
        val registry = SourceRevisionRegistry()
        val anime = LoadedSourceRevision(31, Any(), "a")
        val manga = LoadedSourceRevision(31, Any(), "b")
        registry.publish("anime", listOf(anime))
        registry.publish("manga", listOf(manga))
        registry.publish("anime", emptyList())
        assertTrue(registry.current("manga", manga))
        assertFalse(registry.current("anime", anime))
    }
}
