package dev.gaurav.dailyrhythm

import android.app.Application
import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class LiveRemindersTest {
    private lateinit var app:Application
    private lateinit var kv:Preferences
    private lateinit var bridge:LiveReminders
    private var clock=Instant.parse("2026-11-09T06:59:00Z")
    private var zone:ZoneId=ZoneOffset.UTC
    private val fake=Fake()
    private class Fake:LivePlatform {
        var exact=true;var denied:String?=null;var failArm=false;var failPost=false
        val alarms=mutableMapOf<String,LiveAlert>();val cancelled=mutableListOf<LiveAlert>();val posts=mutableListOf<LiveAlert>();val dismissed=mutableListOf<String>()
        override fun exactAllowed()=exact
        override fun blocked(item:String)=denied
        override fun exists(alert:LiveAlert)=alarms[alert.id]?.token==alert.token
        override fun arm(alert:LiveAlert){if(failArm)throw SecurityException("Rejected scheduling");alarms[alert.id]=alert}
        override fun cancel(alert:LiveAlert){cancelled+=alert;if(alarms[alert.id]?.token==alert.token)alarms.remove(alert.id)}
        override fun show(alert:LiveAlert,title:String,late:Boolean){if(failPost)throw SecurityException("Rejected post");posts+=alert}
        override fun dismiss(id:String){dismissed+=id}
    }
    @Before fun setup(){app=RuntimeEnvironment.getApplication();app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).edit().clear().commit();kv=Preferences(app);RhythmStore(kv).remindersEnabled=true;bridge=LiveReminders(app,{clock},{zone},fake)}
    private fun planner()=FlexiblePlanner(kv){clock.atZone(zone).toLocalDate()}
    private fun id(item:String,date:LocalDate=LocalDate.of(2026,11,9))=FlexiblePlanner.identity(item,date)
    private fun pending(item:String)=bridge.state().pending.getValue(id(item))
    private fun fire(alert:LiveAlert){clock=alert.trigger.plusSeconds(1);bridge.handle(AndroidLivePlatform.intent(app,alert,"fire"))}
    private fun op(alert:LiveAlert,action:String)=AndroidLivePlatform.intent(app,alert,action)

    @Test fun additiveAtomicMigrationPreservesLegacySettingsLogsAndLedger(){
        val old=RhythmStore(kv);old.setRounds(LocalDate.of(2026,11,8),2);old.setEntry(LocalDate.of(2026,11,8),"wake",Entry(Outcome.LATE,LocalTime.of(6,12)));old.setSchedule("bed",Schedule(LocalTime.of(22,45),false));kv.put("alarm_v2.bed","old-ledger")
        val before=app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).all.toMap()
        bridge.sync();val state=bridge.state();val raw=kv.get(LiveStore.KEY)
        assertFalse(state.configs.getValue("bed").enabled);assertTrue(old.remindersEnabled)
        assertEquals(before,app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).all.filterKeys{it!=LiveStore.KEY && it!=FlexiblePlanner.KEY})
        bridge.initialize();assertEquals(raw,kv.get(LiveStore.KEY));assertEquals(2,old.rounds(LocalDate.of(2026,11,8)))
    }
    @Test fun alreadyLoggedUpcomingDatesDoNotStarveLaterRecurrence(){val p=planner();listOf(LocalDate.of(2026,11,9),LocalDate.of(2026,11,11),LocalDate.of(2026,11,13)).forEach {p.report(id("pack",it),Entry(Outcome.DONE))};bridge.sync();assertTrue(id("pack",LocalDate.of(2026,11,16)) in bridge.state().pending);assertFalse(id("pack") in bridge.state().pending)}
    @Test fun fractionalTimesRoundTripAtAlarmMillisecondResolutionWithoutRearming(){RhythmStore(kv).setSchedule("pack",Schedule(LocalTime.parse("07:00:00.123456789"),true));bridge.sync();val before=pending("pack");bridge.sync();assertEquals(before,pending("pack"));assertTrue(fake.cancelled.none{it.token==before.token})}
    @Test fun masterOffHasNoLiveAlarmsAndDoesNotBecomeEnabled(){RhythmStore(kv).remindersEnabled=false;bridge.sync();assertTrue(fake.alarms.isEmpty());assertFalse(RhythmStore(kv).remindersEnabled)}
    @Test fun officeDaysDriveActualGenericPendingOccurrences(){bridge.sync();assertTrue(id("pack") in bridge.state().pending);assertFalse(id("pack",LocalDate.of(2026,11,10)) in bridge.state().pending);assertTrue(id("pack",LocalDate.of(2026,11,11)) in bridge.state().pending)}
    @Test fun unchangedOverdueOccurrenceSurvivesOrdinaryResumeAndReload(){bridge.sync();val before=pending("pack");clock=before.trigger.plusSeconds(5);bridge.sync();val reloaded=LiveReminders(app,{clock},{zone},fake);reloaded.sync();assertEquals(before,reloaded.state().pending.getValue(before.id));assertTrue(fake.cancelled.none{it.token==before.token})}
    @Test fun unrelatedEditDoesNotReplaceDueToken(){bridge.sync();val before=pending("pack");clock=before.trigger.plusSeconds(5);val p=planner();p.edit("bed",p.state.items.getValue("bed").at(LocalDate.of(2026,11,9))!!.copy(time=LocalTime.of(22,30)));bridge.sync();assertEquals(before,pending("pack"));assertTrue(fake.cancelled.none{it.token==before.token})}
    @Test fun moveReplacesAffectedIdentityAndOldPayloadCannotDeliver(){bridge.sync();val old=pending("pack");planner().move(old.id,old.day.plusDays(1),LocalTime.of(8,0));bridge.sync();val moved=bridge.state().pending.getValue(old.id);assertNotEquals(old.token,moved.token);assertEquals(old.day.plusDays(1),moved.day);bridge.handle(op(old,"fire"));assertTrue(fake.posts.isEmpty());assertEquals(moved,bridge.state().pending.getValue(old.id))}
    @Test fun skipUndoCreatesNewTokenAndDoesNotReviveOldActions(){bridge.sync();val old=pending("pack");planner().skip(old.id);bridge.sync();assertFalse(old.id in bridge.state().pending);planner().undoItem(old.id);bridge.sync();assertNotEquals(old.token,pending("pack").token);fire(old);assertTrue(fake.posts.isEmpty())}
    @Test fun officeCancellationOnlyCancelsLinkedDateAndPreservesCompletedHistory(){bridge.sync();val p=planner();p.report(id("depart"),Entry(Outcome.DONE,LocalTime.of(7,20)));val bed=pending("bed");p.changeOffice(LocalDate.of(2026,11,9),LocalDate.of(2026,11,9),OfficeChoice.OFF,"Leave");bridge.sync();assertFalse(id("pack") in bridge.state().pending);assertEquals(bed,pending("bed"));assertEquals(Outcome.DONE,planner().entry(id("depart")).outcome)}
    @Test fun extraOfficeTuesdayAddsOnceWithoutReplayingPastMondayMorning(){bridge.sync();val p=planner();repeat(2){p.changeOffice(LocalDate.of(2026,11,10),LocalDate.of(2026,11,10),OfficeChoice.ON)};clock=Instant.parse("2026-11-09T12:00:00Z");bridge.sync();assertEquals(1,bridge.state().pending.keys.count{it==id("pack",LocalDate.of(2026,11,10))})}
    @Test fun newCustomRoutineCanAlertWithoutStaticCatalogMembership(){val p=planner();val custom=p.create(PlanVersion(LocalDate.of(2026,11,9),"Custom",time=LocalTime.of(7,10)));bridge.configure(custom,GentleConfig(true));val a=bridge.state().pending.getValue(id(custom));fire(a);assertEquals(a.id,fake.posts.single().id);assertEquals(Entry(),planner().entry(a.id))}
    @Test fun ritualAlertOpenDismissSnoozeNeverReportsReading(){bridge.sync();val p=planner();val item=p.create(PlanVersion(LocalDate.of(2026,11,9),"Read",kind=ItemKind.RITUAL,time=LocalTime.of(7,5),content=listOf("Local"),target=3));bridge.configure(item,GentleConfig(true));val a=bridge.state().pending.getValue(id(item));fire(a);bridge.handle(op(a,"snooze5"));assertEquals(0,planner().ritual(a.id).rounds);assertEquals(Entry(),planner().entry(a.id));bridge.handle(op(a,"dismiss"));assertEquals(0,planner().ritual(a.id).rounds)}
    @Test fun exactDeniedIsNotSilentlyApproximate(){bridge.sync();fake.exact=false;bridge.configure("pack",GentleConfig(true,true));assertFalse(id("pack") in bridge.state().pending);assertTrue(bridge.state().events.any{it.id==id("pack") && it.status=="BLOCKED" && it.detail.contains("Alarm not enabled")});assertEquals(Entry(),planner().entry(id("pack")))}
    @Test fun exactFallbackRequiresExplicitConfiguration(){fake.exact=false;bridge.configure("pack",GentleConfig(true,true));assertFalse(id("pack") in bridge.state().pending);bridge.configure("pack",GentleConfig(true,false));assertFalse(pending("pack").precise);assertEquals("QUEUED",bridge.state().events.last{it.id==id("pack")}.status)}
    @Test fun revokedAccessCancelsPendingWithoutRoutineFailure(){bridge.configure("pack",GentleConfig(true,true));val old=pending("pack");fake.exact=false;bridge.sync();assertFalse(old.id in bridge.state().pending);assertTrue(fake.cancelled.any{it.token==old.token});assertEquals(Entry(),planner().entry(old.id))}
    @Test fun exactGrantRebuildDoesNotReplayExpiredMorning(){fake.exact=false;bridge.configure("pack",GentleConfig(true,true));clock=Instant.parse("2026-11-09T07:01:00Z");fake.exact=true;bridge.sync(AlarmSync.REBUILD);assertFalse(id("pack") in bridge.state().pending);assertTrue(fake.posts.isEmpty())}
    @Test fun denialAtDeliveryFailsClosedAndLeavesHistoryUnlogged(){bridge.sync();val a=pending("pack");clock=a.trigger.plusSeconds(1);fake.denied="Channel blocked";bridge.handle(op(a,"fire"));assertTrue(fake.posts.isEmpty());assertFalse(a.id in bridge.state().pending);assertEquals(Entry(),planner().entry(a.id));assertEquals("BLOCKED",bridge.state().events.last().status)}
    @Test fun scheduleAndPostFailuresBecomeHealthNotOutcomes(){fake.failArm=true;bridge.sync();assertTrue(bridge.state().pending.isEmpty());assertTrue(bridge.state().events.any{it.status=="SCHEDULE_BLOCKED"});fake.failArm=false;bridge.sync();val a=pending("pack");fake.failPost=true;fire(a);assertTrue(bridge.state().events.any{it.id==a.id && it.status=="POST_BLOCKED"});assertEquals(Entry(),planner().entry(a.id))}
    @Test fun earlyDuplicateAndStaleDeliveriesDoNotAdvanceTwice(){bridge.sync();val a=pending("pack");bridge.handle(op(a,"fire"));assertTrue(fake.posts.isEmpty());fire(a);val count=fake.posts.size;bridge.handle(op(a,"fire"));assertEquals(count,fake.posts.size);assertFalse(a.id in bridge.state().pending)}
    @Test fun delayedDeliveryIsNotARecordedLateRoutine(){bridge.sync();val a=pending("pack");clock=a.trigger.plusSeconds(240);bridge.handle(op(a,"fire"));assertTrue(bridge.state().events.any{it.id==a.id && it.status=="DELIVERED_LATE"});assertEquals(Entry(),planner().entry(a.id))}
    @Test fun snooze5IsScopedAndItsDozeLimitationIsRecorded(){bridge.configure("pack",GentleConfig(true,true));val a=pending("pack");fire(a);val at=clock;bridge.handle(op(a,"snooze5"));val snoozed=bridge.state().pending.getValue(a.id);assertTrue(snoozed.snooze);assertTrue(snoozed.precise);assertEquals(at.plusSeconds(300),snoozed.trigger);assertNotEquals(a.token,snoozed.token);assertTrue(bridge.state().events.last().detail.contains("nine-minute"));bridge.handle(op(a,"snooze10"));assertEquals(snoozed,bridge.state().pending.getValue(a.id))}
    @Test fun snoozeCancellationOnMoveSkipArchiveAndModeOffDoesNotDeleteHistory(){for(change in 0..3){kv.putAll(mapOf(FlexiblePlanner.KEY to null, LiveStore.KEY to null));RhythmStore(kv).remindersEnabled=true;clock=Instant.parse("2026-11-09T06:59:00Z");bridge.sync();val a=pending("pack");fire(a);bridge.handle(op(a,"snooze5"));val child=bridge.state().pending.getValue(a.id);val p=planner();when(change){0->p.move(a.id,a.day.plusDays(1),LocalTime.NOON);1->p.skip(a.id);2->p.archive("pack");else->bridge.configure("pack",GentleConfig(false))};bridge.sync();assertNotEquals(child.token,bridge.state().pending[a.id]?.token);val posts=fake.posts.size;bridge.handle(op(child,"fire"));assertEquals(posts,fake.posts.size);assertTrue(p.state.items.containsKey("pack"))}}
    @Test fun unrelatedEditPreservesSnoozeChild(){bridge.sync();val a=pending("pack");fire(a);bridge.handle(op(a,"snooze10"));val child=bridge.state().pending.getValue(a.id);val p=planner();p.edit("bed",p.state.items.getValue("bed").at(a.day)!!.copy(note="Changed description"));bridge.sync();assertEquals(child,bridge.state().pending.getValue(a.id))}
    @Test fun notificationSkipInvalidatesOnlyItsOccurrenceAndUndoDoesNotReplayPastTime(){bridge.sync();val a=pending("pack");val depart=pending("depart");fire(a);bridge.handle(op(a,"skip"));assertEquals(HistoryState.SKIPPED,planner().occurrences(a.day).single{it.id==a.id}.state);assertEquals(depart,pending("depart"));planner().undoItem(a.id);bridge.sync();assertFalse(a.id in bridge.state().pending)}
    @Test fun dismissStopDoesNotCompleteOrReschedule(){bridge.sync();val a=pending("pack");fire(a);bridge.handle(op(a,"stop"));assertFalse(a.id in bridge.state().active);assertEquals(Entry(),planner().entry(a.id));bridge.handle(op(a,"snooze5"));assertFalse(a.id in bridge.state().pending)}
    @Test fun doneRecordsTheLogTimeOnceAndClearsOnlyTheCurrentOccurrence() {
        bridge.sync()
        val alert = pending("pack")
        val other = pending("depart")
        fire(alert)
        bridge.handle(op(alert, "done"))
        val saved = planner().entry(alert.id)
        assertEquals(Entry(Outcome.DONE, loggedAt = clock), saved)
        assertFalse(alert.id in bridge.state().active)
        assertFalse(alert.id in bridge.state().pending)
        assertEquals(other, pending("depart"))
        clock = clock.plusSeconds(60)
        bridge.handle(op(alert, "done"))
        assertEquals(saved, planner().entry(alert.id))
    }
    @Test fun bootClockZoneRebuildOnlyFutureAndNeverPosts(){bridge.sync();val a=pending("pack");fire(a);val count=fake.posts.size;bridge.sync(AlarmSync.REBUILD);assertTrue(bridge.state().pending.values.all{it.trigger>clock});assertTrue(bridge.state().active.isEmpty());assertEquals(count,fake.posts.size);zone=ZoneId.of("Asia/Kolkata");bridge.sync(AlarmSync.REBUILD);assertTrue(bridge.state().pending.values.all{it.zoneId==zone.id})}
    @Test fun ordinaryZoneChangeInvalidatesSnoozeAndActiveTokens(){bridge.sync();val a=pending("pack");fire(a);bridge.handle(op(a,"snooze5"));val child=bridge.state().pending.getValue(a.id);zone=ZoneId.of("Asia/Kolkata");bridge.sync();assertNotEquals(child.token,bridge.state().pending[a.id]?.token);assertTrue(bridge.state().active.isEmpty())}
    @Test fun restoreReassertsSameStoredOverdueOccurrence(){bridge.sync();val a=pending("pack");clock=a.trigger.plusSeconds(5);fake.alarms.clear();bridge.sync(AlarmSync.RESTORE_PENDING);assertEquals(a,bridge.state().pending.getValue(a.id));assertEquals(a,fake.alarms[a.id])}
    @Test fun firstCutoverPreservesMatchingLegacyDueOccurrenceAndInvalidatesLegacyDispatch(){clock=Instant.parse("2026-11-09T07:00:05Z");kv.put("alarm_v2.pack","07:00|Z|${Instant.parse("2026-11-09T07:00:00Z").toEpochMilli()}|old-token");bridge.sync(AlarmSync.RESTORE_PENDING);assertEquals(Instant.parse("2026-11-09T07:00:00Z"),pending("pack").trigger);val old=Intent(ReminderScheduler.ACTION).putExtra("routine_id","pack");ReminderReceiver().onReceive(app,old);assertTrue(fake.posts.isEmpty())}
    @Test fun restartingUsesDurablePendingAndConfigState(){bridge.configure("pack",GentleConfig(true,true));val before=bridge.state();val reloaded=LiveReminders(app,{clock},{zone},fake);assertEquals(before,reloaded.state());reloaded.sync();assertEquals(before.pending,reloaded.state().pending)}
    @Test fun malformedStateIsRejectedWithoutReplacingTheCommittedState() {
        val existing = bridge.initialize()
        assertThrows(IllegalStateException::class.java) { kv.put(LiveStore.KEY, "broken") }
        assertEquals(existing, bridge.initialize())
        assertTrue(fake.alarms.isEmpty())
    }

    @Test fun customSnoozeChecksTheActiveTokenAndKeepsItActiveOnSchedulingFailure() {
        bridge.configure("pack", GentleConfig(true, precise = true))
        val alert = pending("pack")
        fire(alert)
        val tomorrow = clock.plusSeconds(24 * 3600)
        fake.failArm = true
        assertThrows(SecurityException::class.java) { bridge.snooze(alert, tomorrow) }
        assertEquals(alert, bridge.state().active[alert.id])
        assertEquals(Entry(), planner().entry(alert.id))
        fake.failArm = false
        bridge.snooze(alert, tomorrow)
        val snoozed = bridge.state().pending.getValue(alert.id)
        assertEquals(tomorrow, snoozed.trigger)
        assertNotEquals(alert.token, snoozed.token)
        assertFalse(alert.id in bridge.state().active)
        assertThrows(IllegalStateException::class.java) { bridge.snooze(alert, tomorrow) }
    }
    @Test fun testReminderDoesNotCreateOccurrenceOrChangeModeOrRounds(){bridge.sync();val before=planner().state;val config=bridge.config("pack");bridge.test("pack");val a=bridge.state().pending.values.single{it.test};fire(a);assertEquals(before,planner().state);assertEquals(config,bridge.config("pack"));assertTrue(bridge.state().events.any{it.status=="TEST_DELIVERED"})}
    @Test fun launcherRejectsUntrustedAndStaleOpenPayloads(){
        bridge.sync();val a=pending("pack");fire(a)
        fun opening(token:String):Intent {
            return Intent(app,MainActivity::class.java).setData(android.net.Uri.parse("dailyrhythm://open/$token")).putExtra(AndroidLivePlatform.OPEN,a.id)
        }
        assertNull(validatedNotificationOpen(app,opening("forged")))
        assertEquals(a.id,validatedNotificationOpen(app,opening(a.token)))
        bridge.handle(op(a,"stop"));assertNull(validatedNotificationOpen(app,opening(a.token)))
    }
    @Test fun testCannotQueueBeforeMasterOptIn(){RhythmStore(kv).remindersEnabled=false;assertThrows(IllegalStateException::class.java){bridge.test("pack")};assertTrue(fake.alarms.isEmpty())}
    @Test fun stalePlannerCannotEraseNotificationSkipOrRitualProgress(){val stale=planner();val fresh=planner();fresh.skip(id("pack"));fresh.setRounds(id(FlexiblePlanner.AFFIRMATIONS),1);stale.edit("bed",stale.state.items.getValue("bed").at(LocalDate.of(2026,11,9))!!.copy(note="Concurrent edit"));assertEquals(HistoryState.SKIPPED,planner().occurrences(LocalDate.of(2026,11,9)).single{it.id==id("pack")}.state);assertEquals(1,planner().ritual(id(FlexiblePlanner.AFFIRMATIONS)).rounds)}
    @Test fun originalGentleCodecAndSignaturePreserveUnchangedOverdueAlert(){
        bridge.sync();val a=pending("pack");val j=org.json.JSONObject(kv.get(LiveStore.KEY)!!);val configs=j.getJSONObject("configs")
        configs.keys().forEach {key->configs.getJSONObject(key).apply {remove("ringing");remove("sound");remove("vibrate")}}
        kv.put(LiveStore.KEY,j.toString());clock=a.trigger.plusSeconds(20)
        val fresh=LiveReminders(app,{clock},{zone},fake);fresh.sync();assertEquals(a,fresh.state().pending[a.id]);assertFalse(fresh.config("pack").ringing)
    }
    @Test fun ringingCannotSilentlySelectApproximateTiming(){val before=bridge.state();assertThrows(IllegalArgumentException::class.java){bridge.configure("pack",GentleConfig(true,false,true))};assertEquals(before,bridge.state());fake.exact=false;bridge.configure("pack",GentleConfig(true,true,true));assertFalse(id("pack") in bridge.state().pending);assertTrue(bridge.state().events.any {it.detail.contains("Alarm not enabled")})}
    @Test fun ringingStartFailureIsHealthAndNotAnActiveOrCompletedRoutine(){bridge.configure("pack",GentleConfig(true,true,true));val a=pending("pack");fire(a);assertFalse(a.id in bridge.state().active);assertTrue(bridge.state().events.any {it.id==a.id && it.status=="POST_BLOCKED"});assertEquals(Entry(),planner().entry(a.id))}
    @Test fun invalidSoundConfigurationDoesNotMutateStoredState(){val before=bridge.state();assertThrows(IllegalArgumentException::class.java){bridge.configure("pack",GentleConfig(true,true,true,"tone:https://example.invalid"))};assertEquals(before,bridge.state())}

}
