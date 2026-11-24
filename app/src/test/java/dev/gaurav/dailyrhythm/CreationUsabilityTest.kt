package dev.gaurav.dailyrhythm

import android.app.Application
import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class CreationUsabilityTest {
    private val day=LocalDate.of(2026,11,9)
    private class Batch:AtomicKeyValue {
        val data=mutableMapOf<String,String>();var fail=false;var commits=0;var last=emptySet<String>()
        override fun get(key:String)=data[key]
        override fun put(key:String,value:String?){if(value==null)data.remove(key)else data[key]=value}
        override fun putAll(values:Map<String,String?>){commits++;last=values.keys;check(!fail){"Injected commit failure"};values.forEach {(k,v)->put(k,v)}}
    }
    private fun storage():Batch=Batch().also {kv->FlexiblePlanner(kv){day};LiveStore(kv).save(LiveState());kv.put("legacy-protected","keep");kv.commits=0}
    @Test fun routineAndRitualEachSaveEveryVisibleReminderModeInOneCommit(){
        for(kind in ItemKind.entries)for(c in listOf(GentleConfig(false),GentleConfig(true),GentleConfig(true,true),GentleConfig(true,true,true))){
            val kv=storage();val v=PlanVersion(day,"Created $kind",kind=kind,time=LocalTime.NOON,content=if(kind==ItemKind.RITUAL)listOf("Read this")else emptyList(),target=3)
            val id=PlannedCreation.create(kv,LiveStore(kv).read()!!,v,c,day)
            assertEquals(1,kv.commits);assertEquals(setOf(FlexiblePlanner.KEY,LiveStore.KEY),kv.last)
            assertEquals(c,LiveStore(kv).read()!!.configs[id]);val p=FlexiblePlanner(kv){day};assertEquals(v,p.state.items.getValue(id).versions.single());assertEquals(Entry(),p.entry(FlexiblePlanner.identity(id,day)));assertEquals("keep",kv.get("legacy-protected"))
            assertTrue(p.state.reports.isEmpty());assertTrue(p.state.rituals.isEmpty())
        }
    }
    @Test fun failedAtomicCreateLeavesNoPartialPlanConfigOrLostHistory(){val kv=storage();FlexiblePlanner(kv){day}.report(FlexiblePlanner.identity("pack",day),Entry(Outcome.DONE,LocalTime.of(7,3)));val before=kv.data.toMap();kv.fail=true;assertThrows(IllegalStateException::class.java){PlannedCreation.create(kv,LiveStore(kv).read()!!,PlanVersion(day,"Not saved",time=LocalTime.NOON),GentleConfig(true,true,true),day)};assertEquals(before,kv.data)}
    @Test fun editKeepsOccurrenceHistoryAndDoesNotResumeAnExplicitPause() {
        val kv = storage()
        val id = FlexiblePlanner.identity("wake", day)
        val planner = FlexiblePlanner(kv) { day }
        planner.report(id, Entry(Outcome.DONE, LocalTime.of(6, 1)))
        RhythmStore(kv).remindersEnabled = false
        val version = planner.state.items.getValue("wake").at(day)!!.copy(title = "Earlier wake-up")
        val config = GentleConfig(true, true, true)
        val before = kv.data.toMap()
        kv.fail = true
        assertThrows(IllegalStateException::class.java) {
            PlannedCreation.save(kv, LiveStore(kv).read()!!, version, config, day, "wake", true)
        }
        assertEquals(before, kv.data)
        kv.fail = false
        PlannedCreation.save(kv, LiveStore(kv).read()!!, version, config, day, "wake", true)
        assertFalse(RhythmStore(kv).remindersEnabled)
        assertEquals(config, LiveStore(kv).read()!!.configs["wake"])
        val reopened = FlexiblePlanner(kv) { day }
        assertEquals(Outcome.DONE, reopened.entry(id).outcome)
        assertEquals("Earlier wake-up", reopened.state.items.getValue("wake").at(day)?.title)
    }
    @Test fun cloneOfArchivedOneTimeReminderKeepsSettingsAndGetsAUsableDate() {
        val original = PlanVersion(day.minusDays(1), "Take out recycling", time = LocalTime.of(8, 0),
            rule = RepeatRule(RepeatKind.ONCE, oneDate = day.minusDays(1)), archived = true,
            note = "Blue bin", durationMinutes = 5, category = ReminderCategory.PERSONAL)
        val copy = ReminderDraft.clone(original, day.atTime(10, 0))
        assertEquals(original.copy(from = day, rule = original.rule.copy(oneDate = day.plusDays(1)), archived = false), copy)
        val kv = storage()
        val config = GentleConfig(enabled = true, precise = true, ringing = true, vibrate = true)
        val clone = PlannedCreation.create(kv, LiveStore(kv).read()!!, copy, config, day)
        assertEquals(config, LiveStore(kv).read()!!.configs[clone])
        assertTrue(FlexiblePlanner(kv) { day }.state.reports.isEmpty())
    }
    @Test fun anytimeRequiresExplicitOffWithoutMutations(){val kv=storage();val before=kv.data.toMap();for(c in listOf(GentleConfig(true),GentleConfig(true,true,true)))assertThrows(IllegalArgumentException::class.java){PlannedCreation.create(kv,LiveStore(kv).read()!!,PlanVersion(day,"Anytime"),c,day)};assertEquals(before,kv.data);val id=PlannedCreation.create(kv,LiveStore(kv).read()!!,PlanVersion(day,"Anytime"),GentleConfig(false),day);assertNull(FlexiblePlanner(kv){day}.state.items.getValue(id).at(day)!!.time)}
    @Test fun ringingNeverSilentlyBecomesApproximate(){val kv=storage();val before=kv.data.toMap();assertThrows(IllegalArgumentException::class.java){PlannedCreation.create(kv,LiveStore(kv).read()!!,PlanVersion(day,"Invalid",time=LocalTime.NOON),GentleConfig(true,false,true),day)};assertEquals(before,kv.data)}
    @Test fun reviewIncludesTimezoneDatesActualModeAndNoCompletionPromise(){val v=PlanVersion(day,"Read",kind=ItemKind.RITUAL,time=LocalTime.NOON,content=listOf("Line"),target=3,rule=RepeatRule(RepeatKind.WEEKDAYS,setOf(DayOfWeek.MONDAY,DayOfWeek.FRIDAY)));val summary=ReminderDraft.summary(v,GentleConfig(true,true,true),listOf(day,day.plusDays(4)),ZoneId.of("Asia/Kolkata"));for(word in listOf("Read","Ritual","12:00","Asia/Kolkata","2026-11-09","2026-11-13","Alarm","never complete"))assertTrue(word,summary.contains(word))}
    @Test fun oneOffBeforeStartIsRejectedAndMonthlyPolicyRemainsExplicit(){assertThrows(IllegalArgumentException::class.java){ReminderDraft.validate(PlanVersion(day.plusDays(1),"Invalid",rule=RepeatRule(RepeatKind.ONCE,oneDate=day)),GentleConfig(false))};assertThrows(IllegalArgumentException::class.java){RepeatRule(RepeatKind.MONTHLY,monthDays=setOf(31))};val rule=RepeatRule(RepeatKind.MONTHLY,monthDays=setOf(31),missing=MissingDate.MONTH_END);assertEquals(setOf(LocalDate.of(2027,2,28)),rule.monthDates(YearMonth.of(2027,2)))}
    private class Platform:LivePlatform {
        var exact=false;var failArm=false
        override fun exactAllowed()=exact
        override fun blocked(item:String):String?=null
        override fun exists(alert:LiveAlert)=false
        override fun arm(alert:LiveAlert){if(failArm)throw SecurityException("Injected schedule failure")}
        override fun cancel(alert:LiveAlert){}
        override fun show(alert:LiveAlert,title:String,late:Boolean){}
        override fun dismiss(id:String){}
    }
    @Test fun savingDeniedExactChoiceKeepsPlanAndShowsNotEnabledWithoutFallback(){val app:Application=RuntimeEnvironment.getApplication();app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).edit().clear().commit();RhythmStore(Preferences(app)).remindersEnabled=true;val platform=Platform();val bridge=LiveReminders(app,{day.atTime(6,0).toInstant(ZoneOffset.UTC)},{ZoneOffset.UTC},platform);val result=bridge.createPlanned(PlanVersion(day,"Ringing",time=LocalTime.NOON),GentleConfig(true,true,true));assertNotNull(result.warning);assertTrue(result.warning!!.contains("Alarm not enabled"));assertTrue(bridge.config(result.id).ringing);assertTrue(bridge.state().pending.values.none {it.id.startsWith(result.id+"@")});assertEquals("Ringing",FlexiblePlanner(Preferences(app)){day}.state.items[result.id]!!.at(day)!!.title)}
    @Test fun postSaveSchedulingFailureDoesNotCreateRetryablePartialForm(){val app:Application=RuntimeEnvironment.getApplication();app.getSharedPreferences("rhythm_v1",0).edit().clear().commit();RhythmStore(Preferences(app)).remindersEnabled=true;val platform=Platform().apply {exact=true;failArm=true};val bridge=LiveReminders(app,{day.atTime(6,0).toInstant(ZoneOffset.UTC)},{ZoneOffset.UTC},platform);val result=bridge.createPlanned(PlanVersion(day,"Saved once",time=LocalTime.NOON),GentleConfig(true,true,true));assertTrue(FlexiblePlanner(Preferences(app)){day}.state.items.containsKey(result.id));assertTrue(bridge.state().events.any {it.id.startsWith(result.id+"@") && it.status=="SCHEDULE_BLOCKED"})}
    @Test fun alarmPresentationFiltersStaleOccurrenceTokensAndLabelsQueueTruthfully(){val a=LiveAlert("pack@$day#base",day,Instant.EPOCH,"one","sig",true);val b=a.copy(id="depart@$day#base",token="two");val view=RingingSnapshot("session",300000,a.id,listOf(a,b));val state=LiveState(active=mapOf(a.id to a,b.id to b));val rows=AlarmPresentation.rows(view,state){it.id};assertEquals(listOf("Current alert","Queued alert"),rows.map {it.status});val replaced=state.copy(active=state.active+(b.id to b.copy(token="new")));assertEquals(listOf(a.id),AlarmPresentation.rows(view,replaced){it.id}.map {it.alert.id})}
}
