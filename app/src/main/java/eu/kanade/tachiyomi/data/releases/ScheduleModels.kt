package eu.kanade.tachiyomi.data.releases

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.WeekFields

@Serializable
enum class ScheduleAirType { RAW, SUB, DUB }

@Serializable
data class ScheduleBroadcast(
    val episode: Int,
    val at: Long,
    val type: ScheduleAirType,
    val untilEpisode: Int = episode,
    val delayed: String = "",
    val platforms: List<String> = emptyList(),
    val remindedAt: Long = 0,
)

@Serializable
data class ScheduleSnapshot(
    val route: String,
    val status: String = "",
    val broadcasts: List<ScheduleBroadcast> = emptyList(),
    val premieres: Map<ScheduleAirType, Long> = emptyMap(),
    val delay: String = "",
    val premiereRemindedAt: Long = 0,
    val exceptions: List<ScheduleBroadcast> = emptyList(),
)

internal data class ScheduleRecord(
    val entryId: Long,
    val reference: String,
    val snapshot: ScheduleSnapshot?,
    val verifiedAt: Long,
    val attemptedAt: Long,
    val error: String,
) {
    fun due(now: Long): Boolean {
        val ttl = when {
            error.isNotEmpty() -> 15 * ReleasePolicy.MINUTE
            snapshot?.status.equals("Finished", ignoreCase = true) -> 7 * ReleasePolicy.DAY
            else -> 6 * ReleasePolicy.HOUR
        }
        val age = now - attemptedAt
        return attemptedAt == 0L || age < 0 || age >= ttl
    }
}

