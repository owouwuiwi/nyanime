package eu.kanade.presentation.more

import eu.kanade.presentation.util.resolveDocumentLink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdateNotesDocumentTest {
    @Test
    fun `numeric and historical notes retain their matching public document revision`() {
        listOf("1.2.3.4" to "v1.2.3.4", "v1.2.3.4" to "v1.2.3.4", "r1234" to "r1234").forEach { (version, tag) ->
            assertEquals(
                "https://github.com/owouwuiwi/nyanime/blob/$tag/docs/guide.md",
                resolveDocumentLink("docs/guide.md", updateNotesDocument(version)),
            )
        }
    }

    @Test
    fun `unknown labels cannot inject an alternate document path`() {
        listOf("Unknown", "1.2.3.4/../../other", "", "1.2.3.4?query").forEach { version ->
            assertEquals(
                "https://github.com/owouwuiwi/nyanime/blob/main/CHANGELOG.md",
                updateNotesDocument(version),
            )
        }
    }
}
