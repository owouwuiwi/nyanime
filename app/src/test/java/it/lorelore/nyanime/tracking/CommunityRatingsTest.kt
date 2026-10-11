package it.lorelore.nyanime.tracking

import eu.kanade.tachiyomi.data.workinfo.WorkKind
import eu.kanade.tachiyomi.data.workinfo.WorkProvider
import eu.kanade.tachiyomi.data.workinfo.WorkReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CommunityRatingsTest {
    private val reference = WorkReference(WorkProvider.ANILIST, "123", WorkKind.ANIME)
    private fun media(distribution: String, id: Int = 123, type: String = "ANIME") = Json.parseToJsonElement(
        """{"id":$id,"type":"$type","averageScore":81,"episodes":12,"stats":{"scoreDistribution":$distribution}}""",
    ).jsonObject

    @Test fun `uses actual distribution and normalizes the average only`() {
        val result = parseCommunityRatings(media("""[{"score":80,"amount":31},{"score":90,"amount":52}]"""), reference)
        assertEquals(83L, result.total)
        assertEquals(31, result.counts[7])
        assertEquals(52, result.counts[8])
        assertEquals(8.1, result.average!!, .0001)
        assertEquals(12L, result.totalParts)
    }

    @Test fun `missing votes remain empty instead of invented bars`() {
        val result = parseCommunityRatings(media("[]"), reference)
        assertEquals(List(10) { 0 }, result.counts)
    }

    @Test fun `mismatched identity and media type are rejected`() {
        assertThrows(IllegalStateException::class.java) { parseCommunityRatings(media("[]", id = 124), reference) }
        assertThrows(IllegalStateException::class.java) {
            parseCommunityRatings(media("[]", type = "MANGA"), reference)
        }
    }

    @Test fun `duplicate malformed and negative buckets do not become misleading charts`() {
        listOf(
            """[{"score":80,"amount":1},{"score":80,"amount":2}]""",
            """[{"score":85,"amount":1}]""",
            """[{"score":80,"amount":-1}]""",
            """[{"score":80}]""",
        ).forEach { distribution ->
            assertThrows(IllegalStateException::class.java) { parseCommunityRatings(media(distribution), reference) }
        }
    }
}
