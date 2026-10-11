package eu.kanade.tachiyomi.data.rin

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RinCatalogueIconTest {
    private val entry = RinEntry(
        RinManifest(
            1, "test.extension.video", "Example", 1, "16.1", "anime", 16.0,
            listOf("test.extension.video.Source"), listOf(RinSource(901L, "Example", "it")),
            hashes = emptyMap(),
        ),
        "https://example.invalid/package.rin",
        "a".repeat(64),
        "b".repeat(64),
    )

    @Test fun `catalogue icon survives decoding and persisted cache round trips`() {
        val json = RinArchive.json.encodeToString(entry).dropLast(1) +
            """, "resources":{"rinUrl":"https://example.invalid/package.rin","iconUrl":"https://example.invalid/icon.png"}}"""
        val decoded = RinArchive.json.decodeFromString<RinEntry>(json)
        assertEquals("https://example.invalid/icon.png", decoded.iconUrl)
        assertEquals(decoded, RinArchive.json.decodeFromString<RinEntry>(RinArchive.json.encodeToString(decoded)))
    }

    @Test fun `older cached catalogues without icon metadata remain readable`() {
        val decoded = RinArchive.json.decodeFromString<RinEntry>(RinArchive.json.encodeToString(entry))
        assertEquals(entry, decoded)
        assertNull(decoded.iconUrl)
    }

    @Test fun `catalogue images cannot address local files or expose embedded credentials`() {
        for (url in listOf(
            "file:///private/icon.png",
            "http://example.invalid/icon.png",
            "https://user:password@example.invalid/icon.png",
            "https://example.invalid/icon.png#fragment",
        )) {
            assertNull(entry.copy(resources = RinResources(url)).iconUrl)
        }
    }
}
