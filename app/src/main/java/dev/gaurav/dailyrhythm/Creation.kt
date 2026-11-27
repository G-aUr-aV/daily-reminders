package dev.gaurav.dailyrhythm

import java.time.LocalDate
import java.time.ZoneId

internal object ReminderDraft {
    fun validate(version:PlanVersion,config:GentleConfig) {
        require(!config.enabled || version.time!=null) {"Choose a time for a reminder, or select Off for Anytime."}
        require(version.rule.kind!=RepeatKind.ONCE || version.rule.oneDate!!>=version.from) {"The one-off date must be on or after the start date."}
        require(!config.ringing || config.precise) {"Ringing requires explicit precise timing; no approximate fallback."}
    }
    fun label(c:GentleConfig)=when { !c.enabled->"Off";c.ringing->"Alarm";else->"Notification" }
    fun repeat(rule:RepeatRule):String=when(rule.kind) {
        RepeatKind.DAILY->"Every day"
        RepeatKind.OFFICE, RepeatKind.HOME, RepeatKind.LEAVE, RepeatKind.WEEKEND -> DayType.entries.first { it.repeat == rule.kind }.label
        RepeatKind.WEEKDAYS->rule.weekdays.sortedBy {it.value}.joinToString {it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())}
        RepeatKind.MONTHLY->"Monthly dates: "+(rule.monthDays.sorted().map(Int::toString)+if(rule.lastDay)listOf("Last day")else emptyList()).joinToString()+when(rule.missing){MissingDate.SKIP->" · skip missing dates";MissingDate.MONTH_END->" · use month's last day";null->""}
        RepeatKind.ONCE->"One date: ${rule.oneDate}"
    }
    fun summary(v:PlanVersion,c:GentleConfig,next:List<LocalDate>,zone:ZoneId):String {
        validate(v,c)
        val completion = if (v.kind == ItemKind.RITUAL) "Record reading rounds in the app." else "Done records this routine as completed."
        return "${v.title} · ${if(v.kind==ItemKind.RITUAL) "Ritual" else "Routine"}\n${repeat(v.rule)} · ${v.time ?: "Anytime"} · ${zone.id}\nFrom ${v.from} forward\nReminder: ${label(c)}\nNext: ${next.joinToString().ifEmpty {"No occurrence in preview horizon"}}\nSnooze 5/10 is a request, not a punctuality guarantee. $completion Stop and Snooze leave the outcome open."
    }
}
/** Stage plan/config writes, then publish both with one commit; no partial created item on failure. */
internal object PlannedCreation {
    fun create(kv:AtomicKeyValue,state:LiveState,version:PlanVersion,config:GentleConfig,today:LocalDate):String =
        save(kv, state, version, config, today)

    fun save(kv: AtomicKeyValue, state: LiveState, version: PlanVersion, config: GentleConfig, today: LocalDate,
             item: String? = null, enableFirstAlert: Boolean = false): String {
        ReminderDraft.validate(version,config)
        if (kv is Preferences) {
            val initial = requireNotNull(kv.planSnapshot()) { "Initialize planning before creating a reminder." }
            val draft = object : KeyValue by kv, PlanSnapshots {
                private var plan = initial
                override fun planSnapshot() = plan
                override fun savePlan(plan: FlexState) { this.plan = plan }
            }
            val planner = FlexiblePlanner(draft, persistMigration = false) { today }
            val id = item?.also { planner.edit(it, version) } ?: planner.create(version)
            val switch = if (enableFirstAlert && config.enabled && kv.get(RhythmStore.REMINDERS_KEY) == null) {
                mapOf(RhythmStore.REMINDERS_KEY to "true")
            } else emptyMap()
            kv.saveLive(state.copy(configs = state.configs + (id to config)), switch, planner.state)
            return id
        }
        require(kv.get(FlexiblePlanner.KEY)!=null) {"Initialize existing planning data before creating an item."}
        class Staged:KeyValue {
            val writes=mutableMapOf<String,String?>()
            override fun get(key:String)=if(writes.containsKey(key))writes[key]else kv.get(key)
            override fun put(key:String,value:String?){writes[key]=value}
        }
        val staged=Staged();val p=FlexiblePlanner(staged,persistMigration=false,today={today})
        val id=item?.also { p.edit(it, version) } ?: p.create(version)
        LiveStore(staged).save(state.copy(configs=state.configs+(id to config)))
        check(staged.writes.keys==setOf(FlexiblePlanner.KEY,LiveStore.KEY))
        if (enableFirstAlert && config.enabled && kv.get(RhythmStore.REMINDERS_KEY) == null) {
            staged.put(RhythmStore.REMINDERS_KEY, "true")
        }
        kv.putAll(staged.writes)
        return id
    }
}

data class CreatedPlan(val id:String,val warning:String?)
internal data class RingingSnapshot(val sessionToken:String,val deadlineElapsed:Long,val currentId:String?,val alerts:List<LiveAlert>)
internal data class AlarmRow(val alert:LiveAlert,val title:String,val status:String,val health:String)
internal object AlarmPresentation {
    fun rows(snapshot:RingingSnapshot,state:LiveState,title:(LiveAlert)->String):List<AlarmRow> = snapshot.alerts.mapNotNull {a->
        if(state.active[a.id]?.token!=a.token)null else AlarmRow(a,title(a),if(a.id==snapshot.currentId)"Current alert"else"Queued alert",state.events.lastOrNull {it.id==a.id}?.let {"${it.status}: ${it.detail}"} ?: "No recent health event")
    }
}
