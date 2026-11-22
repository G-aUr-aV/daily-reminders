package dev.gaurav.dailyrhythm

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.*
import java.time.temporal.ChronoUnit

/** Deterministic simulation of a delayed OS alarm; not real Android delivery evidence. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OccurrenceRegressionTest {
    private lateinit var app: Application
    private lateinit var store: RhythmStore
    private lateinit var alarm: AlarmManager
    private lateinit var scheduler: ReminderScheduler
    private var current = Instant.parse("2026-11-08T06:59:00Z")
    private var currentZone: ZoneId = ZoneOffset.UTC
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("rhythm_v1", Context.MODE_PRIVATE).edit().clear().commit()
        store = app.rhythmStore()
        alarm = app.getSystemService(AlarmManager::class.java)
        scheduler = ReminderScheduler(app, { current }, { currentZone })
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store.remindersEnabled = true
        scheduler.sync()
    }
    private fun scheduled(id: String) = shadowOf(alarm).scheduledAlarms.single {
        shadowOf(requireNotNull(it.operation)).savedIntent.getStringExtra("routine_id") == id
    }
    private fun payload(id: String) = Intent(shadowOf(requireNotNull(scheduled(id).operation)).savedIntent)
    private fun trigger(id: String) = scheduled(id).triggerAtTime
    private fun token(id: String) = payload(id).getStringExtra(ReminderScheduler.TOKEN)
    private fun delayed() { current = Instant.parse("2026-11-08T07:00:05Z") }
    private fun assertFourUnique() {
        assertEquals(4, shadowOf(alarm).scheduledAlarms.size)
        assertEquals(4, shadowOf(alarm).scheduledAlarms.map { shadowOf(requireNotNull(it.operation)).savedIntent.getStringExtra("routine_id") }.distinct().size)
    }

    @Test fun dueButUndeliveredOccurrenceSurvivesOrdinaryResumeSyncUnchanged() {
        val original = scheduled("pack")
        val originalToken = token("pack")
        delayed(); scheduler.sync(); scheduler.sync()
        assertSame(original, scheduled("pack"))
        assertEquals(Instant.parse("2026-11-08T07:00:00Z").toEpochMilli(), trigger("pack"))
        assertEquals(originalToken, token("pack"))
        assertFourUnique()
    }
    @Test fun unrelatedRoutineEditDoesNotCancelDelayedPacking() {
        val original = scheduled("pack")
        val oldBed = token("bed")
        delayed()
        store.setSchedule("bed", Schedule(LocalTime.of(22, 30), true)); scheduler.sync()
        assertSame(original, scheduled("pack"))
        assertEquals(Instant.parse("2026-11-08T07:00:00Z").toEpochMilli(), trigger("pack"))
        assertNotEquals(oldBed, token("bed"))
        assertFourUnique()
    }
    @Test fun savingIdenticalScheduleIsANoOpEvenWhenDue() {
        val original = scheduled("pack")
        delayed(); store.setSchedule("pack", Schedule(LocalTime.of(7, 0), true)); scheduler.sync()
        assertSame(original, scheduled("pack"))
    }
    @Test fun changedRoutineGetsNewOccurrenceAndStalePayloadCannotAdvanceIt() {
        val stale = payload("pack")
        delayed(); store.setSchedule("pack", Schedule(LocalTime.of(8, 0), true)); scheduler.sync()
        val updated = scheduled("pack")
        assertEquals(Instant.parse("2026-11-08T08:00:00Z").toEpochMilli(), trigger("pack"))
        assertNotEquals(stale.getStringExtra(ReminderScheduler.TOKEN), token("pack"))
        assertFalse(scheduler.consume(stale))
        assertSame(updated, scheduled("pack"))
        assertFourUnique()
    }
    @Test fun disablingAndReenablingDiscardsOldOccurrenceWithoutRevival() {
        val stale = payload("pack")
        delayed(); store.setSchedule("pack", Schedule(LocalTime.of(7, 0), false)); scheduler.sync()
        assertEquals(3, shadowOf(alarm).scheduledAlarms.size)
        assertNull(Preferences(app).get("alarm_v2.pack"))
        assertFalse(scheduler.consume(stale))
        store.setSchedule("pack", Schedule(LocalTime.of(7, 0), true)); scheduler.sync()
        assertEquals(Instant.parse("2026-11-09T07:00:00Z").toEpochMilli(), trigger("pack"))
        assertFalse(scheduler.consume(stale))
        assertFourUnique()
    }
    @Test fun masterDisableCancelsAllAndReenableCreatesOnlyFutureOccurrences() {
        scheduler.test()
        val stale = payload("pack")
        delayed(); store.remindersEnabled = false; scheduler.sync()
        assertTrue(shadowOf(alarm).scheduledAlarms.isEmpty())
        assertFalse(scheduler.consume(stale))
        store.remindersEnabled = true; scheduler.sync()
        assertEquals(Instant.parse("2026-11-09T07:00:00Z").toEpochMilli(), trigger("pack"))
        assertFalse(scheduler.consume(stale))
        assertFourUnique()
    }
    @Test fun deliveryAdvancesOnceAndDuplicateDeliveryDoesNotPostponeAgain() {
        val delivered = payload("pack")
        delayed()
        assertTrue(scheduler.consume(delivered))
        val next = scheduled("pack")
        assertEquals(Instant.parse("2026-11-09T07:00:00Z").toEpochMilli(), trigger("pack"))
        assertFalse(scheduler.consume(delivered))
        assertSame(next, scheduled("pack"))
        assertFourUnique()
    }
    @Test fun earlyPayloadCannotConsumeFutureOccurrence() {
        val original = scheduled("pack")
        assertFalse(scheduler.consume(payload("pack")))
        assertSame(original, scheduled("pack"))
    }
    @Test fun activityStartRecoveryReassertsSameOverdueOccurrenceRatherThanTomorrow() {
        val before = payload("pack")
        delayed(); scheduler.sync(AlarmSync.RESTORE_PENDING)
        assertEquals(before.getStringExtra(ReminderScheduler.TOKEN), token("pack"))
        assertEquals(before.getLongExtra(ReminderScheduler.TRIGGER, -1), trigger("pack"))
        assertTrue(scheduler.consume(before))
        assertFourUnique()
    }
    @Test fun missingPendingIntentIsRecoveredFromPersistedOccurrenceEvenWhenOverdue() {
        val before = payload("pack")
        val lost = requireNotNull(scheduled("pack").operation)
        alarm.cancel(lost); lost.cancel()
        delayed(); ReminderScheduler(app, { current }, { currentZone }).sync()
        assertEquals(before.getStringExtra(ReminderScheduler.TOKEN), token("pack"))
        assertEquals(before.getLongExtra(ReminderScheduler.TRIGGER, -1), trigger("pack"))
        assertFourUnique()
    }
    @Test fun newSchedulerInstanceKeepsExistingDelayedAlarmAndLedger() {
        val before = scheduled("pack")
        delayed(); ReminderScheduler(app, { current }, { currentZone }).sync()
        assertSame(before, scheduled("pack"))
        assertFourUnique()
    }
    @Test fun explicitRebootRebuildChoosesFutureTimesAndRejectsOldDelivery() {
        val old = payload("pack")
        delayed(); scheduler.sync(AlarmSync.REBUILD)
        assertEquals(Instant.parse("2026-11-09T07:00:00Z").toEpochMilli(), trigger("pack"))
        assertNotEquals(old.getStringExtra(ReminderScheduler.TOKEN), token("pack"))
        assertFalse(scheduler.consume(old))
        scheduler.sync(AlarmSync.REBUILD)
        assertFourUnique()
    }
    @Test fun zoneChangeReconcilesFutureLocalTimeWithoutDuplicating() {
        val old = payload("pack")
        delayed(); currentZone = ZoneId.of("Asia/Kolkata"); scheduler.sync()
        assertEquals(Instant.parse("2026-11-09T01:30:00Z").toEpochMilli(), trigger("pack"))
        assertFalse(scheduler.consume(old))
        assertFourUnique()
    }
    @Test fun clockRollbackCannotReuseOldPayloadEvenWhenTriggerTimestampMatches() {
        val old = payload("pack")
        delayed(); assertTrue(scheduler.consume(old))
        current = Instant.parse("2026-11-08T06:59:00Z"); scheduler.sync(AlarmSync.REBUILD)
        assertEquals(old.getLongExtra(ReminderScheduler.TRIGGER, -1), trigger("pack"))
        assertNotEquals(old.getStringExtra(ReminderScheduler.TOKEN), token("pack"))
        delayed(); assertFalse(scheduler.consume(old)); assertTrue(scheduler.consume(payload("pack")))
        assertFourUnique()
    }
    @Test fun upgradingLegacyStateReplacesAlarmButPreservesOutcomesRoundsAndPreferences() {
        val date = LocalDate.of(2026, 11, 8)
        store.setEntry(date, "wake", Entry(Outcome.LATE, LocalTime.of(6, 20))); store.setRounds(date, 3)
        Preferences(app).put("alarm_v2.pack", null)
        val legacyIntent = Intent(app, ReminderReceiver::class.java).setAction(ReminderScheduler.ACTION).putExtra("routine_id", "pack")
        val legacy = PendingIntent.getBroadcast(app, 1002, legacyIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, current.plusSeconds(60).toEpochMilli(), legacy)
        scheduler.sync(AlarmSync.REBUILD)
        assertNotNull(token("pack")); assertFalse(scheduler.consume(legacyIntent))
        val fresh = app.rhythmStore()
        assertEquals(Entry(Outcome.LATE, LocalTime.of(6, 20)), fresh.entry(date, "wake"))
        assertEquals(3, fresh.rounds(date)); assertTrue(fresh.remindersEnabled)
        assertFourUnique()
    }
    @Test fun corruptSchedulingRecordRepairsOnlyAlarmMetadata() {
        val date = LocalDate.of(2026, 11, 8)
        store.setRounds(date, 2)
        Preferences(app).put("alarm_v2.pack", "not-an-occurrence")
        scheduler.sync()
        assertNotNull(token("pack")); assertEquals(2, store.rounds(date))
        assertFourUnique()
    }
    @Test fun primaryActivityCutoverAndResumeRetainsMatchingLegacyDelayedWakeOccurrence() {
        val realNow = Instant.now()
        val due = realNow.truncatedTo(ChronoUnit.MINUTES).minusSeconds(60)
        currentZone = ZoneId.systemDefault(); current = due.minusSeconds(60)
        store.setSchedule("wake", Schedule(due.atZone(currentZone).toLocalTime(), true)); scheduler.sync(AlarmSync.REBUILD)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val identity=FlexiblePlanner.identity("wake",due.atZone(currentZone).toLocalDate())
        val bridge=LiveReminders(app)
        val before=bridge.state().pending.getValue(identity)
        assertEquals(due.toEpochMilli(),before.trigger.toEpochMilli())
        controller.pause().resume(); shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(before,bridge.state().pending.getValue(identity))
        assertTrue(LiveReminders.migrated(app))
        controller.pause().stop().destroy()
    }
}