/** Parse only explicitly announced dates. Regular weekly air times are never extrapolated. */
internal object ScheduleParser {
    val json = Json { ignoreUnknownKeys = true }
    fun date(value: String?): Long = try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli().takeIf { it > 0 } ?: 0
    } catch (_: Exception) {
        0
    }
    fun text(
        node: JsonObject,
        key: String,
    ): String = (node[key] as? JsonPrimitive)?.content?.takeUnless { it == "null" }.orEmpty()
    fun animePage(body: String): List<JsonObject> {
        val root = json.parseToJsonElement(body).jsonObject
        return (root["anime"] as? JsonArray ?: error("Missing anime page")).map { it.jsonObject }
    }
    fun timetable(body: String): List<JsonObject> =
        (json.parseToJsonElement(body) as? JsonArray ?: error("Missing timetable")).map { it.jsonObject }

    fun snapshot(metadata: JsonObject, rows: List<JsonObject>): ScheduleSnapshot {
        val route = text(metadata, "route").also { require(it.isNotBlank()) }
        val explicit = overrides(metadata)
        val broadcasts = (
            rows.filter { text(it, "route") == route }.mapNotNull { node ->
                val type =
                    ScheduleAirType.entries.firstOrNull { it.name.equals(text(node, "airType"), true) }
                        ?: return@mapNotNull null
                val last =
                    node["episodeNumber"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..65535 } ?: return@mapNotNull null
                val first = node["subtractedEpisodeNumber"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..last } ?: last
                val delayed = text(node, "delayedText").take(500)
                val at = if (text(node, "airingStatus") == "delayed-air") {
                    date(text(node, "delayedUntil"))
                } else {
                    date(text(node, "episodeDate"))
                }
                // Unknown delay dates remain visible as status, but cannot schedule an alarm.
                if (at == 0L && delayed.isEmpty()) return@mapNotNull null
                val streams = node["streams"]
                val platforms = when (streams) {
                    is JsonArray -> streams.mapNotNull {
                        (it as? JsonObject)?.let { row -> text(row, "name").ifBlank { text(row, "platform") } }
                    }
                    is JsonObject -> if (streams.containsKey("platform")) {
                        listOf(text(streams, "name").ifBlank { text(streams, "platform") })
                    } else {
                        streams.keys.filter {
                            text(streams, it).isNotBlank()
                        }.map { it.replaceFirstChar(Char::uppercaseChar) }
                    }
                    else -> emptyList()
                }.filter { it.isNotBlank() }.distinct().take(12)
                ScheduleBroadcast(first, at, type, last, delayed, platforms)
            } +
                explicit
            ).groupBy { it.episode to it.type }.values.map { candidates ->
            // A revised date replaces the earlier announcement for this exact episode/channel.
            candidates.firstOrNull { it.delayed.isNotEmpty() } ?: candidates.maxBy { it.at }
        }.sortedBy { it.at }
        val premieres = buildMap {
            listOf(
                ScheduleAirType.RAW to "premier",
                ScheduleAirType.SUB to "subPremier",
                ScheduleAirType.DUB to "dubPremier",
            ).forEach { (type, field) ->
                val prefix = when (type) {
                    ScheduleAirType.RAW -> ""
                    ScheduleAirType.SUB -> "sub"
                    ScheduleAirType.DUB -> "dub"
                }
                val delayField = if (prefix.isEmpty()) "delayedTimetable" else "${prefix}DelayedTimetable"
                val untilField = if (prefix.isEmpty()) "delayedUntil" else "${prefix}DelayedUntil"
                val at = if (text(
                        metadata,
                        delayField,
                    ).isNotBlank()
                ) {
                    date(text(metadata, untilField))
                } else {
                    date(text(metadata, field))
                }
                if (at > 0 || text(metadata, delayField).isNotBlank()) put(type, at)
            }
        }
        return ScheduleSnapshot(
            route,
            text(metadata, "status"),
            broadcasts,
            premieres,
            text(metadata, "delayedDesc").take(500),
            exceptions = explicit,
        )
    }

    private fun overrides(metadata: JsonObject): List<ScheduleBroadcast> = buildList {
        for ((type, field) in listOf(
            ScheduleAirType.RAW to "episodeOverride",
            ScheduleAirType.SUB to "subEpisodeOverride",
            ScheduleAirType.DUB to "dubEpisodeOverride",
        )) {
            val value = metadata[field]
            val rows = if (value is JsonArray) {
                value.mapNotNull {
                    it as? JsonObject
                }
            } else {
                listOfNotNull(value as? JsonObject)
            }
            for (row in rows) {
                val at = date(text(row, "overrideDate"))
                val last = row["overrideEpisode"]?.jsonPrimitive?.intOrNull ?: continue
                val extra = row["episodesAired"]?.jsonPrimitive?.intOrNull ?: 0
                if (at > 0 &&
                    last in 1..65535 &&
                    extra in 0 until last
                ) {
                    add(ScheduleBroadcast(last - extra, at, type, last))
                }
            }
        }
    }

    fun week(at: Long): Pair<Int, Int> {
        val date = Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate()
        return date.get(WeekFields.ISO.weekBasedYear()) to date.get(WeekFields.ISO.weekOfWeekBasedYear())
    }

    fun overlay(
        base: List<AiringEvent>,
        records: List<ScheduleRecord>,
        preferred: ScheduleAirType,
        now: Long,
    ): List<AiringEvent> {
        val result = base.associateBy { it.entryId to it.episode }.toMutableMap()
        for (record in records) {
            val snapshot = record.snapshot ?: continue
            // Unverified/expired data can never replace a currently verified fallback.
            if (record.verifiedAt == 0L || now - record.verifiedAt > 2 * ReleasePolicy.DAY) continue
            for ((episode, broadcasts) in snapshot.broadcasts.groupBy { it.episode }) {
                val chosen = broadcasts.firstOrNull { it.type == preferred }
                    ?: broadcasts.firstOrNull { it.type == ScheduleAirType.RAW } ?: broadcasts.minBy { it.at }
                val key = record.entryId to episode
                for (number in episode..broadcasts.maxOf { it.untilEpisode }) result.remove(record.entryId to number)
                if (chosen.at == 0L) {
                    result.remove(key)
                    continue
                }
                val catalog = base.firstOrNull { it.entryId == record.entryId }?.catalogId ?: 0
                result[key] =
                    AiringEvent(
                        record.entryId,
                        episode,
                        chosen.at,
                        catalog,
                        chosen.remindedAt,
                        broadcasts,
                        snapshot.status,
                    )
            }
            val premiereType = preferred.takeIf { it in snapshot.premieres }
                ?: ScheduleAirType.RAW.takeIf { it in snapshot.premieres }
                ?: snapshot.premieres.keys.minByOrNull { it.ordinal }
            premiereType?.let { type ->
                val at = snapshot.premieres.getValue(type)
                if (at == 0L) {
                    result.remove(record.entryId to 1)
                    return@let
                }
                // Keep recently due premieres so the alarm can consume them after its scheduled instant.
                if (at < now - 2 * ReleasePolicy.DAY || snapshot.broadcasts.any { it.episode == 1 }) return@let
                val broadcast = ScheduleBroadcast(1, at, type, remindedAt = snapshot.premiereRemindedAt)
                val variants = snapshot.premieres.map { (channel, time) ->
                    ScheduleBroadcast(1, time, channel, remindedAt = snapshot.premiereRemindedAt)
                }
                result[record.entryId to 1] =
                    AiringEvent(record.entryId, 1, at, 0, broadcast.remindedAt, variants, snapshot.status)
            }
        }
        return result.values.sortedBy { it.airingAt }
    }
}
