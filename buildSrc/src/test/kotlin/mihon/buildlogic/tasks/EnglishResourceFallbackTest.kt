package mihon.buildlogic.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.xml.parsers.DocumentBuilderFactory

class EnglishResourceFallbackTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun missingAndBlankStringsUseEnglishWithoutReplacingTranslations() {
        val root = temp.newFolder()
        fun xml(folder: String, text: String) = root.resolve("$folder/strings.xml").apply {
            parentFile.mkdirs()
            writeText("<resources>$text</resources>")
        }
        xml("base", """<string name="a">English</string><string name="b">Hello %1${'$'}s</string>
            <string name="c">Fallback</string><string name="private" translatable="false">Internal</string>""")
        val local = xml("it", """<string name="a">Italiano</string><string name="b"> </string>""")
        EnglishResourceFallback.prepare(root, "base")
        assertTrue(local.readText().contains("Italiano"))
        assertTrue(local.readText().contains("Hello %1${'$'}s"))
        val fallback = root.resolve("it/english_fallback.xml").readText()
        assertTrue(fallback.contains("Fallback"))
        assertFalse(fallback.contains("Internal"))
        assertFalse(fallback.contains("Italiano"))
    }

    @Test
    fun blankPluralFormsAndArrayEntriesPreserveFormattingAndMarkup() {
        val root = temp.newFolder()
        val english = root.resolve("values/messages.xml").apply { parentFile.mkdirs() }
        english.writeText("""<resources>
            <plurals name="count"><item quantity="one">%1${'$'}d item</item><item quantity="other">%1${'$'}d items</item></plurals>
            <string-array name="choices"><item>One</item><item><b>Two</b></item></string-array>
            </resources>""")
        val local = root.resolve("values-it/messages.xml").apply { parentFile.mkdirs() }
        local.writeText("""<resources>
            <plurals name="count"><item quantity="one">%1${'$'}d voce</item><item quantity="other"/></plurals>
            <string-array name="choices"><item>Uno</item><item/></string-array>
            </resources>""")
        EnglishResourceFallback.prepare(root, "values")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(local)
        assertEquals("%1${'$'}d voce", document.getElementsByTagName("item").item(0).textContent)
        assertEquals("%1${'$'}d items", document.getElementsByTagName("item").item(1).textContent)
        assertEquals("Uno", document.getElementsByTagName("item").item(2).textContent)
        assertEquals("Two", document.getElementsByTagName("b").item(0).textContent)
    }

    @Test
    fun hardwareAndNightQualifiersDoNotOverrideLocalizedText() {
        val root = temp.newFolder()
        root.resolve("values/messages.xml").apply {
            parentFile.mkdirs()
            writeText("""<resources><string name="a">English</string></resources>""")
        }
        for (folder in listOf("values-night", "values-v26", "values-car", "drawable", "values-sw600dp")) {
            root.resolve("$folder/theme.xml").apply {
                parentFile.mkdirs()
                writeText("<resources/>")
            }
        }
        EnglishResourceFallback.prepare(root, "values")
        assertEquals(0, root.walkTopDown().count { it.name == "english_fallback.xml" })
    }

    @Test
    fun arbitraryLanguageAndRegionalQualifierHaveTheirOwnEnglishFallback() {
        val root = temp.newFolder()
        root.resolve("values/messages.xml").apply {
            parentFile.mkdirs()
            writeText("""<resources><string name="a">English &amp; safe</string></resources>""")
        }
        for (locale in listOf("values-hi", "values-fr-rCA", "values-b+zh+Hans")) {
            root.resolve("$locale/messages.xml").apply { parentFile.mkdirs(); writeText("<resources/>") }
        }
        EnglishResourceFallback.prepare(root, "values")
        for (locale in listOf("values-hi", "values-fr-rCA", "values-b+zh+Hans")) {
            assertEquals(
                "English & safe",
                DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(root.resolve("$locale/english_fallback.xml")).getElementsByTagName("string")
                    .item(0).textContent,
            )
        }
    }
    @Test
    fun escapedWhitespaceAndMissingPluralOtherAlsoUseEnglish() {
        val root = temp.newFolder()
        root.resolve("base/messages.xml").apply {
            parentFile.mkdirs()
            writeText("""<resources><string name="a">Readable</string>
                <plurals name="n"><item quantity="one">One</item><item quantity="other">Many</item></plurals>
                </resources>""")
        }
        val local = root.resolve("hi/messages.xml").apply {
            parentFile.mkdirs()
            writeText("""<resources><string name="a">\n\t</string>
                <plurals name="n"><item quantity="one">Translated</item></plurals></resources>""")
        }
        EnglishResourceFallback.prepare(root, "base")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(local)
        assertEquals("Readable", document.getElementsByTagName("string").item(0).textContent)
        assertEquals("Translated", document.getElementsByTagName("item").item(0).textContent)
        assertEquals("Many", document.getElementsByTagName("item").item(1).textContent)
    }

}
