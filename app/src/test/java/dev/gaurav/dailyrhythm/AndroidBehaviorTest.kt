package dev.gaurav.dailyrhythm

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** JVM Android simulation, not evidence of device delivery or OS lifecycle behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidBehaviorTest {
    private lateinit var app: Application
    private lateinit var store: RhythmStore
    private lateinit var scheduler: ReminderScheduler
    private lateinit var alarm: AlarmManager
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("rhythm_v1", Context.MODE_PRIVATE).edit().clear().commit()
        store = app.rhythmStore()
        scheduler = ReminderScheduler(app)
        alarm = app.getSystemService(AlarmManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        scheduler.ensureChannel()
    }
    @Test fun nativeActivityCreatesAndResumesWithoutEnablingReminders() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(controller.get().findViewById<android.view.View>(android.R.id.content))
        assertFalse(store.remindersEnabled)
        assertTrue(shadowOf(alarm).scheduledAlarms.isEmpty())
        controller.pause().stop().destroy()
    }
    @Test fun coldInstallSchedulesNothing() {
        scheduler.sync()
        assertTrue(shadowOf(alarm).scheduledAlarms.isEmpty())
    }
    @Test fun repeatedSyncKeepsFourTimedRoutinesWithoutDuplicates() {
        store.remindersEnabled = true
        scheduler.sync(); scheduler.sync(); scheduler.sync()
        val alarms = shadowOf(alarm).scheduledAlarms
        assertEquals(4, alarms.size)
        assertEquals(4, alarms.map { shadowOf(it.operation).savedIntent.getStringExtra("routine_id") }.distinct().size)
        assertTrue(alarms.all { it.type == AlarmManager.RTC_WAKEUP })
    }
    @Test fun unsettingAndDisablingCancelExistingRoutineAlarms() {
        store.remindersEnabled = true; scheduler.sync()
        store.setSchedule("wake", Schedule(null, true)); scheduler.sync()
        assertEquals(3, shadowOf(alarm).scheduledAlarms.size)
        store.setSchedule("pack", Schedule(java.time.LocalTime.of(7, 0), false)); scheduler.sync()
        assertEquals(2, shadowOf(alarm).scheduledAlarms.size)
        store.remindersEnabled = false; scheduler.sync()
        assertTrue(shadowOf(alarm).scheduledAlarms.isEmpty())
    }
    @Test fun testIsOneShotAndUsesIndependentStableIdentity() {
        assertNull(scheduler.availability())
        store.remindersEnabled = true
        scheduler.test(); scheduler.test()
        assertEquals(1, shadowOf(alarm).scheduledAlarms.size)
        assertTrue(store.remindersEnabled)
        val pending = requireNotNull(shadowOf(alarm).scheduledAlarms.single().operation)
        scheduler.cancelTest()
        assertEquals(ReminderScheduler.TEST, shadowOf(pending).savedIntent.getStringExtra("routine_id"))
        assertTrue(shadowOf(alarm).scheduledAlarms.isEmpty())
    }
    @Test fun masterOffCancelsThePendingTestAndRejectsStaleTestDelivery() {
        store.remindersEnabled = true; scheduler.test()
        assertEquals(1, shadowOf(alarm).scheduledAlarms.size)
        store.remindersEnabled = false; scheduler.sync()
        assertTrue(shadowOf(alarm).scheduledAlarms.isEmpty())
        ReminderReceiver().onReceive(app, Intent(app, ReminderReceiver::class.java).setAction(ReminderScheduler.ACTION).putExtra("routine_id", ReminderScheduler.TEST))
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
    @Test fun deniedNotificationsStillRescheduleDailyReminder() {
        val zone = java.time.ZoneId.systemDefault()
        val due = java.time.Instant.now().minusSeconds(2)
        store.remindersEnabled = true
        store.setSchedule("pack", Schedule(due.atZone(zone).toLocalTime(), true))
        ReminderScheduler(app, { due.minusSeconds(60) }, { zone }).sync()
        val pending = requireNotNull(shadowOf(alarm).scheduledAlarms.single {
            shadowOf(requireNotNull(it.operation)).savedIntent.getStringExtra("routine_id") == "pack"
        }.operation)
        val fired = Intent(shadowOf(pending).savedIntent)
        alarm.cancel(pending)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertNotNull(scheduler.availability())
        ReminderReceiver().onReceive(app, fired)
        assertEquals(4, shadowOf(alarm).scheduledAlarms.size)
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
    @Test(expected = IllegalStateException::class) fun deniedPermissionPreventsTestQueueing() {
        store.remindersEnabled = true
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        scheduler.test()
    }
    @Test fun disabledReceiverDoesNotReviveScheduleOrPost() {
        ReminderReceiver().onReceive(app, Intent(app, ReminderReceiver::class.java).setAction(ReminderScheduler.ACTION).putExtra("routine_id", "wake"))
        assertTrue(shadowOf(alarm).scheduledAlarms.isEmpty())
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
    @Test fun testReceiverPostsOnceWithoutRecurringSchedule() {
        store.remindersEnabled = true
        ReminderReceiver().onReceive(app, Intent(app, ReminderReceiver::class.java).setAction(ReminderScheduler.ACTION).putExtra("routine_id", ReminderScheduler.TEST))
        assertEquals(1, shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.size)
        assertTrue(shadowOf(alarm).scheduledAlarms.isEmpty())
    }
    @Test fun preferencesSurviveNewRepositoryAndPreserveLocalDateKeys() {
        val date = java.time.LocalDate.of(2026, 11, 8)
        store.setRounds(date, 3)
        store.setEntry(date, "wake", Entry(Outcome.LATE, java.time.LocalTime.of(6, 21)))
        val fresh = app.rhythmStore()
        assertEquals(3, fresh.rounds(date))
        assertEquals(Entry(Outcome.LATE, java.time.LocalTime.of(6, 21)), fresh.entry(date, "wake"))
        assertEquals(0, fresh.rounds(date.plusDays(1)))
    }
    @Test fun manifestKeepsReceiversPrivateAndOnlyAddsReviewedExactAccess() {
        val pm = app.packageManager
        for (type in listOf(ReminderReceiver::class.java, RestoreReceiver::class.java)) {
            assertFalse(pm.getReceiverInfo(android.content.ComponentName(app, type), 0).exported)
        }
        @Suppress("DEPRECATION")
        val permissions = pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertFalse(permissions.contains("android.permission.INTERNET"))
        assertTrue(permissions.contains("android.permission.SCHEDULE_EXACT_ALARM"))
        assertFalse(permissions.contains("android.permission.USE_EXACT_ALARM"))
    }
    @Test fun disabledChannelBlocksTestWithoutEnablingDailyReminders() {
        val manager = app.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(ReminderScheduler.CHANNEL)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)
        assertNotNull(scheduler.availability())
        assertFalse(store.remindersEnabled)
    }
    @Test(expected = IllegalStateException::class) fun testCannotCreateAlarmBeforeMasterOptIn() {
        scheduler.test()
    }
    @Test @Config(sdk = [26]) fun androidEightUsesChannelWithoutRuntimeNotificationPermission() {
        store.remindersEnabled = true
        assertNull(scheduler.availability())
        scheduler.test()
        assertEquals(1, shadowOf(alarm).scheduledAlarms.size)
    }
    @Test fun systemRestoreRebuildsOnlyEnabledTimedSchedules() {
        store.remindersEnabled = true
        RestoreReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(4, shadowOf(alarm).scheduledAlarms.size)
        RestoreReceiver().onReceive(app, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        RestoreReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
        RestoreReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(4, shadowOf(alarm).scheduledAlarms.size)
    }
}
