package eu.kanade.tachiyomi.data.releases

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReleaseReminderPlanTest {
    private val at = 20 * ReleasePolicy.DAY
    private fun event(id: Long = 1, episode: Int = 3, catalog: Long = 42, time: Long = at) =
        AiringEvent(id, episode, time, catalog)

    private fun plan(
        vararg events: AiringEvent,
        delivered: Set<Pair<String, String>> = emptySet(),
        selected: Set<ReleaseReminderKind> = setOf(ReleaseReminderKind.ADVANCE, ReleaseReminderKind.AIRING),
        zone: ZoneId = ZoneId.of("Europe/Rome"),
    ) = ReleaseReminderPlan.pending(events.toList(), delivered, selected, zone)

    @Test fun fallbackAloneSchedulesThePreviousDayAndTheBroadcast() {
        val reminders = plan(event())
        assertEquals(listOf(ReleaseReminderKind.ADVANCE, ReleaseReminderKind.AIRING), reminders.map { it.kind })
        assertEquals(listOf(at - ReleasePolicy.DAY, at), reminders.map { it.at })
    }

    @Test fun disablingAdvanceKeepsTheBroadcastReminder() {
        assertEquals(
            ReleaseReminderKind.AIRING,
            plan(event(), selected = setOf(ReleaseReminderKind.AIRING)).single().kind,
        )
    }

    @Test fun duplicateEditionsNotifyOnceAndWatchingEitherCanSuppressTheGroup() {
        val reminders = plan(event(id = 7), event(id = 2))
        assertEquals(2, reminders.size)
        assertEquals(listOf(2L, 7L), reminders.first().events.map { it.entryId })
        assertEquals(setOf("catalog:42:3", "entry:2:3", "entry:7:3"), reminders.first().keys)
    }

    @Test fun differentCataloguesAndUnverifiedEntriesNeverMerge() {
        assertEquals(
            8,
            plan(event(), event(id = 2, catalog = 43), event(id = 3, catalog = 0), event(id = 4, catalog = 0)).size,
        )
    }

    @Test fun advanceReceiptDoesNotConsumeBroadcastAndSurvivesRestartAndDateChanges() {
        val receipt = plan(event()).first().keys.map { it to ReleaseReminderKind.ADVANCE.name }.toSet()
        assertEquals(
            ReleaseReminderKind.AIRING,
            plan(event(time = at + ReleasePolicy.HOUR), delivered = receipt).single().kind,
        )
        assertEquals(ReleaseReminderKind.AIRING, plan(event(id = 2), delivered = receipt).single().kind)
    }

    @Test fun entryAliasProtectsAgainstGainingOrLosingACatalogueMapping() {
        val receipt = setOf("entry:1:3" to "ADVANCE")
        assertEquals(ReleaseReminderKind.AIRING, plan(event(catalog = 0), delivered = receipt).single().kind)
        assertEquals(ReleaseReminderKind.AIRING, plan(event(catalog = 42), delivered = receipt).single().kind)
    }

    @Test fun oldBroadcastAcknowledgementsDoNotProduceAnAdvanceAlertOnUpgrade() {
        assertTrue(plan(event().copy(remindedAt = 1)).isEmpty())
        assertTrue(plan(event(id = 2), event().copy(remindedAt = 1)).isEmpty())
    }

    @Test fun providerBroadcastWinsOverDuplicateFallbackAndSwitchingRetainsDelivery() {
        val preferred = event(id = 2, time = at + ReleasePolicy.HOUR).copy(
            variants = listOf(ScheduleBroadcast(3, at + ReleasePolicy.HOUR, ScheduleAirType.SUB)),
        )
        val reminders = plan(event(), preferred)
        assertEquals(preferred, reminders.first().event)
        assertEquals(preferred.airingAt - ReleasePolicy.DAY, reminders.first().at)
        val receipt = reminders.first().keys.map { it to "ADVANCE" }.toSet()
        assertEquals(ReleaseReminderKind.AIRING, plan(event(), delivered = receipt).single().kind)
    }

    @Test fun nextAlarmUsesReminderTimeInsteadOfEpisodeTimeOrInputOrder() {
        val reminders = plan(
            event(time = at + 10 * ReleasePolicy.HOUR),
            event(
                id = 2,
                catalog = 43,
                time =
                at - ReleasePolicy.HOUR,
            ),
        )
        assertEquals(2L, reminders.first().event.entryId)
        assertEquals(reminders.map { it.at }.sorted(), reminders.map { it.at })
    }

    @Test fun batteryDelayedAlarmsHaveABoundedRecoveryWindow() {
        val reminders = plan(event())
        val advance = reminders.first()
        val broadcast = reminders.last()
        assertFalse(advance.expired(advance.at + ReleasePolicy.HOUR))
        assertFalse(broadcast.expired(broadcast.at + ReleasePolicy.HOUR))
        assertTrue(advance.expired(advance.at + 6 * ReleasePolicy.HOUR + 1))
        assertTrue(broadcast.expired(broadcast.at + 2 * ReleasePolicy.HOUR + 1))
    }

    @Test fun missedAlertsDoNotFireAfterOfflinePeriodsAndPostponedDatesCanBeRescheduled() {
        assertTrue(plan(event()).all { it.expired(at + 3 * ReleasePolicy.DAY) })
        val rescheduled = plan(event(time = at + 5 * ReleasePolicy.DAY))
        assertFalse(rescheduled.first().expired(at + 3 * ReleasePolicy.DAY))
    }

    @Test fun advanceIs24ElapsedHoursEvenAcrossDaylightSavingChanges() {
        val airing = ZonedDateTime.parse("2026-10-25T12:00:00+01:00[Europe/Rome]").toInstant().toEpochMilli()
        val reminder = plan(event(time = airing)).first()
        assertEquals(24 * ReleasePolicy.HOUR, airing - reminder.at)
    }

    @Test fun localDaypartsAndOffsetsUseTheirOwnInstantsAcrossDaylightSaving() {
        val zone = ZoneId.of("Europe/Rome")
        val airing = ZonedDateTime.parse("2026-10-25T21:47:00+01:00[Europe/Rome]").toInstant().toEpochMilli()
        val selected = ReleaseReminderKind.entries.toSet()
        val reminders = plan(event(time = airing), selected = selected, zone = zone).associateBy { it.kind }
        fun local(kind: ReleaseReminderKind) =
            ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(reminders.getValue(kind).at), zone)
        assertEquals(9, local(ReleaseReminderKind.DAY_BEFORE_MORNING).hour)
        assertEquals(24, local(ReleaseReminderKind.DAY_BEFORE_MORNING).dayOfMonth)
        assertEquals(15, local(ReleaseReminderKind.DAY_BEFORE_AFTERNOON).hour)
        assertEquals(20, local(ReleaseReminderKind.DAY_BEFORE_EVENING).hour)
        assertEquals(9, local(ReleaseReminderKind.SAME_DAY_MORNING).hour)
        assertEquals(25, local(ReleaseReminderKind.SAME_DAY_MORNING).dayOfMonth)
        assertEquals(15, local(ReleaseReminderKind.SAME_DAY_AFTERNOON).hour)
        assertEquals(20, local(ReleaseReminderKind.SAME_DAY_EVENING).hour)
        assertEquals(ReleasePolicy.HOUR, airing - reminders.getValue(ReleaseReminderKind.HOUR_BEFORE).at)
        assertEquals(10 * ReleasePolicy.MINUTE, airing - reminders.getValue(ReleaseReminderKind.TEN_MINUTES_BEFORE).at)
        assertEquals(5 * ReleasePolicy.MINUTE, airing - reminders.getValue(ReleaseReminderKind.FIVE_MINUTES_BEFORE).at)
        assertEquals(2 * ReleasePolicy.MINUTE, airing - reminders.getValue(ReleaseReminderKind.TWO_MINUTES_BEFORE).at)
        assertEquals(reminders.values.map { it.at }.toSet().size, reminders.size)
    }

    @Test fun fixedSameDayMomentsAfterTheReleaseAreNotScheduled() {
        val airing = ZonedDateTime.parse("2026-11-01T14:00:00+01:00[Europe/Rome]").toInstant().toEpochMilli()
        val selected = setOf(
            ReleaseReminderKind.SAME_DAY_MORNING,
            ReleaseReminderKind.SAME_DAY_AFTERNOON,
            ReleaseReminderKind.SAME_DAY_EVENING,
        )
        assertEquals(
            listOf(ReleaseReminderKind.SAME_DAY_MORNING),
            plan(event(time = airing), selected = selected).map { it.kind },
        )
        val nine = ZonedDateTime.parse("2026-11-01T09:00:00+01:00[Europe/Rome]").toInstant().toEpochMilli()
        assertTrue(
            plan(event(time = nine), selected = setOf(ReleaseReminderKind.SAME_DAY_MORNING)).isEmpty(),
        )
    }

    @Test fun equivalentChoicesAndPreferenceChangesNeverDeliverTwiceAtTheSameInstant() {
        val airing = ZonedDateTime.parse("2026-11-01T20:00:00+01:00[Europe/Rome]").toInstant().toEpochMilli()
        val selected = setOf(ReleaseReminderKind.ADVANCE, ReleaseReminderKind.DAY_BEFORE_EVENING)
        val reminders = plan(event(time = airing), selected = selected)
        assertEquals(1, reminders.size)
        assertEquals(ReleaseReminderKind.ADVANCE, reminders.single().kind)
        val receipt = reminders.single().keys.map { it to reminders.single().timeReceipt }.toSet()
        assertTrue(plan(event(time = airing), delivered = receipt, selected = selected).isEmpty())
        assertTrue(
            plan(event(time = airing), delivered = receipt, selected = setOf(ReleaseReminderKind.DAY_BEFORE_EVENING))
                .isEmpty(),
        )
    }

    @Test fun shortCountdownNeverArrivesAfterAirTimeOrFarBehindSchedule() {
        val ten = plan(event(), selected = setOf(ReleaseReminderKind.TEN_MINUTES_BEFORE)).single()
        val two = plan(event(), selected = setOf(ReleaseReminderKind.TWO_MINUTES_BEFORE)).single()
        assertFalse(ten.expired(ten.at + 4 * ReleasePolicy.MINUTE))
        assertTrue(ten.expired(ten.at + 6 * ReleasePolicy.MINUTE))
        assertFalse(two.expired(two.at + ReleasePolicy.MINUTE))
        assertTrue(two.expired(at))
    }

    @Test fun choosingNoMomentsSchedulesNoReminder() {
        assertTrue(plan(event(), selected = emptySet()).isEmpty())
    }

    @Test fun malformedOrUnknownDatesCannotScheduleAnAlarm() {
        assertTrue(plan(event(time = 0), event(episode = 0)).isEmpty())
    }
}
