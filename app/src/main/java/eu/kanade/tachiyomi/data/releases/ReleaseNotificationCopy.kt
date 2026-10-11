package eu.kanade.tachiyomi.data.releases

import android.content.Context
import eu.kanade.tachiyomi.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class ReleaseNotificationText(
    val title: String,
    val headline: String,
    val summary: String,
    val details: String,
)

internal interface ReleaseNotificationStrings {
    fun text(id: Int, vararg args: Any): String
    fun quantity(id: Int, count: Int, vararg args: Any): String
}

/** Presentation only: publication evidence and delivery remain in the existing release pipeline. */
internal class ReleaseNotificationCopy(
    private val strings: ReleaseNotificationStrings,
    private val locale: Locale,
    private val zone: ZoneId,
    private val time: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale),
) {
    fun reminder(
        title: String,
        event: AiringEvent,
        now: Long,
        preferred: ScheduleAirType,
        hidden: Boolean = false,
    ): ReleaseNotificationText {
        if (hidden) return privateCopy()
        val today = date(now)
        val airing = date(event.airingAt)
        val selected = event.variants.firstOrNull { it.at == event.airingAt && it.type == preferred }
            ?: event.variants.firstOrNull { it.at == event.airingAt }
        val last = selected?.untilEpisode?.coerceAtLeast(event.episode) ?: event.episode
        val count = last - event.episode + 1
        val headline = strings.quantity(
            when (airing) {
                today -> R.plurals.release_notice_episode_today
                today.plusDays(1) -> R.plurals.release_notice_episode_tomorrow
                else -> R.plurals.release_notice_episode_upcoming
            },
            count,
            title,
            count,
        )
        val number = if (last == event.episode) "${event.episode}" else "${event.episode}–$last"
        val item = strings.quantity(R.plurals.release_notice_episode_number, count, number)
        val hour = Instant.ofEpochMilli(event.airingAt).atZone(zone).format(time)
        val whenShort = strings.text(R.string.release_notice_day_time, day(airing, today), hour)
        val fullDate = airing.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        val type = strings.text(
            when (selected?.type) {
                ScheduleAirType.SUB -> R.string.schedule_sub
                ScheduleAirType.DUB -> R.string.schedule_dub
                else -> R.string.schedule_raw
            },
        )
        val lines = buildList {
            add(title)
            add(item)
            add(strings.text(R.string.release_notice_announced_at, fullDate, hour))
            add(type)
            selected?.platforms?.filter { it.isNotBlank() }?.distinct()?.take(4)?.takeIf { it.isNotEmpty() }?.let {
                add(strings.text(R.string.release_notice_platforms, it.joinToString(", ")))
            }
            selected?.delayed?.takeIf { it.isNotBlank() }?.let { add(it.take(300)) }
            add(strings.text(R.string.release_notice_source_may_differ))
        }
        return ReleaseNotificationText(title, headline, "$item · $whenShort", lines.joinToString("\n"))
    }

    fun available(
        medium: ReleaseMedium,
        title: String,
        itemDescription: String,
        count: Int,
        publicationDates: List<Long>,
        detectedAt: Long,
        now: Long,
        hidden: Boolean = false,
    ): ReleaseNotificationText {
        if (hidden) return privateCopy()
        val today = date(now)
        // Date of discovery is never evidence that a chapter/episode was published today.
        val publishedToday = publicationDates.size == count &&
            publicationDates.all {
                it in 1..now && date(it) == today
            }
        val headlineId = when (medium) {
            ReleaseMedium.ANIME -> if (publishedToday) {
                R.plurals.release_notice_episode_today
            } else {
                R.plurals.release_notice_episode_available
            }
            ReleaseMedium.MANGA -> if (publishedToday) {
                R.plurals.release_notice_chapter_today
            } else {
                R.plurals.release_notice_chapter_available
            }
        }
        val headline = strings.quantity(headlineId, count, title, count)
        val lines = buildList {
            add(title)
            add(itemDescription)
            add(strings.text(R.string.release_notice_available_source))
            val days = publicationDates.filter { it in 1..now }.map(::date).distinct().sorted()
            if (days.size == 1 && publicationDates.all { it in 1..now }) {
                val published = days.single().format(
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
                )
                add(strings.text(R.string.release_notice_published_on, published))
            }
            if (detectedAt in 1..now) {
                val hour = Instant.ofEpochMilli(detectedAt).atZone(zone).format(time)
                add(strings.text(R.string.release_notice_detected_at, day(date(detectedAt), today), hour))
            }
        }
        return ReleaseNotificationText(title, headline, itemDescription, lines.joinToString("\n"))
    }

    private fun date(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    private fun day(value: LocalDate, today: LocalDate): String = when (value) {
        today -> strings.text(R.string.release_notice_today)
        today.plusDays(1) -> strings.text(R.string.release_notice_tomorrow)
        else -> value.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }

    private fun privateCopy(): ReleaseNotificationText {
        val headline = strings.text(R.string.release_title)
        val text = strings.text(R.string.release_notice_private)
        return ReleaseNotificationText(headline, headline, text, text)
    }

    companion object {
        fun from(context: Context): ReleaseNotificationCopy = ReleaseNotificationCopy(
            object : ReleaseNotificationStrings {
                override fun text(id: Int, vararg args: Any) = context.getString(id, *args)
                override fun quantity(id: Int, count: Int, vararg args: Any) =
                    context.resources.getQuantityString(id, count, *args)
            },
            context.resources.configuration.locales[0],
            ZoneId.systemDefault(),
            DateTimeFormatter.ofPattern(
                if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
            )
                .withLocale(context.resources.configuration.locales[0]),
        )
    }
}
