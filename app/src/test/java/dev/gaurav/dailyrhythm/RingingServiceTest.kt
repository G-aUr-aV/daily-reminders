package dev.gaurav.dailyrhythm

import android.app.Application
import android.app.Service
import android.content.*
import android.os.Looper
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ServiceController
import java.time.*
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class RingingServiceTest {
    companion object { lateinit var supplied:LiveReminders; var starts=0;var closes=0;var fail=false;var end:(()->Unit)?=null }
    class ProbeService:RingingService() {
        override fun reminders()=supplied
        override fun output(problem:(String)->Unit, ended:()->Unit):AudioOutput {
            assertNotNull(Shadows.shadowOf(this).lastForegroundNotification)
            end=ended
            return object:AudioOutput {
                override fun play(sound:String,loop:Boolean) {starts++;if(fail){problem("Focus rejected");ended()}}
                override fun close(){closes++}
            }
        }
    }
    private class Platform:LivePlatform {
        var exact=true;var denied:String?=null
        override fun exactAllowed()=exact
        override fun blocked(item:String)=denied
        override fun exists(alert:LiveAlert)=true
        override fun arm(alert:LiveAlert){}
        override fun cancel(alert:LiveAlert){}
        override fun show(alert:LiveAlert,title:String,late:Boolean){}
        override fun ring(alert:LiveAlert,title:String){}
        override fun dismiss(id:String){RingingService.cancel(id)}
    }
    private lateinit var app:Application
    private lateinit var kv:Preferences
    private lateinit var controller:ServiceController<ProbeService>
    private val platform=Platform()
    private var now=Instant.parse("2026-11-09T06:59:00Z")
    @Before fun setup(){
        app=RuntimeEnvironment.getApplication();app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).edit().clear().commit()
        kv=Preferences(app);RhythmStore(kv).remindersEnabled=true
        supplied=LiveReminders(app,{now},{ZoneOffset.UTC},platform);starts=0;closes=0;fail=false;end=null
        controller=Robolectric.buildService(ProbeService::class.java).create()
    }
    @After fun cleanup(){controller.destroy();Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private fun alert(item:String="pack",sound:String="default"):LiveAlert {
        supplied.configure(item,GentleConfig(true,true,true,sound))
        val a=supplied.state().pending.values.first {FlexiblePlanner.parts(it.id).first==item}
        now=a.trigger.plusSeconds(1);supplied.handle(AndroidLivePlatform.intent(app,a,"fire"));return a
    }
    private fun start(a:LiveAlert,token:String=a.token):Int=controller.get().onStartCommand(Intent(app,ProbeService::class.java).putExtra("occurrence",a.id).putExtra("token",token),0,1)
    @Test fun privateServiceIsNonStickyForegroundBeforeAudioAndTokenValidated(){val a=alert();assertEquals(Service.START_NOT_STICKY,start(a,"forged"));assertEquals(0,starts);assertEquals(Service.START_NOT_STICKY,start(a));assertEquals(1,starts);assertNotNull(RingingService.sessionToken(a))}
    @Test fun notificationStopSilencesAlarmAndKeepsTheOutcomeUnreported() {
        val a = alert()
        start(a)
        val notification = requireNotNull(Shadows.shadowOf(controller.get()).lastForegroundNotification)
        assertEquals(listOf("Stop", "Snooze 5m", "Done"), notification.actions.map { it.title.toString() })
        val publicVersion = requireNotNull(notification.publicVersion)
        assertEquals("Reminders alarm", publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
        val stop = publicVersion.actions.single()
        assertEquals("Stop", stop.title.toString())
        assertTrue(stop.actionIntent.isImmutable)
        val intent = Shadows.shadowOf(stop.actionIntent).savedIntent
        supplied.handle(intent)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, closes)
        assertFalse(RingingService.contains(a.token))
        assertEquals(Entry(), FlexiblePlanner(kv) { a.day }.entry(a.id))
        assertTrue(LiveReminders(app).state().events.any { it.id == a.id && it.status == "DISMISSED" })
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun alarmPanelShowsDoneWithTheReminderAndHidesItWhenPrivate() {
        val alert = alert()
        start(alert)
        val snapshot = requireNotNull(RingingService.snapshot())
        val panel = AlarmPanel(app, snapshot.sessionToken, supplied)
        assertTrue(panel.refresh(snapshot, private = true))
        assertEquals("Reminders alarm", panel.display?.title)
        assertFalse(requireNotNull(panel.display).canComplete)
        assertTrue(panel.refresh(snapshot, private = false))
        assertTrue(requireNotNull(panel.display).canComplete)
        assertNotEquals("Reminders alarm", panel.display?.title)
        panel.perform("done")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Entry(Outcome.DONE, loggedAt = now), FlexiblePlanner(kv) { alert.day }.entry(alert.id))
        assertFalse(RingingService.contains(alert.token))
    }
    @Test fun fixedDeadlineDoesNotExtendForConcurrentAlertAndRecordsBothUnanswered(){
        val a=alert();start(a)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(4,TimeUnit.MINUTES)
        val b=alert("depart");start(b);assertEquals(1,starts)
        assertTrue(supplied.state().events.any {it.id==b.id && it.status=="RING_QUEUED"})
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.MINUTES)
        assertFalse(RingingService.contains(a.token));assertFalse(RingingService.contains(b.token));assertEquals(1,closes)
        for(x in listOf(a,b)){assertTrue(supplied.state().events.any {it.id==x.id && it.status=="UNANSWERED"});assertEquals(Entry(),FlexiblePlanner(kv){a.day}.entry(x.id))}
    }
    @Test fun focusOrMediaFailureStopsAllWithoutResurrection(){val a=alert();fail=true;start(a);assertTrue(supplied.state().active.isEmpty());assertFalse(RingingService.contains(a.token));assertEquals(1,closes);start(a);assertEquals(1,starts)}
    @Test fun staleSessionActionCannotSnoozeOrComplete(){val a=alert();start(a);supplied.handle(AndroidLivePlatform.intent(app,a,"snooze5").putExtra("ring_session","old"));assertEquals(a,supplied.state().active[a.id]);assertFalse(supplied.state().pending[a.id]?.snooze==true);assertEquals(Entry(),FlexiblePlanner(kv){a.day}.entry(a.id))}
    @Test fun stopOnlyCurrentAlertAndNextQueuedSharesSessionDeadline(){val a=alert();start(a);val b=alert("depart");start(b);val session=RingingService.sessionToken(a);supplied.action(a.id,"stop");Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,starts);assertFalse(a.id in supplied.state().active);assertEquals(session,RingingService.sessionToken(b));assertEquals(Entry(),FlexiblePlanner(kv){a.day}.entry(a.id))}
    @Test fun snoozeIsOccurrenceAndSessionScoped(){val a=alert();start(a);supplied.action(a.id,"snooze5");Shadows.shadowOf(Looper.getMainLooper()).idle();val next=supplied.state().pending.getValue(a.id);assertTrue(next.snooze);assertTrue(next.alarmClock);assertNotEquals(a.token,next.token);assertFalse(RingingService.contains(a.token));supplied.handle(AndroidLivePlatform.intent(app,a,"snooze10").putExtra("ring_session","stale"));assertEquals(Entry(),FlexiblePlanner(kv){a.day}.entry(a.id))}
    @Test fun exactRevocationStopsAndDoesNotReportRoutineFailure(){val a=alert();start(a);platform.exact=false;Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.SECONDS);assertFalse(RingingService.contains(a.token));assertFalse(a.id in supplied.state().active);assertEquals(Entry(),FlexiblePlanner(kv){a.day}.entry(a.id))}
    @Test fun affectedSkipCancelsOnlyItsSessionMember(){val a=alert();start(a);val b=alert("depart");start(b);FlexiblePlanner(kv){a.day}.skip(b.id);supplied.sync();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(RingingService.contains(a.token));assertFalse(RingingService.contains(b.token));assertEquals(1,starts)}
    @Test fun restartAndRebuildNeverResumePlayback(){val a=alert();supplied.recoverInterruptedRinging();assertFalse(a.id in supplied.state().active);assertEquals(0,starts);start(a);assertEquals(0,starts);supplied.sync(AlarmSync.REBUILD);assertEquals(0,starts)}
    @Test fun serviceDestroyAndFocusLossReleaseAll(){val a=alert();start(a);end!!.invoke();assertEquals(1,closes);assertFalse(RingingService.contains(a.token));assertTrue(supplied.state().active.isEmpty())}
    @Test fun revisionChangedBetweenReceiverAndServiceCannotPlay(){val a=alert();FlexiblePlanner(kv){a.day}.move(a.id,a.day.plusDays(1),LocalTime.NOON);start(a);assertEquals(0,starts);assertFalse(a.id in supplied.state().active)}
    @Test fun notificationRevocationFailsClosedAtServiceRecheck(){val a=alert();start(a);platform.denied="Notifications blocked";Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.SECONDS);assertFalse(RingingService.contains(a.token));assertEquals(1,closes);assertEquals(Entry(),FlexiblePlanner(kv){a.day}.entry(a.id))}
    @Test fun officeCancellationStopsOnlyLinkedSessionAndPersonalQueuedAlertSurvives(){val a=alert();start(a);val b=alert("bed");start(b);FlexiblePlanner(kv){a.day}.changeOffice(a.day,a.day,OfficeChoice.OFF,"Leave");supplied.sync();Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(RingingService.contains(a.token));assertTrue(RingingService.contains(b.token));assertEquals(2,starts)}
    @Test fun explicitVibrationAndWakelockReleasedOnStop(){
        val a=alert();supplied.configure("pack",GentleConfig(true,true,true,vibrate=true))
        val newer=supplied.state().pending.values.first {FlexiblePlanner.parts(it.id).first=="pack"};now=newer.trigger.plusSeconds(1);supplied.handle(AndroidLivePlatform.intent(app,newer,"fire"));start(newer)
        val lock=org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock();assertNotNull(lock);assertTrue(lock.isHeld)
        @Suppress("DEPRECATION") val v=app.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        assertTrue(Shadows.shadowOf(v).isVibrating)
        supplied.action(newer.id,"stop");Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(lock.isHeld);assertFalse(Shadows.shadowOf(v).isVibrating)
    }

    @Test fun postedCancellationCannotStopNewTokenForSameOccurrence(){
        val old=alert();start(old);RingingService.cancel(old.id)
        val replacement=old.copy(token="replacement-token")
        val state=supplied.state();LiveStore(kv).save(state.copy(active=state.active+(old.id to replacement)))
        start(replacement);Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(RingingService.contains(replacement.token));assertFalse(RingingService.contains(old.token));assertEquals(2,starts)
    }

    @Test fun staleAudioCallbackCannotStopNextQueuedAlert(){
        val a=alert();start(a);val oldEnd=end!!;val b=alert("depart");start(b)
        supplied.action(a.id,"stop");Shadows.shadowOf(Looper.getMainLooper()).idle();oldEnd.invoke()
        assertTrue(RingingService.contains(b.token));assertFalse(RingingService.contains(a.token));assertEquals(2,starts)
    }

    private fun imported():String {
        val id=java.util.UUID.randomUUID().toString();val root=PrivateSounds(app).directory()
        app.resources.openRawResource(R.raw.alarm_fallback).use {input->java.io.File(root,"$id.wav").outputStream().use {input.copyTo(it)}}
        return id
    }
    @Test fun activeAndQueuedPrivateSoundsRemainPinnedUntilSessionCleanup(){
        val one=imported();val two=imported();val a=alert("pack","private:$one");start(a);val b=alert("depart","private:$two");start(b)
        val state=supplied.state();LiveStore(kv).save(state.copy(configs=state.configs.mapValues {it.value.copy(sound="default")}))
        val library=SoundLibrary(app);assertThrows(IllegalStateException::class.java){library.remove(one)};assertThrows(IllegalStateException::class.java){library.remove(two)}
        end!!.invoke();assertFalse(PrivateSounds.pinned(one));assertFalse(PrivateSounds.pinned(two));library.remove(one);library.remove(two)
        assertEquals(Entry(),FlexiblePlanner(kv){a.day}.entry(a.id));assertEquals(Entry(),FlexiblePlanner(kv){b.day}.entry(b.id))
    }
    @Test fun sessionScreenActionsRequireCapturedSessionAndOccurrenceTokens(){
        val a=alert();start(a);val view=RingingService.snapshot()!!
        supplied.sessionAction(a.id,"stale",view.sessionToken,"stop");supplied.sessionAction(a.id,a.token,"stale","snooze5")
        assertEquals(a,supplied.state().active[a.id]);assertFalse(supplied.state().pending[a.id]?.snooze==true)
        supplied.sessionAction(a.id,a.token,view.sessionToken,"stop");Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(a.id in supplied.state().active);assertEquals(Entry(),FlexiblePlanner(kv){a.day}.entry(a.id))
    }
    @Test fun cosmeticRenameDoesNotResetLiveSessionOrItsDeadline(){
        val one=imported();val a=alert("pack","private:$one");start(a);val b=alert("depart");start(b)
        val before=RingingService.snapshot()!!;val raw=kv.get(LiveStore.KEY);SoundLibrary(app).rename(one,"Ocean at night")
        val after=RingingService.snapshot()!!;assertEquals(before,after);assertEquals(raw,kv.get(LiveStore.KEY));assertEquals(1,starts)
        val rows=AlarmPresentation.rows(after,supplied.state()){it.id};assertEquals(listOf("Current alert","Queued alert"),rows.map {it.status})
    }

    @Test fun fullScreenPendingIntentIsPrivateImmutableAndCurrentSessionBound() {
        RingingService.blocked(app)
        val a = alert()
        start(a)
        val notification = Shadows.shadowOf(controller.get()).lastForegroundNotification
        val launch = requireNotNull(notification.fullScreenIntent)
        assertTrue(launch.isImmutable)
        val intent = Shadows.shadowOf(launch).savedIntent
        assertEquals(AlarmActivity::class.java.name, intent.component?.className)
        assertEquals(RingingService.sessionToken(a), intent.data?.lastPathSegment)
        supplied.action(a.id, "stop")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Robolectric.buildActivity(AlarmActivity::class.java, intent).setup().use { activity ->
            assertTrue(activity.get().isFinishing)
        }
    }

    @Test fun deletingRingingReminderCancelsSnoozesAndStopsAnActiveSession() {
        val a = alert()
        start(a)
        val snoozing = alert("depart")
        start(snoozing)
        supplied.action(snoozing.id, "snooze5")
        val planner = FlexiblePlanner(kv) { a.day }
        planner.report(a.id, Entry(Outcome.DONE, LocalTime.NOON))
        planner.delete(FlexiblePlanner.parts(a.id).first)
        planner.delete(FlexiblePlanner.parts(snoozing.id).first)
        supplied.sync()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(supplied.state().active.isEmpty())
        assertTrue(supplied.state().pending.values.none { FlexiblePlanner.parts(it.id).first in setOf("pack", "depart") })
        assertEquals(Outcome.DONE, planner.entry(a.id).outcome)
    }

    @Test fun notificationOnlyAlarmKeepsControlsAndDoesNotRequestFullScreen() {
        RingingService.blocked(app)
        val a = alert()
        val item = FlexiblePlanner.parts(a.id).first
        val state = supplied.state()
        LiveStore(kv).save(state.copy(configs = state.configs + (item to supplied.config(item).copy(fullScreen = false))))
        start(a)
        val notification = Shadows.shadowOf(controller.get()).lastForegroundNotification
        assertNull(notification.fullScreenIntent)
        assertEquals("Stop", notification.actions.first().title.toString())
        assertFalse(RingingService.usesFullScreen(a))
        assertEquals(1, starts)
    }

}
