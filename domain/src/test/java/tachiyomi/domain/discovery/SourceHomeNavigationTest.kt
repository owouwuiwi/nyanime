package tachiyomi.domain.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class SourceHomeNavigationTest {
    @Test
    fun advancedNavigationControlsSurviveSavedAndroidScreenState() {
        val section = SourceHomeSection(
            "search",
            "Search",
            mapOf("Scope" to "Catalog"),
            group = SourceHomeSectionGroup("group", "Group", "Tab"),
            browseValues = mapOf("Genres" to listOf("Adventure")),
        )
        val controls = listOf(SourceHomeFilter("Genres", SourceHomeFilter.Kind.MULTIPLE, listOf("Adventure")))
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { output ->
            output.writeObject(section)
            output.writeObject(controls)
        }
        ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { input ->
            assertEquals(section, input.readObject())
            assertEquals(controls, input.readObject())
        }
    }
}
