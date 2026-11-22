package dev.gaurav.dailyrhythm

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.annotation.Config
import java.time.*

@android.annotation.TargetApi(33)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class LivePlatformTest {
    private lateinit var app:Application
    @Before fun setup(){app=RuntimeEnvironment.getApplication();app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).edit().clear().commit();shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)}
    private fun alert(precise:Boolean)=LiveAlert(FlexiblePlanner.identity("wake",LocalDate.now()),LocalDate.now(),Instant.now().plusSeconds(60),"token-$precise","signature",precise)
    @Test fun preciseActuallyUsesExactAllowWhileIdleAndNotAlarmClock(){
        ShadowAlarmManager.setCanScheduleExactAlarms(true);val platform=AndroidLivePlatform(app);val a=alert(true);platform.arm(a)
        val scheduled=shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        assertEquals(0L,scheduled.windowLengthMs);assertTrue(scheduled.isAllowWhileIdle);assertNull(scheduled.alarmClockInfo)
        assertTrue(requireNotNull(scheduled.operation).isImmutable)
    }
    @Test fun ringingRegistersAnAlarmClockIncludingAfterSnooze() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val platform = AndroidLivePlatform(app)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val ringing = alert(true).copy(alarmClock = true)
        for (request in listOf(ringing, ringing.copy(trigger = ringing.trigger.plusSeconds(300), snooze = true))) {
            platform.arm(request)
            val scheduled = requireNotNull(alarms.scheduledAlarms.single().alarmClockInfo)
            assertEquals(request.trigger.toEpochMilli(), scheduled.triggerTime)
            assertTrue(scheduled.showIntent.isImmutable)
        }
    }
    @Test fun approximateUsesInexactAllowWhileIdle(){val p=AndroidLivePlatform(app);p.arm(alert(false));val a=shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single();assertTrue(a.isAllowWhileIdle);assertNotEquals(0L,a.windowLengthMs)}
    @Test fun deniedNativeExactDoesNotBecomeApproximate(){ShadowAlarmManager.setCanScheduleExactAlarms(false);assertThrows(IllegalStateException::class.java){AndroidLivePlatform(app).arm(alert(true))};assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())}
    @Test fun nativeReceiverRejectsAnOldDoneActionAfterSnoozing(){
        val zone=ZoneId.systemDefault();val real=Instant.now();val due=real.minusSeconds(10);val kv=Preferences(app);val old=RhythmStore(kv)
        old.remindersEnabled=true;old.setSchedule("wake",Schedule(due.atZone(zone).toLocalTime(),true))
        val bridge=LiveReminders(app,{real.minusSeconds(60)},{zone});bridge.sync()
        val id=FlexiblePlanner.identity("wake",due.atZone(zone).toLocalDate());val a=bridge.state().pending.getValue(id)
        ReminderReceiver().onReceive(app,AndroidLivePlatform.intent(app,a,"fire"))
        val manager=app.getSystemService(NotificationManager::class.java);val n=shadowOf(manager).allNotifications.single()
        assertEquals(Notification.VISIBILITY_SECRET,n.visibility)
        assertEquals(listOf("Dismiss", "Snooze 5m", "Done"), n.actions.map { it.title.toString() })
        val open=shadowOf(n.contentIntent).savedIntent;assertEquals(MainActivity::class.java.name,open.component?.className);assertEquals(id,open.getStringExtra(AndroidLivePlatform.OPEN))
        val snooze=shadowOf(n.actions[1].actionIntent).savedIntent
        ReminderReceiver().onReceive(app,snooze)
        val child=LiveReminders(app).state().pending.getValue(id);assertTrue(child.snooze);assertNotEquals(a.token,child.token)
        assertEquals(Entry(),FlexiblePlanner(kv){LocalDate.now()}.entry(id))
        ReminderReceiver().onReceive(app,shadowOf(n.actions[2].actionIntent).savedIntent)
        assertEquals(child,LiveReminders(app).state().pending.getValue(id))
        assertEquals(Entry(),FlexiblePlanner(kv){LocalDate.now()}.entry(id))
    }
    @Test fun newChannelSoundSettingsRemainStableAndUserOwned(){
        val p=AndroidLivePlatform(app);p.ensure("wake");val manager=app.getSystemService(NotificationManager::class.java)
        val first=manager.getNotificationChannel(AndroidLivePlatform.channel("wake"));val uri=first.sound
        p.ensure("wake");val second=manager.getNotificationChannel(AndroidLivePlatform.channel("wake"))
        assertEquals(first.id,second.id);assertEquals(uri,second.sound);assertEquals(Notification.VISIBILITY_SECRET,second.lockscreenVisibility)
    }
    @Test fun perItemMigrationDoesNotBypassMutedLegacyChannel(){
        val manager=app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(ReminderScheduler.CHANNEL,"Legacy",NotificationManager.IMPORTANCE_NONE))
        val platform=AndroidLivePlatform(app);platform.ensure("wake")
        assertEquals(NotificationManager.IMPORTANCE_NONE,manager.getNotificationChannel(AndroidLivePlatform.channel("wake")).importance)
        assertNotNull(platform.blocked("wake"))
    }
    @Test fun migrationPlanAndMarkerAreOneBatchOrNeither(){
        class Batch:AtomicKeyValue {
            val data=mutableMapOf<String,String>();var fail=true;var batches=0
            override fun get(key:String)=data[key]
            override fun put(key:String,value:String?){if(value==null)data.remove(key)else data[key]=value}
            override fun putAll(values:Map<String,String?>){batches++;check(!fail);values.forEach{(k,v)->put(k,v)}}
        }
        val kv=Batch();kv.put("rounds.2026-11-08","2")
        val p=FlexiblePlanner(kv,persistMigration=false,today={LocalDate.of(2026,11,9)})
        assertNull(kv.get(FlexiblePlanner.KEY))
        assertThrows(IllegalStateException::class.java){LiveStore(kv).save(LiveState(),mapOf(FlexiblePlanner.KEY to FlexCodec.encode(p.state)))}
        assertNull(kv.get(LiveStore.KEY));assertNull(kv.get(FlexiblePlanner.KEY));assertEquals("2",kv.get("rounds.2026-11-08"))
        kv.fail=false;LiveStore(kv).save(LiveState(),mapOf(FlexiblePlanner.KEY to FlexCodec.encode(p.state)))
        assertNotNull(kv.get(LiveStore.KEY));assertNotNull(kv.get(FlexiblePlanner.KEY));assertEquals(2,kv.batches)
    }
    @Test fun ringingControlsChannelIsStableAndNeverUnmutesUserChoice(){
        val manager=app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(RingingService.CHANNEL,"Muted",NotificationManager.IMPORTANCE_NONE))
        assertNotNull(RingingService.blocked(app));assertEquals(NotificationManager.IMPORTANCE_NONE,manager.getNotificationChannel(RingingService.CHANNEL).importance)
        assertNotNull(RingingService.blocked(app));assertEquals(NotificationManager.IMPORTANCE_NONE,manager.getNotificationChannel(RingingService.CHANNEL).importance)
    }

}
