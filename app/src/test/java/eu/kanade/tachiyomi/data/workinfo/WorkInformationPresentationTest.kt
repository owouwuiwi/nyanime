package eu.kanade.tachiyomi.data.workinfo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkInformationPresentationTest {
    private val reference = WorkReference(WorkProvider.ANILIST, "123", WorkKind.ANIME)
    private val information = WorkInformation(
        WorkSummary(reference, "Public title", year = 2000, format = "TV"),
        synopsis = "English catalogue synopsis",
        textLanguage = "en",
        status = "FINISHED",
        imdbId = "tt1234567",
    )
    private val identity =
        WorkIdentity("stable-key", "My chosen title", WorkKind.VIDEO, year = 2001, format = "SPECIAL")

    @Test fun `source or customized text takes precedence including its language and formatting`() {
        val source = "Una descrizione scelta\ncon la sua formattazione."
        val result = WorkInformationPresentation.description(source, information)
        assertEquals(source, result.text)
        assertFalse(result.fromCatalogue)
    }

    @Test fun `missing text can use a labelled catalogue synopsis`() {
        val result = WorkInformationPresentation.description("  ", information)
        assertEquals(information.synopsis, result.text)
        assertTrue(result.fromCatalogue)
        assertNull(WorkInformationPresentation.description(null, null).text)
    }

    @Test fun `facts prefer source hints and do not mutate catalogue data`() {
        val result = WorkInformationPresentation.details(information, identity, hasSourceStatus = true)
        assertEquals(2001, result.work.year)
        assertEquals("SPECIAL", result.work.format)
        assertNull(result.status)
        assertEquals(2000, information.work.year)
        assertEquals("FINISHED", information.status)
    }

    @Test fun `contradictory IMDb references produce no outbound link`() {
        val result = WorkInformationPresentation.details(information, identity.copy(imdbId = "tt9999999"), false)
        assertNull(result.imdbUrl)
        val same = WorkInformationPresentation.details(information, identity.copy(imdbId = "tt1234567"), false)
        assertEquals(information.imdbUrl, same.imdbUrl)
    }
}
