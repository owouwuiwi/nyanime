package eu.kanade.tachiyomi.data.releases

import eu.kanade.tachiyomi.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class ReleaseNotificationCopyTest {
    private val now = Instant.parse("2026-09-29T14:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Europe/Rome")
    private val copy = ReleaseNotificationCopy(ResourceStrings("it"), Locale.ITALIAN, zone)
    private val title = "Titolo di prova"

    @Test fun tomorrowReminderHasAReadableHeadlineAndSeparateTimeAndDetails() {
        val event = AiringEvent(1, 12, now + ReleasePolicy.DAY, 42)
        val text = copy.reminder(title, event, now, ScheduleAirType.RAW)
        assertEquals(title, text.title)
        assertEquals("Domani esce un nuovo episodio di «Titolo di prova»", text.headline)
        assertEquals("Episodio 12 · Domani, ore 16:00", text.summary)
        assertTrue(text.details.contains("Orario annunciato: 30 set 2026, ore 16:00"))
        assertTrue(text.details.contains("Trasmissione giapponese"))
        assertTrue(text.details.contains("La disponibilità nella tua fonte può essere diversa."))
    }

    @Test fun todayReminderDoesNotKeepTheWordTomorrowFromTheAdvanceAlert() {
        val text = copy.reminder(title, AiringEvent(1, 12, now, 42), now, ScheduleAirType.RAW)
        assertEquals("Oggi esce un nuovo episodio di «Titolo di prova»", text.headline)
        assertTrue(text.summary.contains("Oggi, ore 16:00"))
        assertFalse(text.details.contains("Domani"))
    }

    @Test fun relativeDayUsesTheDeviceZoneNearMidnight() {
        val midnight = Instant.parse("2026-09-29T22:10:00Z").toEpochMilli()
        val airing = Instant.parse("2026-09-30T21:30:00Z").toEpochMilli()
        val text = copy.reminder(title, AiringEvent(1, 2, airing, 42), midnight, ScheduleAirType.RAW)
        assertTrue(text.headline.startsWith("Oggi"))
        assertTrue(text.summary.contains("23:30"))
    }

    @Test fun postponedOrDistantEventsUseComingSoonAndTheirActualDate() {
        val text = copy.reminder(title, AiringEvent(1, 2, now + 3 * ReleasePolicy.DAY, 42), now, ScheduleAirType.RAW)
        assertEquals("In arrivo un nuovo episodio di «Titolo di prova»", text.headline)
        assertFalse(text.summary.contains("Domani"))
        assertTrue(text.summary.contains("2 ott 2026"))
    }

    @Test fun broadcastBatchHasTheRightPluralAndRangeAndPreferredLanguage() {
        val event = AiringEvent(
            1,
            3,
            now + ReleasePolicy.DAY,
            42,
            variants = listOf(
                ScheduleBroadcast(3, now + ReleasePolicy.DAY, ScheduleAirType.RAW),
                ScheduleBroadcast(3, now + ReleasePolicy.DAY, ScheduleAirType.SUB, untilEpisode = 5),
            ),
        )
        val text = copy.reminder(title, event, now, ScheduleAirType.SUB)
        assertEquals("Domani escono 3 nuovi episodi di «Titolo di prova»", text.headline)
        assertTrue(text.summary.startsWith("Episodi 3–5"))
        assertTrue(text.details.contains("Sottotitoli inglesi"))
    }

    @Test fun mangaPublishedTodayUsesTheChapterHeadlineAndDistinguishesDiscoveryTime() {
        val text = copy.available(ReleaseMedium.MANGA, title, "Capitolo 4.5", 1, listOf(now - 3600_000), now, now)
        assertEquals("Oggi esce un nuovo capitolo di «Titolo di prova»", text.headline)
        assertEquals("Capitolo 4.5", text.summary)
        assertEquals(title, text.title)
        assertEquals(title, text.details.lineSequence().first())
        assertTrue(text.details.contains("Pubblicato il 29 set 2026"))
        assertTrue(text.details.contains("Rilevato: Oggi, ore 16:00"))
        assertFalse(text.details.contains("ore 15:00"))
    }

    @Test fun importedHistoricalAndUndatedChaptersAreNeverDescribedAsPublishedToday() {
        for (date in listOf(0L, now - 7 * ReleasePolicy.DAY, now + ReleasePolicy.DAY)) {
            val text = copy.available(ReleaseMedium.MANGA, title, "Capitolo 4", 1, listOf(date), now, now)
            assertEquals("È disponibile un nuovo capitolo di «Titolo di prova»", text.headline)
            assertFalse(text.headline.startsWith("Oggi"))
        }
    }

    @Test fun mixedPublicationDatesDoNotMakeTheWholeBatchAnUpdateFromToday() {
        val text = copy.available(
            ReleaseMedium.ANIME,
            title,
            "Episodi 3, 4",
            2,
            listOf(now, now - 2 * ReleasePolicy.DAY),
            now,
            now,
        )
        assertEquals("Sono disponibili 2 nuovi episodi di «Titolo di prova»", text.headline)
        assertFalse(text.details.contains("Pubblicato il"))
    }

    @Test fun aDateOnlyPublicationNeverInventsAMidnightReleaseTime() {
        val dayOnly = Instant.parse("2026-09-28T22:00:00Z").toEpochMilli()
        val text = copy.available(ReleaseMedium.MANGA, title, "Capitolo 4", 1, listOf(dayOnly), 0, now)
        assertTrue(text.headline.startsWith("Oggi"))
        assertFalse(text.details.contains("00:00"))
        assertFalse(text.details.contains("Rilevato:"))
    }

    @Test fun longTitlesRemainFirstInCompactViewAndCompleteInWrappedDetails() {
        val long = "Un titolo molto lungo con numeri 100%, citazioni e una seconda parte: " + "parola ".repeat(15)
        val text = copy.reminder(long, AiringEvent(1, 2, now, 42), now, ScheduleAirType.RAW)
        assertTrue(text.headline.contains(long))
        assertEquals(long, text.title)
        assertEquals(long, text.details.lineSequence().first())
    }

    @Test fun privacyHidesTitlesEpisodeNumbersAndBroadcastMetadata() {
        val event = AiringEvent(1, 42, now + ReleasePolicy.DAY, 7)
        val reminder = copy.reminder(title, event, now, ScheduleAirType.RAW, hidden = true)
        val available = copy.available(
            ReleaseMedium.MANGA,
            title,
            "Capitolo 42",
            1,
            listOf(now),
            now,
            now,
            hidden = true,
        )
        for (text in listOf(reminder, available)) {
            assertFalse((text.title + text.headline + text.summary + text.details).contains(title))
            assertFalse(text.details.contains("42"))
            assertFalse(text.details.contains("16:00"))
        }
    }

    @Test fun englishFallbackUsesTheSameStructureAndPluralArguments() {
        val english = ReleaseNotificationCopy(ResourceStrings("en"), Locale.UK, zone)
        val text = english.available(ReleaseMedium.MANGA, title, "Chapters 3, 4", 2, listOf(now, now), now, now)
        assertEquals("2 new chapters of «Titolo di prova» come out today", text.headline)
        assertTrue(text.details.contains("Available in your source."))
    }

    /** Render the shipped resources rather than maintaining a second set of notification messages. */
    private class ResourceStrings(language: String) : ReleaseNotificationStrings {
        private val textNames = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        private val pluralNames = R.plurals::class.java.fields.associate { it.getInt(null) to it.name }
        private val entries: Map<String, Element> = buildMap {
            val folder = if (language == "it") "values-it" else "values"
            for (name in listOf("strings_release_notifications.xml", "strings_releases.xml", "strings_schedule.xml")) {
                val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(File("src/main/res/$folder/$name"))
                val children = xml.documentElement.childNodes
                for (index in 0 until children.length) {
                    val node = children.item(index) as? Element ?: continue
                    put(node.getAttribute("name"), node)
                }
            }
        }

        override fun text(id: Int, vararg args: Any): String =
            String.format(Locale.ITALIAN, entries.getValue(textNames.getValue(id)).textContent, *args)

        override fun quantity(id: Int, count: Int, vararg args: Any): String {
            val items = entries.getValue(pluralNames.getValue(id)).getElementsByTagName("item")
            val selected = (0 until items.length).map { items.item(it) as Element }
                .first { it.getAttribute("quantity") == if (count == 1) "one" else "other" }
            return String.format(Locale.ITALIAN, selected.textContent, *args)
        }
    }
}
