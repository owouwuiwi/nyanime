package eu.kanade.tachiyomi.data.releases

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class ReleaseReminderKind {
    ADVANCE,
    DAY_BEFORE_MORNING,
    DAY_BEFORE_AFTERNOON,
    DAY_BEFORE_EVENING,
    SAME_DAY_MORNING,
    SAME_DAY_AFTERNOON,
    SAME_DAY_EVENING,
    HOUR_BEFORE,
    TEN_MINUTES_BEFORE,
    FIVE_MINUTES_BEFORE,
    TWO_MINUTES_BEFORE,
    AIRING,
}

data class ReleaseReminder(
    val kind: ReleaseReminderKind,
    val events: List<AiringEvent>,
    val keys: Set<String>,
    val at: Long,
) {
    val event: AiringEvent get() = events.first()
    val timeReceipt: String get() = "at:$at"

    fun expired(now: Long): Boolean {
        if (kind != ReleaseReminderKind.AIRING && now >= event.airingAt) return true
        val tolerance = when (kind) {
            ReleaseReminderKind.ADVANCE -> 6 * ReleasePolicy.HOUR
            ReleaseReminderKind.DAY_BEFORE_MORNING,
            ReleaseReminderKind.DAY_BEFORE_AFTERNOON,
            ReleaseReminderKind.DAY_BEFORE_EVENING,
            ReleaseReminderKind.SAME_DAY_MORNING,
            ReleaseReminderKind.SAME_DAY_AFTERNOON,
            ReleaseReminderKind.SAME_DAY_EVENING,
            -> 2 * ReleasePolicy.HOUR
            ReleaseReminderKind.HOUR_BEFORE -> 30 * ReleasePolicy.MINUTE
            ReleaseReminderKind.TEN_MINUTES_BEFORE -> 5 * ReleasePolicy.MINUTE
            ReleaseReminderKind.FIVE_MINUTES_BEFORE -> 3 * ReleasePolicy.MINUTE
            ReleaseReminderKind.TWO_MINUTES_BEFORE -> 2 * ReleasePolicy.MINUTE
            ReleaseReminderKind.AIRING -> 2 * ReleasePolicy.HOUR
        }
        return now - at > tolerance
    }
}

/** Absolute dates from either provider. No polling, guessed dates or source-specific identities. */
object ReleaseReminderPlan {
    fun pending(
        events: List<AiringEvent>,
        delivered: Set<Pair<String, String>>,
        selected: Set<ReleaseReminderKind>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ReleaseReminder> = events.filter {
        it.airingAt > 0 && it.episode > 0
    }.groupBy(::key).values.flatMap { group ->
        // Prefer the selected broadcast over a duplicate edition's fallback RAW date.
        val choices = group.sortedWith(
            compareBy<AiringEvent> { it.variants.isEmpty() }.thenBy { it.airingAt }.thenBy { it.entryId },
        )
        val keys = choices.flatMap { listOf(key(it), "entry:${it.entryId}:${it.episode}") }.toSet()
        val event = choices.first()
        ReleaseReminderKind.entries.filter { it in selected }
            .mapNotNull { kind ->
                val at = timeFor(kind, event.airingAt, zone)
                if (at >= event.airingAt && kind != ReleaseReminderKind.AIRING) {
                    null
                } else {
                    ReleaseReminder(kind, choices, keys, at)
                }
            }
            // Selecting two ways to describe the same instant must make only one notification.
            .distinctBy { it.at }
            .filter { reminder ->
                choices.none { it.remindedAt != 0L } &&
                    keys.none { key ->
                        key to reminder.kind.name in delivered || key to reminder.timeReceipt in delivered
                    }
            }
    }.sortedBy { it.at }

    private fun timeFor(kind: ReleaseReminderKind, airingAt: Long, zone: ZoneId): Long = when (kind) {
        ReleaseReminderKind.ADVANCE -> airingAt - ReleasePolicy.DAY
        ReleaseReminderKind.HOUR_BEFORE -> airingAt - ReleasePolicy.HOUR
        ReleaseReminderKind.TEN_MINUTES_BEFORE -> airingAt - 10 * ReleasePolicy.MINUTE
        ReleaseReminderKind.FIVE_MINUTES_BEFORE -> airingAt - 5 * ReleasePolicy.MINUTE
        ReleaseReminderKind.TWO_MINUTES_BEFORE -> airingAt - 2 * ReleasePolicy.MINUTE
        ReleaseReminderKind.AIRING -> airingAt
        else -> {
            val date = Instant.ofEpochMilli(airingAt).atZone(zone).toLocalDate()
                .minusDays(
                    when (kind) {
                        ReleaseReminderKind.DAY_BEFORE_MORNING,
                        ReleaseReminderKind.DAY_BEFORE_AFTERNOON,
                        ReleaseReminderKind.DAY_BEFORE_EVENING,
                        -> 1
                        else -> 0
                    },
                )
            val hour = when (kind) {
                ReleaseReminderKind.DAY_BEFORE_MORNING, ReleaseReminderKind.SAME_DAY_MORNING -> 9
                ReleaseReminderKind.DAY_BEFORE_AFTERNOON, ReleaseReminderKind.SAME_DAY_AFTERNOON -> 15
                else -> 20
            }
            date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()
        }
    }

    private fun key(event: AiringEvent): String = if (event.catalogId > 0) {
        "catalog:${event.catalogId}:${event.episode}"
    } else {
        "entry:${event.entryId}:${event.episode}"
    }
}
