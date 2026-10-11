package it.lorelore.nyanime.novel

import eu.kanade.tachiyomi.data.rin.RinManifest
import eu.kanade.tachiyomi.data.rin.RinSource
import it.lorelore.nyanime.novel.source.NovelSourcePackages
import it.lorelore.nyanime.source.novel.NovelChapter
import it.lorelore.nyanime.source.novel.NovelContent
import it.lorelore.nyanime.source.novel.NovelDetails
import it.lorelore.nyanime.source.novel.NovelSearchPage
import it.lorelore.nyanime.source.novel.NovelSearchRequest
import it.lorelore.nyanime.source.novel.NovelSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelSourcePackagesTest {
    private val manifest =
        RinManifest(
            schema = 2,
            packageName = "fictional.novel.extension",
            name = "Fictional books",
            versionCode = 1,
            versionName = "1",
            medium = "novel",
            extensionLib = 1.0,
            entryPoints = listOf("fictional.novel.Factory"),
            sources = listOf(RinSource(77L, "Fictional books", "en")),
            hashes = emptyMap(),
            minHostVersionCode = NovelSourcePackages.MIN_HOST_VERSION,
        )

    private fun source(
        id: Long = 77L,
        name: String = "Fictional books",
        language: String = "en",
        adult: Boolean = false,
    ) =
        object : NovelSource {
            override val id = id
            override val name = name
            override val lang = language
            override val isNsfw = adult

            override suspend fun search(request: NovelSearchRequest): NovelSearchPage =
                error("Not invoked")

            override suspend fun details(reference: String): NovelDetails = error("Not invoked")

            override suspend fun chapters(reference: String): List<NovelChapter> =
                error("Not invoked")

            override suspend fun content(chapterReference: String): NovelContent =
                error("Not invoked")
        }

    @Test
    fun `novel packages require their versioned optional contract and declared host`() {
        assertTrue(NovelSourcePackages.compatible(manifest))
        assertFalse(NovelSourcePackages.compatible(manifest.copy(minHostVersionCode = null)))
        assertFalse(NovelSourcePackages.compatible(manifest.copy(minHostVersionCode = 288)))
        assertFalse(NovelSourcePackages.compatible(manifest.copy(schema = 1)))
        assertFalse(NovelSourcePackages.compatible(manifest.copy(extensionLib = 2.0)))
        assertFalse(NovelSourcePackages.compatible(manifest.copy(medium = "publication")))
    }

    @Test
    fun `inspection cannot silently accept missing identities or optional foreign factories`() {
        assertFalse(NovelSourcePackages.compatible(manifest.copy(sources = emptyList())))
        assertFalse(
            NovelSourcePackages.compatible(
                manifest.copy(optionalFactories = listOf("fictional.Other")),
            ),
        )
        assertFalse(NovelSourcePackages.compatible(manifest.copy(entryPoints = emptyList())))
    }

    @Test
    fun `loaded identities must match every declared source with no duplicate ids`() {
        val expected = source()
        assertEquals(listOf(expected), NovelSourcePackages.verify(manifest, listOf(expected)))
        assertThrows(IllegalArgumentException::class.java) {
            NovelSourcePackages.verify(manifest, listOf(expected, expected))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelSourcePackages.verify(manifest, listOf(source(id = 78)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelSourcePackages.verify(manifest, listOf(source(name = "Another source")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelSourcePackages.verify(manifest, listOf(source(language = "it")))
        }
    }

    @Test
    fun `adult capabilities cannot be hidden by a normal package declaration`() {
        assertThrows(IllegalArgumentException::class.java) {
            NovelSourcePackages.verify(manifest, listOf(source(adult = true)))
        }
        assertEquals(
            1,
            NovelSourcePackages.verify(manifest.copy(nsfw = true), listOf(source(adult = true)))
                .size,
        )
    }
}
