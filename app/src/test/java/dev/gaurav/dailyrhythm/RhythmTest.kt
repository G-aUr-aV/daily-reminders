package dev.gaurav.dailyrhythm

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RhythmTest {
    private class Disk : KeyValue {
        val data = mutableMapOf<String, String>()
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
    }
    private val date = LocalDate.of(2026, 11, 8)
    @Test fun localDateUsesDeviceZoneNotUtc() {
        val instant = Instant.parse("2026-11-07T20:00:00Z")
        assertEquals(date, CalendarLogic.today(instant, ZoneId.of("Asia/Kolkata")))
        assertEquals(date.minusDays(1), CalendarLogic.today(instant, ZoneOffset.UTC))
    }
    @Test fun todaySelectionFollowsMidnight() {
        val zone = ZoneId.of("Asia/Kolkata")
        assertEquals(date, CalendarLogic.selectedDate(null, Instant.parse("2026-11-08T18:29:59Z"), zone))
        assertEquals(date.plusDays(1), CalendarLogic.selectedDate(null, Instant.parse("2026-11-08T18:30:00Z"), zone))
    }
    @Test fun historicalSelectionDoesNotFollowMidnightOrTravel() {
        assertEquals(date, CalendarLogic.selectedDate(date, Instant.parse("2026-11-20T00:00:00Z"), ZoneId.of("Pacific/Auckland")))
    }
    @Test fun nextTriggerBeforeTimeUsesToday() {
        assertEquals(Instant.parse("2026-11-08T00:30:00Z"), CalendarLogic.nextTrigger(LocalTime.of(6, 0), Instant.parse("2026-11-08T00:00:00Z"), ZoneId.of("Asia/Kolkata")))
    }
    @Test fun nextTriggerAtExactTimeIsStrictlyFuture() {
        assertEquals(Instant.parse("2026-11-09T00:30:00Z"), CalendarLogic.nextTrigger(LocalTime.of(6, 0), Instant.parse("2026-11-08T00:30:00Z"), ZoneId.of("Asia/Kolkata")))
    }
    @Test fun missedReminderSchedulesFutureNotBackfill() {
        assertEquals(Instant.parse("2026-11-09T00:30:00Z"), CalendarLogic.nextTrigger(LocalTime.of(6, 0), Instant.parse("2026-11-08T22:00:00Z"), ZoneId.of("Asia/Kolkata")))
    }
    @Test fun springDstGapMovesForwardByGap() {
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), CalendarLogic.nextTrigger(LocalTime.of(2, 30), Instant.parse("2026-03-08T05:00:00Z"), ZoneId.of("America/New_York")))
    }
    @Test fun fallDstOverlapUsesEarlierOffsetOnce() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), CalendarLogic.nextTrigger(LocalTime.of(1, 30), Instant.parse("2026-11-01T04:00:00Z"), zone))
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), CalendarLogic.nextTrigger(LocalTime.of(1, 30), Instant.parse("2026-11-01T05:30:00Z"), zone))
    }
    @Test fun recurrenceIsWallTimeNot24HoursAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-03-07T11:00:00Z")
        val next = CalendarLogic.nextTrigger(LocalTime.of(6, 0), now, zone)
        assertEquals(Instant.parse("2026-03-08T10:00:00Z"), next)
        assertEquals(23L, Duration.between(now, next).toHours())
    }
    @Test fun timezoneChangeRecalculatesLocalTime() {
        val now = Instant.parse("2026-11-08T00:00:00Z")
        assertNotEquals(CalendarLogic.nextTrigger(LocalTime.of(6, 0), now, ZoneId.of("Asia/Kolkata")), CalendarLogic.nextTrigger(LocalTime.of(6, 0), now, ZoneId.of("Europe/London")))
    }
    @Test fun newInstallHasNoHistoryAndNoLiveReminders() {
        val store = RhythmStore(Disk())
        assertFalse(store.remindersEnabled)
        val summary = summarize(store, date)
        assertEquals(0, summary.logged)
        assertEquals(42, summary.unlogged)
        assertNull(summary.completionOfLogged)
        assertTrue(summary.days.all { it.rounds == 0 })
    }
    @Test fun initialTimesMatchBriefAndUnsetNeverInvented() {
        val store = RhythmStore(Disk())
        assertEquals(LocalTime.of(6, 0), store.schedule(Routines.byId("wake")!!).time)
        assertEquals(LocalTime.of(7, 0), store.schedule(Routines.byId("pack")!!).time)
        assertEquals(LocalTime.of(7, 30), store.schedule(Routines.byId("depart")!!).time)
        assertEquals(LocalTime.of(23, 0), store.schedule(Routines.byId("bed")!!).time)
        assertNull(store.schedule(Routines.byId("exercise")!!).time)
        assertNull(store.schedule(Routines.byId("shuttle")!!).time)
    }
    @Test fun missingIsNotSkippedAndDenominatorUsesOnlyLogged() {
        val store = RhythmStore(Disk())
        store.setEntry(date, "wake", Entry(Outcome.DONE, LocalTime.of(6, 1)))
        store.setEntry(date, "depart", Entry(Outcome.LATE, LocalTime.of(8, 0)))
        store.setEntry(date, "bed", Entry(Outcome.SKIPPED))
        val summary = summarize(store, date)
        assertEquals(42, summary.slots)
        assertEquals(3, summary.logged)
        assertEquals(39, summary.unlogged)
        assertEquals(1, summary.skipped)
        assertEquals(2.0 / 3.0, summary.completionOfLogged!!, 0.00001)
    }
    @Test fun weekContainsSevenAscendingDatesEndingToday() {
        assertEquals((6 downTo 0).map { date.minusDays(it.toLong()) }, CalendarLogic.sevenDays(date))
    }
    @Test fun outcomesAndRoundsSurviveRepositoryRecreation() {
        val disk = Disk()
        RhythmStore(disk).apply {
            setEntry(date, "wake", Entry(Outcome.LATE, LocalTime.of(6, 23)))
            setEntry(date, "depart", Entry(Outcome.DONE, LocalTime.of(7, 31)))
            setEntry(date, "bed", Entry(Outcome.DONE, LocalTime.of(23, 17)))
            setRounds(date, 3)
            remindersEnabled = true
            setSchedule(Routines.all[1].id, Schedule(LocalTime.of(6, 30), false))
        }
        val reloaded = RhythmStore(disk)
        assertEquals(Entry(Outcome.LATE, LocalTime.of(6, 23)), reloaded.entry(date, "wake"))
        assertEquals(LocalTime.of(7, 31), reloaded.entry(date, "depart").actual)
        assertEquals(LocalTime.of(23, 17), reloaded.entry(date, "bed").actual)
        assertEquals(3, reloaded.rounds(date))
        assertEquals(0, reloaded.rounds(date.plusDays(1)))
        assertEquals(Entry(), reloaded.entry(date.plusDays(1), "wake"))
        assertEquals(Schedule(LocalTime.of(6, 30), false), reloaded.schedule(Routines.all[1]))
        assertTrue(reloaded.remindersEnabled)
    }
    @Test fun reportsAreReversibleAndClearingRemovesActualTime() {
        val store = RhythmStore(Disk())
        store.setEntry(date, "wake", Entry(Outcome.DONE, LocalTime.of(6, 2)))
        store.setEntry(date, "wake", Entry(Outcome.SKIPPED, LocalTime.of(6, 2)))
        assertEquals(Entry(Outcome.SKIPPED), store.entry(date, "wake"))
        store.setEntry(date, "wake", Entry(Outcome.UNLOGGED, LocalTime.of(6, 2)))
        assertEquals(Entry(), store.entry(date, "wake"))
    }
    @Test fun settingTimeToUnsetPersistsAndDoesNotRestoreDefault() {
        val disk = Disk()
        RhythmStore(disk).setSchedule("wake", Schedule(null, true))
        assertEquals(Schedule(null, true), RhythmStore(disk).schedule(Routines.all.first()))
    }
    @Test fun roundsCanBeUndoneAndResetOnlyForSelectedDate() {
        val store = RhythmStore(Disk())
        store.setRounds(date, 3); store.setRounds(date.minusDays(1), 2)
        store.setRounds(date, 2); assertEquals(2, store.rounds(date))
        store.setRounds(date, 0); assertEquals(0, store.rounds(date))
        assertEquals(2, store.rounds(date.minusDays(1)))
    }
    @Test(expected = IllegalArgumentException::class) fun roundsCannotExceedThree() { RhythmStore(Disk()).setRounds(date, 4) }
    @Test fun corruptStoredValuesFailSafeWithoutInventingProgress() {
        val disk = Disk()
        disk.data["entry.$date.wake"] = "INVALID|25:92"
        disk.data["rounds.$date"] = "oops"
        disk.data["schedule.wake"] = "broken|true"
        val store = RhythmStore(disk)
        assertEquals(Entry(), store.entry(date, "wake"))
        assertEquals(0, store.rounds(date))
        assertNull(store.schedule(Routines.all.first()).time)
    }
    @Test fun scheduleEditsNeverRewriteHistory() {
        val store = RhythmStore(Disk())
        store.setEntry(date, "wake", Entry(Outcome.LATE, LocalTime.of(6, 15)))
        store.setSchedule("wake", Schedule(LocalTime.of(8, 0), false))
        assertEquals(Entry(Outcome.LATE, LocalTime.of(6, 15)), store.entry(date, "wake"))
    }
    @Test fun sampleRitualPrefillsGenericStepsWithoutWritingThePlan() {
        val plan = FlexiblePlanner(Disk(), includeLegacyRoutines = false) { date }
        val sample = ReminderSample.MORNING.draft(date)
        assertEquals(ItemKind.RITUAL, sample.kind)
        assertEquals(3, sample.content.size)
        assertTrue(sample.content.all(String::isNotBlank))
        assertTrue(plan.state.items.isEmpty())
    }
}
