package eu.kanade.tachiyomi.data.watch

import eu.kanade.tachiyomi.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

private fun roomResources(locale: String): Map<String, String> {
    val directory = if (locale == "it") "values-it" else "values"
    return listOf(
        "strings_rooms.xml",
        "strings_reading_session.xml",
        "strings_nyanime_ui.xml",
        "tv.xml",
        "playback.xml",
    )
        .flatMap { name ->
            val relative = "src/main/res/$directory/$name"
            val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("string")
            (0 until nodes.length).map { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
                    .removeSurrounding("\"").replace("\\'", "'").replace("\\n", "\n")
            }
        }.toMap()
}

internal fun roomTextForTest(locale: String): RoomText {
    val resources = roomResources(locale)
    val ids = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    return RoomText { resource, args ->
        val value = resources.getValue(ids.getValue(resource))
        if (args.isEmpty()) value else String.format(Locale.forLanguageTag(locale), value, *args)
    }
}

internal val testRoomText = roomTextForTest("it")

class RoomLocalizationTest {
    @Test
    fun englishAndItalianResourcesHaveTheSameKeysAndFormatArguments() {
        val english = roomResources("en")
        val italian = roomResources("it")
        assertEquals(english.keys, italian.keys)
        val placeholder = Regex("%\\d+\\$[ds]")
        english.forEach { (key, value) ->
            assertEquals(
                placeholder.findAll(value).map { it.value }.sorted().toList(),
                placeholder.findAll(italian.getValue(key)).map { it.value }.sorted().toList(),
                key,
            )
        }
    }

    @Test
    fun roomFeedbackAndErrorsUseTheSelectedLanguage() {
        val english = roomTextForTest("en")
        val activity = WatchActivity(1, 1000, "a".repeat(64), "Friend", "seek", "episode", 135.0)
        assertEquals("Friend skipped to 2:15", activity.label(english))
        assertEquals("Friend è andato a 2:15", activity.label(testRoomText))
        val room = WatchRoomState(active = true, phase = WatchPhase.Reconnecting)
        assertEquals("Reconnecting…", room.preparationCaption(english))
        assertEquals("Missing extension", WatchProblem.MissingSource.description(english))
        val failure = runCatching { WatchInvite.parse("invalid", 1000) }.exceptionOrNull()!!
        assertEquals("Paste the full invitation you received from your friend.", failure.roomMessage(english))
    }

    @Test
    fun sharingChangesTheCopyWithoutChangingTheCodeOrProtocolLink() {
        val code = "12345678"
        val link = WatchShortRooms.link(code)
        val english = WatchShortRooms.shareText(code, roomTextForTest("en"))
        val italian = WatchShortRooms.shareText(code, testRoomText)
        assertTrue(english.startsWith("Let’s watch together on Nyanime!"))
        assertTrue(italian.startsWith("Guardiamo insieme su Nyanime!"))
        listOf(english, italian).forEach { message ->
            assertTrue(message.contains(code))
            assertTrue(message.contains(link))
            assertEquals(code, WatchInvite.codeFromLink(link, 1000))
        }
    }
}
