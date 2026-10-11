package eu.kanade.tachiyomi.extension.anime

import eu.kanade.tachiyomi.animesource.model.Hoster
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class HosterAbiTest {

    @Test
    fun olderExtensionDefaultConstructorRemainsAvailable() {
        val constructor = Hoster::class.java.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.toList() == listOf(
                String::class.java,
                String::class.java,
                List::class.java,
                String::class.java,
                Integer.TYPE,
                Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
            )
        }
        assertNotNull(constructor)

        val hoster = constructor!!.newInstance(
            "https://example.invalid/video",
            "Example",
            null,
            "page",
            4,
            null,
        ) as Hoster
        assertEquals("Example", hoster.hosterName)
        assertEquals("page", hoster.internalData)
    }
}
