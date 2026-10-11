package eu.kanade.tachiyomi.data.releases

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnimeScheduleTest {
    private val now = ScheduleParser.date("2026-09-29T10:00:00Z")
    private val metadata = Json.parseToJsonElement("""{"route":"sample-series","status":"Ongoing"}""").jsonObject
    private fun record(
        snapshot: ScheduleSnapshot,
        verified: Long = now,
    ) = ScheduleRecord(1, "sample-id", snapshot, verified, now, "")
    private fun broadcast(
        type: ScheduleAirType,
        at: Long = now + ReleasePolicy.HOUR,
        episode: Int = 3,
    ) = ScheduleBroadcast(episode, at, type)
    private fun overlay(
        snapshot: ScheduleSnapshot,
        type: ScheduleAirType = ScheduleAirType.SUB,
        base: List<AiringEvent> = emptyList(),
    ) =
        ScheduleParser.overlay(base, listOf(record(snapshot)), type, now)

    @Test fun isoOffsetAndDstAreConvertedToTheSameInstant() {
        assertEquals(ScheduleParser.date("2026-10-25T00:30:00Z"), ScheduleParser.date("2026-10-25T02:30:00+02:00"))
        assertEquals(
            ReleasePolicy.HOUR,
            ScheduleParser.date("2026-10-25T02:30:00+01:00") - ScheduleParser.date("2026-10-25T02:30:00+02:00"),
        )
    }

    @Test fun invalidAndSentinelDatesNeverCreateAnAlarm() {
        assertEquals(0L, ScheduleParser.date("0001-01-01T00:00:00Z"))
        assertEquals(0L, ScheduleParser.date("not-a-date"))
        assertEquals(0L, ScheduleParser.date(null))
    }

    @Test fun isoWeekUsesItsWeekBasedYearAtNewYear() {
        assertEquals(2020 to 53, ScheduleParser.week(ScheduleParser.date("2021-01-01T12:00:00Z")))
    }

    @Test fun actualRawResponseIsNeverRelabeledAsSub() {
        val rows = ScheduleParser.timetable(
            """[{"route":"sample-series","airType":"raw","episodeNumber":3,"episodeDate":"2026-09-29T12:00:00Z"}]""",
        )
        val events = overlay(ScheduleParser.snapshot(metadata, rows))
        assertEquals(ScheduleAirType.RAW, events.single().variants.single().type)
    }

    @Test fun unrelatedRoutesCannotLeakIntoATitle() {
        val rows = ScheduleParser.timetable(
            """[{"route":"another-series","airType":"sub","episodeNumber":3,"episodeDate":"2026-09-29T12:00:00Z"}]""",
        )
        assertTrue(ScheduleParser.snapshot(metadata, rows).broadcasts.isEmpty())
    }

    @Test fun structuredOptionalMetadataDoesNotBreakTheSchedule() {
        val data = Json.parseToJsonElement(
            """{"route":"sample-series","status":"Ongoing","delayedDesc":{},"subDelayedTimetable":[]}""",
        ).jsonObject
        val snapshot = ScheduleParser.snapshot(data, emptyList())
        assertEquals("sample-series", snapshot.route)
        assertTrue(snapshot.delay.isEmpty())
    }

    @Test fun preferredBroadcastAndOtherChannelsRemainDistinct() {
        val sub = broadcast(ScheduleAirType.SUB, now + 2 * ReleasePolicy.HOUR)
        val raw = broadcast(ScheduleAirType.RAW)
        val result = overlay(ScheduleSnapshot("sample-series", broadcasts = listOf(raw, sub))).single()
        assertEquals(sub.at, result.airingAt)
        assertEquals(2, result.variants.size)
    }

    @Test fun missingDubFallsBackToRawWithoutPretendingItIsDub() {
        val raw = broadcast(ScheduleAirType.RAW)
        assertEquals(
            raw.at,
            overlay(ScheduleSnapshot("sample-series", broadcasts = listOf(raw)), ScheduleAirType.DUB).single().airingAt,
        )
    }

    @Test fun dubOnlyPremiereRemainsVisibleWhenSubIsPreferred() {
        val snapshot = ScheduleSnapshot("sample-series", premieres = mapOf(ScheduleAirType.DUB to now))
        val result = overlay(snapshot).single()
        assertEquals(now, result.airingAt)
        assertEquals(ScheduleAirType.DUB, result.variants.single().type)
    }

    @Test fun activeSeriesMetadataIsRefreshedBeforeAWeekOfPossibleDelays() {
        val cached = record(ScheduleSnapshot("sample-series", status = "Ongoing"))
        assertFalse(cached.due(now + 5 * ReleasePolicy.HOUR))
        assertTrue(cached.due(now + 6 * ReleasePolicy.HOUR))
    }

    @Test fun finishedSeriesMetadataDoesNotNeedFrequentRequests() {
        val cached = record(ScheduleSnapshot("sample-series", status = "Finished"))
        assertFalse(cached.due(now + 6 * ReleasePolicy.DAY))
        assertTrue(cached.due(now + 7 * ReleasePolicy.DAY))
    }

    @Test fun aClockCorrectionCannotFreezeMetadataRefreshes() {
        val cached = record(ScheduleSnapshot("sample-series", status = "Ongoing"))
        assertTrue(cached.due(now - ReleasePolicy.DAY))
    }

    @Test fun distantFallbackDatesRemainInTheAgenda() {
        val distant = AiringEvent(1, 99, now + 96 * ReleasePolicy.DAY, 47, 0)
        val result =
            overlay(
                ScheduleSnapshot("sample-series", broadcasts = listOf(broadcast(ScheduleAirType.RAW))),
                base = listOf(distant),
            )
        assertTrue(result.contains(distant))
    }

    @Test fun expiredProviderDataCannotReplaceFallback() {
        val base = listOf(AiringEvent(1, 3, now + ReleasePolicy.HOUR, 47, 0))
        val old = record(
            ScheduleSnapshot("sample-series", broadcasts = listOf(broadcast(ScheduleAirType.SUB))),
            now - 3 * ReleasePolicy.DAY,
        )
        assertEquals(base, ScheduleParser.overlay(base, listOf(old), ScheduleAirType.SUB, now))
    }

    @Test fun unverifiedRecordsCannotReplaceFallback() {
        assertTrue(
            ScheduleParser.overlay(
                emptyList(),
                listOf(
                    record(ScheduleSnapshot("sample-series", broadcasts = listOf(broadcast(ScheduleAirType.SUB))), 0),
                ),
                ScheduleAirType.SUB,
                now,
            ).isEmpty(),
        )
    }

    @Test fun unknownDelaySuppressesTheOutdatedEpisodeTime() {
        val rows = ScheduleParser.timetable(
            """[{"route":"sample-series","airType":"sub","episodeNumber":3,"episodeDate":"2026-09-29T12:00:00Z","airingStatus":"delayed-air","delayedText":"Date pending"}]""",
        )
        val result = ScheduleParser.snapshot(metadata, rows)
        assertEquals(0L, result.broadcasts.single().at)
        assertTrue(overlay(result, base = listOf(AiringEvent(1, 3, now + ReleasePolicy.HOUR, 47, 0))).isEmpty())
    }

    @Test fun explicitDelayedUntilReplacesTheOriginalDate() {
        val rows = ScheduleParser.timetable(
            """[{"route":"sample-series","airType":"raw","episodeNumber":3,"episodeDate":"2026-09-29T12:00:00Z","airingStatus":"delayed-air","delayedText":"Moved","delayedUntil":"2026-10-01T12:00:00Z"}]""",
        )
        assertEquals(
            ScheduleParser.date("2026-10-01T12:00:00Z"),
            ScheduleParser.snapshot(metadata, rows).broadcasts.single().at,
        )
    }

    @Test fun revisedRowsForAnEpisodeProduceOneAnnouncementPerChannel() {
        val rows = ScheduleParser.timetable(
            """[{"route":"sample-series","airType":"raw","episodeNumber":3,"episodeDate":"2026-09-29T12:00:00Z"},{"route":"sample-series","airType":"raw","episodeNumber":3,"episodeDate":"2026-10-01T12:00:00Z"}]""",
        )
        assertEquals(
            ScheduleParser.date("2026-10-01T12:00:00Z"),
            ScheduleParser.snapshot(metadata, rows).broadcasts.single().at,
        )
    }

    @Test fun announcedBatchDoesNotDuplicateFallbackEpisodes() {
        val batch = broadcast(ScheduleAirType.SUB, episode = 3).copy(untilEpisode = 5)
        val base = (3..5).map { AiringEvent(1, it, now + ReleasePolicy.HOUR, 47, 0) }
        assertEquals(1, overlay(ScheduleSnapshot("sample-series", broadcasts = listOf(batch)), base = base).size)
    }

    @Test fun exceptionDatesDoNotRequireWeeklyExtrapolation() {
        val data = Json.parseToJsonElement(
            """{"route":"sample-series","episodeOverride":{"overrideDate":"2026-10-01T12:00:00Z","overrideEpisode":12,"episodesAired":5},"jpnTime":"2026-09-29T12:00:00Z"}""",
        ).jsonObject
        val row = ScheduleParser.snapshot(data, emptyList()).broadcasts.single()
        assertEquals(7, row.episode)
        assertEquals(12, row.untilEpisode)
        assertEquals(ScheduleParser.date("2026-10-01T12:00:00Z"), row.at)
    }

    @Test fun ordinaryWeeklyTimeNeverInventsARelease() {
        val data = Json.parseToJsonElement("""{"route":"sample-series","jpnTime":"2026-09-29T12:00:00Z"}""").jsonObject
        assertTrue(ScheduleParser.snapshot(data, emptyList()).broadcasts.isEmpty())
    }

    @Test fun premiereHasAllVariantsAndPreservesReminderConsumption() {
        val snapshot =
            ScheduleSnapshot(
                "sample-series",
                premieres = mapOf(
                    ScheduleAirType.RAW to now + ReleasePolicy.HOUR,
                    ScheduleAirType.SUB to now + 2 * ReleasePolicy.HOUR,
                ),
                premiereRemindedAt = now,
            )
        val result = overlay(snapshot).single()
        assertEquals(now + 2 * ReleasePolicy.HOUR, result.airingAt)
        assertEquals(now, result.remindedAt)
        assertEquals(2, result.variants.size)
    }

    @Test fun anAlarmCanStillConsumeThePremiereJustAfterItsDueTime() {
        val snapshot = ScheduleSnapshot("sample-series", premieres = mapOf(ScheduleAirType.SUB to now - 1000))
        val event = overlay(snapshot).single()
        assertEquals(now - 1000, event.airingAt)
        assertEquals(0L, event.remindedAt)
    }

    @Test fun postponedPremiereWithUnknownDateSuppressesTheOldPremiere() {
        val data = Json.parseToJsonElement(
            """{"route":"sample-series","premier":"2026-10-01T12:00:00Z","delayedTimetable":"Delayed"}""",
        ).jsonObject
        assertTrue(
            overlay(
                ScheduleParser.snapshot(data, emptyList()),
                base = listOf(
                    AiringEvent(
                        1,
                        1,
                        now + ReleasePolicy.HOUR,
                        47,
                        0,
                    ),
                ),
            ).isEmpty(),
        )
    }

    @Test fun currentAndLegacyPlatformShapesAreAccepted() {
        for (streams in listOf(
            """[{"platform":"platform-a","name":"Platform A"}]""",
            """{"platform":"platform-a","name":"Platform A"}""",
            """{"platformA":"https://platform.invalid/title","platformB":null}""",
        )) {
            val rows = ScheduleParser.timetable(
                """[{"route":"sample-series","airType":"raw","episodeNumber":3,"episodeDate":"2026-09-29T12:00:00Z","streams":$streams}]""",
            )
            assertEquals(1, ScheduleParser.snapshot(metadata, rows).broadcasts.single().platforms.size)
        }
    }

    @Test fun scheduleColdEntriesJoinTheExistingDurableQueue() {
        val candidates = (1L..15L).map {
            AiringRefreshQueue.Candidate(
                it,
                AiringCache(now, now, "AVAILABLE"),
                true,
                scheduleDue = true,
                scheduleCold = true,
            )
        }
        val batch = AiringRefreshQueue.batch(candidates, now)
        assertEquals(6, batch.size)
        assertTrue(AiringRefreshQueue.hasColdBacklog(candidates, batch.map { it.entryId }.toSet()))
        assertFalse(AiringRefreshQueue.hasColdBacklog(candidates.take(6), batch.map { it.entryId }.toSet()))
    }
}
