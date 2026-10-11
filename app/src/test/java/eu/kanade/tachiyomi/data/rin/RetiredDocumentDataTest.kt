package eu.kanade.tachiyomi.data.rin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RetiredDocumentDataTest {
    private fun records(raw: String) = RinArchive.json.parseToJsonElement(raw) as JsonArray
    private fun pending(raw: String) = RinArchive.json.parseToJsonElement(raw) as JsonObject

    @Test fun `retirement preserves unrelated extensions and unknown metadata exactly`() {
        val active = records(
            """[
                {"manifest":{"medium":"anime"},"enabled":false,"future":{"keep":true}},
                {"manifest":{"medium":"publication"},"file":"old.rin"},
                {"manifest":{"medium":"manga"},"progress":17},
                {"manifest":{"medium":"future-medium"},"private":"untouched"},
                {"manifest":{"medium":"novel"},"book":"keep"}
            ]""",
        )
        val retained = RetiredDocumentData.retain(active)
        assertEquals(JsonArray(listOf(active[0], active[2], active[3], active[4])), retained)
        assertEquals(retained, RetiredDocumentData.retain(retained))
    }

    @Test fun `mixed pending migrations retain all surviving handoffs without resurrecting documents`() {
        val state = pending(
            """{"phase":"configure","entries":[
                {"manifest":{"medium":"publication"}},
                {"manifest":{"medium":"manga"},"url":"keep"}
            ],"prepared":[
                {"manifest":{"medium":"manga"},"file":"keep.rin"},
                {"manifest":{"medium":"publication"},"file":"old.rin"}
            ],"future":99}""",
        )
        val retained = RetiredDocumentData.pending(state)!!
        assertEquals(state["phase"], retained["phase"])
        assertEquals(state["future"], retained["future"])
        assertEquals(JsonArray(listOf((state["entries"] as JsonArray)[1])), retained["entries"])
        assertEquals(JsonArray(listOf((state["prepared"] as JsonArray)[0])), retained["prepared"])
        assertEquals(retained, RetiredDocumentData.pending(retained))
    }

    @Test fun `pending migration containing only retired documents is removed`() {
        assertNull(
            RetiredDocumentData.pending(
                pending("""{"phase":"prepare","entries":[{"manifest":{"medium":"publication"}}]}"""),
            ),
        )
    }
}
