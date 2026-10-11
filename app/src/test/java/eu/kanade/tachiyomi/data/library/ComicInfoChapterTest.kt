@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.library

import eu.kanade.tachiyomi.source.model.SChapter
import nl.adaptivity.xmlutil.core.KtXmlReader
import nl.adaptivity.xmlutil.core.KtXmlWriter
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import java.io.StringReader
import java.time.LocalDate
import java.time.ZoneOffset

class ComicInfoChapterTest {
    private val xml = XML { defaultPolicy { ignoreUnknownChildren() } }
    private fun read(value: String): ComicInfo =
        xml.decodeFromReader(ComicInfo.serializer(), KtXmlReader(StringReader(value)))

    @Test
    fun `chapter metadata round trips with a valid publication date`() {
        val info = read(
            """<ComicInfo><Title>Chapter name</Title><Number>12.5</Number><Translator>Team</Translator><Year>2024</Year><Month>2</Month><Day>29</Day></ComicInfo>""",
        )
        val encoded = StringBuilder()
        xml.encodeToWriter(KtXmlWriter(encoded), ComicInfo.serializer(), info)
        val decoded = read(encoded.toString())
        val chapter = SChapter.create().apply {
            name = "Original"
            date_upload = 100
        }
        chapter.copyFromComicInfo(decoded, ZoneOffset.UTC)
        assertEquals("Chapter name", chapter.name)
        assertEquals(12.5f, chapter.chapter_number)
        assertEquals("Team", chapter.scanlator)
        assertEquals(
            LocalDate.of(2024, 2, 29).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            chapter.date_upload,
        )
    }

    @Test
    fun `incomplete invalid dates and nonfinite numbers preserve existing chapter data`() {
        listOf(
            "<Year>2024</Year>",
            "<Year>2024</Year><Month>2</Month><Day>30</Day>",
            "<Year>2024</Year><Month>13</Month><Day>1</Day>",
        ).forEach { date ->
            val info = read(
                "<ComicInfo><Title></Title><Number>NaN</Number>$date</ComicInfo>",
            )
            val chapter = SChapter.create().apply {
                name = "Original"
                chapter_number = 8f
                date_upload = 100
            }
            chapter.copyFromComicInfo(info, ZoneOffset.UTC)
            assertEquals("Original", chapter.name)
            assertEquals(8f, chapter.chapter_number)
            assertEquals(100L, chapter.date_upload)
        }
    }

    @Test
    fun `new XML fields retain the existing constructor and copy signatures`() {
        assertTrue(ComicInfo::class.java.constructors.any { it.parameterCount == 17 })
        assertTrue(ComicInfo::class.java.methods.any { it.name == "copy" && it.parameterCount == 17 })
    }
}
