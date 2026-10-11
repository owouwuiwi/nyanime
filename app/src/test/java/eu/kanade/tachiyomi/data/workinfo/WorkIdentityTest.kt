package eu.kanade.tachiyomi.data.workinfo

import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkIdentityTest {
    private val reference = WorkReference(WorkProvider.ANILIST, "123", WorkKind.ANIME)
    private val original = WorkSummary(reference, "The distant island", year = 2002, format = "TV")
    private val identity = WorkIdentity("video:7:8:/title/1", original.title, WorkKind.VIDEO)

    @Test fun `same name alone never automatically associates a work`() {
        assertNull(WorkIdentityMatcher.match(identity, listOf(original)))
    }

    @Test fun `year and format distinguish remakes without normalizing away numbers`() {
        val remake = original.copy(reference = reference.copy(id = "456"), year = 2020)
        assertEquals(
            original,
            WorkIdentityMatcher.match(identity.copy(year = 2002, format = "TV"), listOf(original, remake)),
        )
        assertNull(
            WorkIdentityMatcher.match(
                identity.copy(title = "The distant island 2", year = 2002, format = "TV"),
                listOf(original),
            ),
        )
    }

    @Test fun `seasons and parts must agree for name based association`() {
        val evidence = identity.copy(year = 2002, format = "TV", season = 2, part = 1)
        assertNull(WorkIdentityMatcher.match(evidence, listOf(original)))
        assertNull(WorkIdentityMatcher.match(evidence, listOf(original.copy(season = 1, part = 1))))
        assertEquals(
            original.copy(season = 2, part = 1),
            WorkIdentityMatcher.match(evidence, listOf(original.copy(season = 2, part = 1))),
        )
    }

    @Test fun `verified IDs cannot cross media or contradict a known year`() {
        assertEquals(
            original,
            WorkIdentityMatcher.match(identity.copy(references = listOf(reference)), listOf(original)),
        )
        assertNull(
            WorkIdentityMatcher.match(
                identity.copy(kind = WorkKind.MANGA, references = listOf(reference)),
                listOf(original),
            ),
        )
        assertNull(
            WorkIdentityMatcher.match(identity.copy(year = 2020, references = listOf(reference)), listOf(original)),
        )
    }

    @Test fun `two editions with the same supporting evidence remain ambiguous`() {
        val other = original.copy(reference = reference.copy(id = "987"))
        assertNull(WorkIdentityMatcher.match(identity.copy(year = 2002, format = "TV"), listOf(original, other)))
    }

    @Test fun `different verified external IDs reject otherwise matching candidates`() {
        assertFalse(WorkIdentityMatcher.compatible(identity.copy(malId = 22), original.copy(malId = 33)))
        assertTrue(WorkIdentityMatcher.compatible(identity.copy(malId = 22), original.copy(malId = 22)))
    }

    @Test fun `text IDs and catalogue namespaces have strict types`() {
        assertTrue(WorkReference(WorkProvider.WIKIDATA, "Q123", WorkKind.FILM).isValid())
        assertFalse(WorkReference(WorkProvider.WIKIDATA, "Q123/anything", WorkKind.FILM).isValid())
        assertFalse(reference.copy(id = "-1").isValid())
        assertFalse(WorkReference(WorkProvider.TVMAZE, "123", WorkKind.FILM).isValid())
        assertNull(WorkInformation(original, imdbId = "tt1234567/other").imdbUrl)
        assertEquals("https://www.imdb.com/title/tt1234567/", WorkInformation(original, imdbId = "tt1234567").imdbUrl)
    }

    @Test fun `optional metadata accepts textual identifiers and ignores unsupported catalogues`() {
        val metadata = Json.parseToJsonElement(
            """{"type":"film","year":2002,"references":[
                {"provider":"wikidata","id":"Q123","type":"film"},
                {"provider":"imdb","id":"tt1234567","type":"film"},
                {"provider":"future","id":"arbitrary","type":"film"},
                {"provider":"anilist","id":"5","type":"manga"}
            ]}""",
        ).jsonObject
        val result = SourceWorkIdentity.apply(identity, metadata)
        assertEquals(WorkKind.FILM, result.kind)
        assertEquals(listOf(WorkReference(WorkProvider.WIKIDATA, "Q123", WorkKind.FILM)), result.references)
        assertEquals("tt1234567", result.imdbId)
        assertEquals(2002, result.year)
    }

    @Test fun `manual associations and consent are portable without database IDs`() {
        val key = WorkInfoPreferences.localKey(true, 7L, "/title/1")
        val bindingKey = WorkInfoPreferences.bindingKey(key)
        assertTrue(BackupPreferencePolicy.isPortable(bindingKey))
        assertTrue(BackupPreferencePolicy.isPortable("work_info_consent"))
        assertEquals(bindingKey, WorkInfoPreferences.bindingKey(WorkInfoPreferences.localKey(true, 7L, "/title/1")))
        assertFalse(bindingKey == WorkInfoPreferences.bindingKey(WorkInfoPreferences.localKey(false, 7L, "/title/1")))
        val binding = WorkBinding(reference)
        assertEquals(binding, Json.decodeFromString<WorkBinding>(Json.encodeToString(binding)))
        assertEquals(WorkBinding(null), Json.decodeFromString<WorkBinding>("{}"))
    }
}
