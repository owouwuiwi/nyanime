package tachiyomi.domain.library.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.net.URLClassLoader

class LibraryDisplayModeInitializationTest {
    @Test
    fun `every first loaded mode retains a usable default and fallback`() {
        listOf("CompactGrid", "ComfortableGrid", "List", "Shelf", "CoverOnlyGrid").forEach { first ->
            isolatedModes(first) { loader, model, companion ->
                val compact =
                    loader.loadClass("${model.name}\$CompactGrid").getField("INSTANCE").get(null)
                val default = companion.javaClass.getMethod("getDefault").invoke(companion)
                assertNotNull(default, "Default after initializing $first")
                assertSame(compact, default)
                listOf("", "unknown", "COMPACT_GRID ").forEach { stored ->
                    assertSame(
                        compact,
                        companion.javaClass
                            .getMethod("deserialize", String::class.java)
                            .invoke(companion, stored),
                    )
                }
                val values = companion.javaClass.getMethod("getValues").invoke(companion) as Set<*>
                assertEquals(5, values.size)
                values.forEach { assertNotNull(it) }
            }
        }
    }

    @Test
    fun `stored modes round trip after a singleton is initialized first`() {
        isolatedModes("CompactGrid") { loader, model, _ ->
            val serializer =
                loader.loadClass("${model.name}\$Serializer").getField("INSTANCE").get(null)
            val deserialize = serializer.javaClass.getMethod("deserialize", String::class.java)
            val serialize = serializer.javaClass.getMethod("serialize", model)
            listOf("COMPACT_GRID", "COMFORTABLE_GRID", "LIST", "SHELF", "COVER_ONLY_GRID")
                .forEach { stored ->
                    val decoded = deserialize.invoke(serializer, stored)
                    assertNotNull(decoded)
                    assertEquals(stored, serialize.invoke(serializer, decoded))
                }
        }
    }

    private fun isolatedModes(first: String, check: (ClassLoader, Class<*>, Any) -> Unit) {
        val classes = LibraryDisplayMode::class.java.protectionDomain.codeSource.location
        val kotlinRuntime = kotlin.Lazy::class.java.protectionDomain.codeSource.location
        URLClassLoader(arrayOf(classes, kotlinRuntime), null).use { loader ->
            val name = LibraryDisplayMode::class.java.name
            Class.forName("$name\$$first", true, loader)
            val model = loader.loadClass(name)
            check(loader, model, checkNotNull(model.getField("Companion").get(null)))
        }
    }
}
