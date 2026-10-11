package eu.kanade.tachiyomi.data.releases

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.domain.discovery.homePresentation
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class AnimeScheduleRepository(private val db: AnimeDatabaseHandler = Injekt.get()) {
    private val preferences = AnimeSchedulePreferences()
    fun records(): Flow<List<ScheduleRecord>> = db.subscribeToList {
        scheduleQueries.getCaches { id, reference, payload, verified, attempted, error ->
            record(id, reference, payload, verified, attempted, error)
        }
    }
    suspend fun record(id: Long): ScheduleRecord? = db.awaitOneOrNull {
        scheduleQueries.getCache(id) { entry, reference, payload, verified, attempted, error ->
            record(entry, reference, payload, verified, attempted, error)
        }
    }

    fun configured(): Boolean = preferences.enabled.get() && preferences.connected.get()

    suspend fun reference(entry: Anime): Pair<AiringCatalogReference, Long?> {
        val tracks = Injekt.get<AnimeTrackRepository>().getTracksByAnimeId(entry.id)
        return AiringCatalogReference.from(entry, tracks) to
            entry.homePresentation?.catalogIds?.get("anidb")?.takeIf { it > 0 }
    }
    private fun key(
        reference: Pair<AiringCatalogReference, Long?>,
    ) = "${reference.first.anilistId}:${reference.first.malId}:${reference.first.expectedMalId}:${reference.second}"

    suspend fun validRecords(records: List<ScheduleRecord>): List<ScheduleRecord> {
        val valid = mutableListOf<ScheduleRecord>()
        for (record in records) {
            if (record.snapshot == null) continue
            val entry = Injekt.get<tachiyomi.domain.entries.anime.repository.AnimeRepository>().getAnimeById(
                record.entryId,
            )
            if (record.reference == key(reference(entry))) valid += record
        }
        return valid
    }

    suspend fun needsMapping(entry: Anime, now: Long): Pair<Boolean, Boolean> {
        if (!configured() || now < preferences.retryAt.get()) return false to false
        val reference = reference(entry)
        if (!reference.first.hasId && reference.second == null) return false to false
        val cached = record(entry.id)
        val cold = cached == null || cached.reference != key(reference) || cached.attemptedAt == 0L
        return (cold || cached!!.due(now)) to cold
    }

    suspend fun refresh(entry: Anime) {
        if (!configured() || System.currentTimeMillis() < preferences.retryAt.get()) return
        val reference = reference(entry)
        if (!reference.first.hasId && reference.second == null) return
        val cached = record(entry.id)
        val fingerprint = key(reference)
        val now = System.currentTimeMillis()
        if (cached?.reference == fingerprint && !cached.due(now)) return
        try {
            val metadata = if (cached?.reference == fingerprint && cached.snapshot != null) {
                client.detail(cached.snapshot.route).takeIf {
                    AnimeScheduleClient.matchesReference(it, reference.first, reference.second)
                }
            } else {
                client.resolve(reference.first, reference.second)
            }
            if (metadata == null) {
                save(ScheduleRecord(entry.id, fingerprint, null, 0, now, "IDENTITY"))
                preferences.state.set("")
                return
            }
            val rows = timetableRows(setOf(now, entry.nextEpisodeAiringAt * 1000).filter { it > 0 })
            val snapshot = preserveReminders(ScheduleParser.snapshot(metadata, rows), cached?.snapshot)
            save(ScheduleRecord(entry.id, fingerprint, snapshot, now, now, ""))
            preferences.state.set("")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = failure(e)
            save(
                ScheduleRecord(
                    entry.id,
                    fingerprint,
                    cached?.snapshot?.takeIf { cached.reference == fingerprint },
                    cached?.verifiedAt ?: 0,
                    now,
                    reason,
                ),
            )
        }
    }

    /** One shared weekly request updates every already mapped title, independent of batch limits. */
    suspend fun refreshTimetables() = weeklyMutex.withLock {
        if (!configured() || System.currentTimeMillis() < preferences.retryAt.get()) return@withLock
        val monitored = ReleaseStore().monitoredIds(ReleaseMedium.ANIME).toSet()
        val all = db.awaitList {
            scheduleQueries.getCaches { id, reference, payload, verified, attempted, error ->
                record(id, reference, payload, verified, attempted, error)
            }
        }.filter { it.snapshot != null && it.entryId in monitored }
        val cached = mutableListOf<ScheduleRecord>()
        for (item in all) {
            if (ReleaseEligibility.source(ReleaseMedium.ANIME, item.entryId) == null) continue
            val entry = Injekt.get<tachiyomi.domain.entries.anime.repository.AnimeRepository>().getAnimeById(
                item.entryId,
            )
            if (item.reference == key(reference(entry))) cached += item
        }
        if (cached.isEmpty()) return@withLock
        val now = System.currentTimeMillis()
        val times = mutableSetOf(now, now + 7 * ReleasePolicy.DAY)
        val cachedIds = cached.map { it.entryId }.toSet()
        for (event in AiringRepository().events().first()) {
            if (event.entryId in cachedIds && event.airingAt > now) times += event.airingAt
        }
        try {
            val rows = timetableRows(times)
            db.await(inTransaction = true) {
                for (item in cached) {
                    val old = item.snapshot ?: continue
                    // Retain confirmed premieres while timetable rows replace changing episode dates.
                    val matching = rows.filter { ScheduleParser.text(it, "route") == old.route }
                    val synthetic = kotlinx.serialization.json.buildJsonObject {
                        put("route", kotlinx.serialization.json.JsonPrimitive(old.route))
                        put(
                            "status",
                            kotlinx.serialization.json.JsonPrimitive(
                                matching.firstOrNull()?.let {
                                    ScheduleParser.text(it, "status")
                                }?.ifBlank { old.status }
                                    ?: old.status,
                            ),
                        )
                    }
                    val parsed = ScheduleParser.snapshot(synthetic, matching)
                    val fresh = preserveReminders(
                        parsed.copy(
                            broadcasts =
                            parsed.broadcasts +
                                old.exceptions.filter { exception ->
                                    parsed.broadcasts.none {
                                        it.episode ==
                                            exception.episode &&
                                            it.type == exception.type
                                    }
                                },
                            premieres = old.premieres,
                            delay = old.delay,
                            exceptions = old.exceptions,
                        ),
                        old,
                    )
                    scheduleQueries.saveCache(
                        item.entryId,
                        item.reference,
                        ScheduleParser.json.encodeToString(fresh),
                        now,
                        item.attemptedAt,
                        "",
                    )
                }
                scheduleQueries.pruneWeeks(now - 14 * ReleasePolicy.DAY)
            }
            preferences.state.set("")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e)
        }
    }

    private suspend fun timetableRows(times: Collection<Long>): List<JsonObject> {
        val weeks = times.map(ScheduleParser::week).toMutableSet().apply {
            add(ScheduleParser.week(System.currentTimeMillis() + 7 * ReleasePolicy.DAY))
        }
        val rows = mutableListOf<JsonObject>()
        for ((year, week) in weeks.sortedWith(compareBy({ it.first }, { it.second }))) {
            val key = "$year-$week"
            val cached = db.awaitOneOrNull { scheduleQueries.getWeek(key) }
            val now = System.currentTimeMillis()
            val ttl = if (ScheduleParser.week(now) ==
                (year to week)
            ) {
                15 * ReleasePolicy.MINUTE
            } else {
                6 * ReleasePolicy.HOUR
            }
            val body = if (cached != null && now - cached.fetched_at in 0 until ttl) {
                cached.payload
            } else {
                val fetched = client.timetable(
                    year,
                    week,
                    allowUnavailable = ScheduleParser.week(now) != (year to week),
                )
                db.await { scheduleQueries.saveWeek(key, fetched, now) }
                fetched
            }
            rows += ScheduleParser.timetable(body)
        }
        return rows
    }

    suspend fun reminded(event: AiringEvent) {
        val cached = record(event.entryId) ?: return
        val snapshot = cached.snapshot ?: return
        val now = System.currentTimeMillis()
        save(
            cached.copy(
                snapshot = snapshot.copy(
                    premiereRemindedAt = if (event.episode ==
                        1
                    ) {
                        now
                    } else {
                        snapshot.premiereRemindedAt
                    },
                    broadcasts = snapshot.broadcasts.map {
                        if (it.episode == event.episode && it.at == event.airingAt) it.copy(remindedAt = now) else it
                    },
                ),
            ),
        )
    }

    suspend fun connect(token: String) {
        val normalized = token.trim().removePrefix("Bearer ").trim()
        if (normalized.length !in 16..8192 || normalized.any(Char::isWhitespace)) throw AnimeScheduleException("AUTH")
        val probe = newClient { normalized }
        val week = ScheduleParser.week(System.currentTimeMillis())
        probe.timetable(week.first, week.second)
        AnimeScheduleTokenStore().save(normalized)
        preferences.connected.set(true)
        preferences.enabled.set(true)
        preferences.state.set("")
        preferences.retryAt.set(0)
    }
    fun disconnect() {
        preferences.enabled.set(false)
        preferences.connected.set(false)
        AnimeScheduleTokenStore().clear()
        preferences.state.set("")
    }

    private suspend fun save(record: ScheduleRecord) = db.await {
        scheduleQueries.saveCache(
            record.entryId,
            record.reference,
            record.snapshot?.let {
                ScheduleParser.json.encodeToString(it)
            }.orEmpty(),
            record.verifiedAt,
            record.attemptedAt,
            record.error,
        )
    }
    private fun failure(error: Exception): String {
        val reason = animeScheduleFailureReason(error)
        preferences.state.set(reason.takeUnless { it == "IDENTITY" }.orEmpty())
        logcat(LogPriority.WARN) {
            "AnimeSchedule check: $reason, HTTP ${(error as? AnimeScheduleException)?.status ?: 0}, " +
                "type ${error.javaClass.simpleName}"
        }
        if (reason == "AUTH") preferences.connected.set(false)
        if (error is AnimeScheduleException && error.retryAt > 0) preferences.retryAt.set(error.retryAt)
        return reason
    }
    private fun record(
        id: Long,
        reference: String,
        payload: String,
        verified: Long,
        attempted: Long,
        error: String,
    ) = ScheduleRecord(
        id,
        reference,
        runCatching {
            ScheduleParser.json.decodeFromString<ScheduleSnapshot>(payload)
        }.getOrNull(),
        verified,
        attempted,
        error,
    )
    private fun preserveReminders(fresh: ScheduleSnapshot, old: ScheduleSnapshot?) = fresh.copy(
        premiereRemindedAt =
        old?.premiereRemindedAt ?: 0,
        broadcasts = fresh.broadcasts.map { row ->
            val previous = old?.broadcasts?.firstOrNull { it.episode == row.episode && it.type == row.type }
            // A revised broadcast date must not notify the same episode twice.
            row.copy(
                remindedAt =
                previous?.remindedAt
                    ?: old?.broadcasts?.firstOrNull { it.episode == row.episode && it.remindedAt > 0 }?.remindedAt
                    ?: if (row.episode == 1) old?.premiereRemindedAt ?: 0 else 0,
            )
        },
    )

    companion object {
        private val weeklyMutex = Mutex()
        private val client by lazy { newClient { AnimeScheduleTokenStore().read() } }
        private fun newClient(token: () -> String?): AnimeScheduleClient {
            val network = AnimeScheduleHttpClient.create(Injekt.get<NetworkHelper>().apiClient)
            return AnimeScheduleClient(network, token)
        }
    }
}
