package dev.gaurav.dailyrhythm

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.*

/** Clean private-storage simulation, not a physical APK install or OEM alarm test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class FreshInstallReviewTest {
    private lateinit var app:Application
    private val day=LocalDate.of(2026,11,10)
    @Before fun cleanPrivateState() {
        app=RuntimeEnvironment.getApplication()
        assertTrue(app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).edit().clear().commit())
    }
    @Test fun freshInitializationRetainsSafeDefaultsWithoutFabricatedHistoryOrAlarms() {
        val kv=Preferences(app)
        kv.put("test.unrelated.preference", "saved")
        val bridge=LiveReminders(app,{day.atTime(5,0).toInstant(ZoneOffset.UTC)},{ZoneOffset.UTC})
        bridge.sync()
        assertFalse(RhythmStore(kv).remindersEnabled)
        val state=bridge.state()
        assertTrue(state.pending.isEmpty());assertTrue(state.active.isEmpty())
        assertTrue(state.closed.isEmpty())
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        val planner=FlexiblePlanner(kv){day}
        assertTrue(planner.state.reports.isEmpty());assertTrue(planner.state.rituals.isEmpty())
        assertTrue(planner.state.items.isEmpty())
        assertTrue(planner.occurrences(day).isEmpty())
        assertNull(CalendarSync(app).target())
        assertTrue((0L..6L).none { planner.office(day.plusDays(it)) })
        assertEquals(0,RhythmStore(kv).rounds(day))
        assertTrue(state.configs.isEmpty())
    }
    @Test fun turningRemindersOnFromCleanInstallArmsOnlyTheNewAlarm() {
        val kv = Preferences(app)
        val bridge = LiveReminders(app, { day.atTime(5, 0).toInstant(ZoneOffset.UTC) }, { ZoneOffset.UTC })
        bridge.initialize()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        RhythmStore(kv).remindersEnabled = true
        bridge.sync()
        assertTrue(bridge.state().pending.isEmpty())

        val created = bridge.createPlanned(
            PlanVersion(day, "My alarm", time = LocalTime.of(5, 2), rule = RepeatRule(RepeatKind.ONCE, oneDate = day)),
            GentleConfig(enabled = true, precise = true, ringing = true)
        )
        assertNull(created.warning)
        val alert = bridge.state().pending.values.single()
        assertEquals(FlexiblePlanner.identity(created.id, day), alert.id)
        assertTrue(alert.alarmClock)
        assertNotNull(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single().alarmClockInfo)
    }
    @Test fun freshInitializationIsStableAcrossNewBridgeAndPlannerInstances() {
        val kv=Preferences(app)
        val first=LiveReminders(app,{day.atTime(5,0).toInstant(ZoneOffset.UTC)},{ZoneOffset.UTC}).initialize()
        val plan=kv.get(FlexiblePlanner.KEY)
        val second=LiveReminders(app,{day.atTime(5,0).toInstant(ZoneOffset.UTC)},{ZoneOffset.UTC}).initialize()
        assertEquals(first,second);assertEquals(plan,kv.get(FlexiblePlanner.KEY))
        assertTrue(FlexiblePlanner(kv){day}.state.reports.isEmpty())
    }
}
