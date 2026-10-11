package eu.kanade.tachiyomi.discovery

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeFilters
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionHomeManifestTest {
    private val grouped = """{"id":"films","title":"Film","source":{"name":"Ciao","lang":"it"},
        "sections":[
          {"id":"day","title":"Giorno","group":{"id":"ranking","title":"Classifica","tab":"Giorno"}},
          {"id":"week","title":"Settimana","group":{"id":"ranking","title":"Classifica","tab":"Settimana"}}
        ]}"""

    @Test fun sectionGroupsAreOptionalAndKeepTheirSourceOwnedLabels() {
        val manifest = ExtensionHomeManifest.parse(wrap(grouped)).single()
        val sections = ExtensionHomeFilters.sections(manifest, AnimeFilterList())
        assertEquals(listOf("day", "week"), sections.map { it.id })
        assertEquals(listOf("Giorno", "Settimana"), sections.map { it.group?.tab })
        assertEquals(setOf("ranking"), sections.map { it.group?.id }.toSet())
        assertEquals(setOf("Classifica"), sections.map { it.group?.title }.toSet())
        assertEquals(null, ExtensionHomeManifest.parse(wrap(film)).single().sections.single().group)
    }

    @Test fun ambiguousOrMalformedGroupsCannotSuppressAValidSibling() {
        for (invalid in listOf(
            grouped.replace("\"ranking\"", "\"../ranking\""),
            grouped.replace("\"Classifica\"", "\"\""),
            grouped.replace("\"Classifica\"", "\"${"x".repeat(101)}\""),
            grouped.replace("\"tab\":\"Settimana\"", "\"tab\":\"Giorno\""),
            grouped.replace("\"tab\":\"Settimana\"", "\"tab\":\"\""),
            grouped.replaceFirst("\"Classifica\"", "\"Titolo diverso\""),
        )) {
            assertEquals(listOf("films"), ExtensionHomeManifest.parse(wrap("$invalid,$film")).map { it.id })
        }
    }

    @Test fun optionalPresentationWorksForAnySourceAndUnknownLayoutsFallBack() {
        val manifest = ExtensionHomeManifest.parse(
            wrap(
                film.replace(
                    "\"sections\":",
                    "\"primary\":true,\"sections\":",
                ).replace("\"title\":\"Più visti\"", "\"title\":\"Più visti\",\"layout\":\"featured\""),
            ),
        ).single()
        assertTrue(manifest.primary)
        assertEquals("featured", ExtensionHomeFilters.sections(manifest, AnimeFilterList()).single().layout)
        val unknown = manifest.copy(sections = manifest.sections.map { it.copy(layout = "future-layout") })
        assertEquals("posters", ExtensionHomeFilters.sections(unknown, AnimeFilterList()).single().layout)
        assertEquals(false, ExtensionHomeManifest.parse(wrap(film)).single().primary)
        val ranking = manifest.copy(sections = manifest.sections.map { it.copy(layout = "ranking") })
        assertEquals("ranking", ExtensionHomeFilters.sections(ranking, AnimeFilterList()).single().layout)
    }

    @Test fun optionalSurpriseActionsRequireReservedIds() {
        val declared = film.replace(
            "\"sections\":",
            "\"random\":{\"id\":\"random\",\"title\":\"Sorprendimi\"}," +
                "\"randomEpisode\":{\"id\":\"random-episode\",\"title\":\"Episodio casuale\"},\"sections\":",
        )
        val parsed = ExtensionHomeManifest.parse(wrap(declared)).single()
        assertEquals("random", ExtensionHomeFilters.random(parsed, AnimeFilterList())?.id)
        assertEquals("random-episode", ExtensionHomeFilters.randomEpisode(parsed, AnimeFilterList())?.id)
        assertTrue(
            ExtensionHomeManifest.parse(wrap(declared.replace("\"id\":\"random\"", "\"id\":\"search\""))).isEmpty(),
        )
    }
    private val film = """{"id":"films","title":"Film","source":{"name":"Ciao","lang":"it"},
        "sections":[{"id":"popular","title":"Più visti"}]}"""
    private fun wrap(homes: String) = """{"version":1,"homes":[$homes]}"""

    @Test fun arbitraryExtensionsCanDeclareNewContentKinds() {
        val result = ExtensionHomeManifest.parse(wrap(film)).single()
        assertEquals("films", result.id)
        assertEquals("Ciao", result.source.name)
        assertEquals("Film", result.title)
    }

    @Test fun missingMalformedOversizedAndUnsupportedVersionsAreIgnored() {
        for (invalid in listOf(
            "",
            "{}",
            "<html>",
            wrap(film).replace("\"version\":1", "\"version\":2"),
            "x".repeat(65_537),
        )) {
            assertTrue(ExtensionHomeManifest.parse(invalid).isEmpty())
        }
    }

    @Test fun invalidSiblingDoesNotSuppressValidHome() {
        assertEquals("films", ExtensionHomeManifest.parse(wrap("{},$film")).single().id)
    }

    @Test fun duplicateIdentifiersInsideOneExtensionAreAmbiguous() {
        assertTrue(ExtensionHomeManifest.parse(wrap("$film,$film")).isEmpty())
    }

    @Test fun identifiersLabelsAndResourceLimitsAreValidated() {
        for (invalid in listOf(
            film.replace("\"films\"", "\"../films\""),
            film.replace("\"Film\"", "\"\""),
            film.replace("\"Film\"", "\"${"é".repeat(101)}\""),
            film.replace("\"popular\"", "\"search\""),
        )) {
            assertTrue(ExtensionHomeManifest.parse(wrap(invalid)).isEmpty())
        }
        assertTrue(ExtensionHomeManifest.parse(wrap(List(9) { film }.joinToString(","))).isEmpty())
    }

    @Test fun forwardCompatibleUnknownMetadataDoesNotBecomeExecutableConfiguration() {
        val input = wrap(
            film,
        ).replace("\"version\":1", "\"version\":1,\"remoteUrl\":\"https://example.invalid/ignored\"")
        assertEquals(ExtensionHomeManifest.parse(wrap(film)), ExtensionHomeManifest.parse(input))
    }
}
