package dev.gaurav.dailyrhythm

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.*
import java.util.UUID

// Backward-compatible defaults retain existing Gentle state and timing signatures.
data class GentleConfig(val enabled: Boolean, val precise: Boolean = false, val ringing: Boolean = false, val sound: String = "default", val vibrate: Boolean = false, val toneTitle:String = "", val fullScreen:Boolean = true)
data class LiveAlert(val id: String, val day: LocalDate, val trigger: Instant, val token: String,
    val signature: String, val precise: Boolean, val snooze: Boolean = false, val zoneId: String = ZoneId.systemDefault().id,
    val test: Boolean = false, val alarmClock: Boolean = false)
data class AlertEvent(val id: String, val status: String, val detail: String, val at: Instant)
data class LiveState(val configs: Map<String,GentleConfig> = emptyMap(), val pending: Map<String,LiveAlert> = emptyMap(),
    val active: Map<String,LiveAlert> = emptyMap(), val closed: Map<String,String> = emptyMap(), val events: List<AlertEvent> = emptyList())

internal const val SNOOZE_PREFERENCE = "default.snooze.minutes"
internal const val ASK_SNOOZE = "ask"
internal fun preferredSnooze(storage: KeyValue): Int = if (storage.get(SNOOZE_PREFERENCE) == "10") 10 else 5

internal class LiveStore(private val kv: KeyValue) {
    companion object {
        const val KEY = "live.alerts.v1"

        fun decode(text: String): LiveState {
        try {
            require(text.length<=8_000_000); val j=JSONObject(text); require(j.getInt("schema")==1 && j.getBoolean("cutover"))
            fun alerts(key:String): Map<String,LiveAlert> = j.getJSONArray(key).let { a -> (0 until a.length()).associate { i ->
                val x=a.getJSONObject(i); val id=x.getString("id"); FlexiblePlanner.parts(id)
                id to LiveAlert(id,LocalDate.parse(x.getString("day")),Instant.ofEpochMilli(x.getLong("at")),x.getString("token"),x.getString("sig"),x.getBoolean("precise"),x.getBoolean("snooze"),x.getString("zone"),x.getBoolean("test"),x.optBoolean("alarmClock"))
            } }
            val configs=j.getJSONObject("configs");val closed=j.getJSONObject("closed");val events=j.getJSONArray("events")
            return LiveState(configs.keys().asSequence().associate { k -> val c=configs.getJSONObject(k); k to GentleConfig(c.getBoolean("enabled"),c.getBoolean("precise"),c.optBoolean("ringing",false),c.optString("sound","default"),c.optBoolean("vibrate",false),c.optString("toneTitle",""),c.optBoolean("fullScreen",true)) },
                alerts("pending"),alerts("active"),closed.keys().asSequence().associateWith { closed.getString(it) },
                (0 until events.length()).map { val e=events.getJSONObject(it); AlertEvent(e.getString("id"),e.getString("status"),e.getString("detail"),Instant.ofEpochMilli(e.getLong("at"))) })
        } catch(e:Exception) { throw IllegalStateException("Reminder state cannot be read; existing data was not reset.",e) }
        }

        fun encode(state: LiveState, portable: Boolean = true): String {
        fun alerts(map:Map<String,LiveAlert>)=JSONArray().also { a -> map.values.forEach { x -> a.put(JSONObject().put("id",x.id).put("day",x.day.toString()).put("at",x.trigger.toEpochMilli()).put("token",x.token).put("sig",x.signature).put("precise",x.precise).put("snooze",x.snooze).put("zone",x.zoneId).put("test",x.test).put("alarmClock",x.alarmClock)) } }
        val j=JSONObject().put("schema",1).put("cutover",true)
        val configs=JSONObject();state.configs.forEach { (id,c)->configs.put(id,JSONObject().put("enabled",c.enabled).put("precise",c.precise).put("ringing",c.ringing).put("sound",c.sound).put("vibrate",c.vibrate).put("toneTitle",c.toneTitle).put("fullScreen",c.fullScreen)) }
        val closed=JSONObject();state.closed.forEach { (id,sig)->closed.put(id,sig) }
        val events=JSONArray();state.events.forEach { e->events.put(JSONObject().put("id",e.id).put("status",e.status).put("detail",e.detail).put("at",e.at.toEpochMilli())) }
        j.put("configs",configs).put("pending",alerts(state.pending)).put("active",alerts(state.active)).put("closed",closed).put("events",events)
        return j.toString().also { text ->
            if (portable) require(text.length <= 8_000_000) { "Reminder event storage limit reached; no data reset." }
        }
        }
    }

    fun read(): LiveState? = if (kv is Preferences) kv.liveSnapshot() else kv.get(KEY)?.let(::decode)

    fun save(state: LiveState, additional: Map<String, String?> = emptyMap()) {
        when {
            kv is Preferences -> kv.saveLive(state, additional)
            additional.isEmpty() -> kv.put(KEY, encode(state))
            else -> {
                check(kv is AtomicKeyValue)
                kv.putAll(additional + (KEY to encode(state)))
            }
        }
    }
}

interface LivePlatform {
    fun exactAllowed():Boolean
    fun blocked(item:String):String?
    fun exists(alert:LiveAlert):Boolean
    fun arm(alert:LiveAlert)
    fun cancel(alert:LiveAlert)
    fun show(alert:LiveAlert,title:String,late:Boolean)
    fun dismiss(id:String)
    fun ringBlocked():String? = null
    fun ring(alert:LiveAlert,title:String) { error("Ringing platform unavailable") }
}

class AndroidLivePlatform(private val context:Context):LivePlatform {
    companion object {
        const val ACTION="dev.gaurav.dailyrhythm.LIVE_ALERT"
        const val OPEN="open_occurrence"
        const val OPEN_SNOOZE = "dev.gaurav.dailyrhythm.OPEN_SNOOZE"

        fun openIntent(context: Context, alert: LiveAlert, snooze: Boolean = false): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setData(Uri.Builder().scheme("dailyrhythm").authority("open").appendPath(alert.token).build())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(OPEN, alert.id)
            if (snooze) intent.action = OPEN_SNOOZE
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        fun channel(item:String)="routine_"+item
        fun intent(context:Context,alert:LiveAlert,operation:String)=Intent(context,ReminderReceiver::class.java).setAction(ACTION)
            .setData(Uri.Builder().scheme("dailyrhythm").authority("alert").appendPath(alert.token).appendPath(operation).build())
            .putExtra("occurrence",alert.id).putExtra("token",alert.token).putExtra("operation",operation)
    }
    private val alarms=context.getSystemService(AlarmManager::class.java)
    private val manager=context.getSystemService(NotificationManager::class.java)
    fun ensure(item:String) {
        val title=runCatching {Preferences(context).planSnapshot()?.items?.get(item)?.at(LocalDate.now())?.title}.getOrNull() ?: item
        val existing=manager.getNotificationChannel(channel(item))
        if(existing!=null) {
            existing.name="Daily Reminders · ${title.take(80)}"
            existing.description="Gentle routine notification; Android controls sound and delivery."
            manager.createNotificationChannel(existing)
            return
        }
        // Moving from the old shared channel must not bypass its mute/sound choices.
        val legacy=manager.getNotificationChannel(ReminderScheduler.CHANNEL)
        manager.createNotificationChannel(NotificationChannel(channel(item),"Daily Reminders · ${title.take(80)}",legacy?.importance ?: NotificationManager.IMPORTANCE_DEFAULT).apply {
            description="Gentle routine notification; Android controls sound and delivery."
            lockscreenVisibility=Notification.VISIBILITY_SECRET
            if(legacy!=null)setSound(legacy.sound,legacy.audioAttributes)
        })
    }
    override fun exactAllowed()=Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms()
    override fun blocked(item:String):String? {
        ensure(item)
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return "Notifications not allowed"
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "App notifications blocked"
        if(manager.getNotificationChannel(channel(item))?.importance==NotificationManager.IMPORTANCE_NONE) return "Item channel blocked"
        return null
    }
    fun channelDescription(item:String):String {
        ensure(item);val c=manager.getNotificationChannel(channel(item))
        val sound=c.sound
        val effective=if(sound==null) "Silent" else DeviceTones(context).currentTitle(sound)?.let {"Device notification tone: $it"} ?: "Android-managed sound (name unavailable): $sound"
        return "Importance ${c.importance}${if(c.importance==NotificationManager.IMPORTANCE_NONE) " · BLOCKED" else ""} · $effective · vibration ${if(c.shouldVibrate()) "On" else "Off"}. Read back from the existing channel; Android owns sound/mute. No ID rotation or override."
    }
    private fun pending(alert:LiveAlert,operation:String,flags:Int)=PendingIntent.getBroadcast(context,0,intent(context,alert,operation),flags or PendingIntent.FLAG_IMMUTABLE)
    override fun exists(alert:LiveAlert)=pending(alert,"fire",PendingIntent.FLAG_NO_CREATE)!=null
    override fun arm(alert:LiveAlert) {
        cancel(alert)
        val pi=requireNotNull(pending(alert,"fire",PendingIntent.FLAG_CANCEL_CURRENT))
        if (alert.precise) check(exactAllowed()) { "Alarm not enabled: exact access missing" }
        val trigger = alert.trigger.toEpochMilli()
        when {
            alert.alarmClock -> {
                check(alert.precise && !alert.test)
                val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), pi)
            }
            alert.precise -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            else -> alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }
    override fun cancel(alert:LiveAlert) { pending(alert,"fire",PendingIntent.FLAG_NO_CREATE)?.let {alarms.cancel(it);it.cancel()} }
    override fun dismiss(id:String) { NotificationManagerCompat.from(context).cancel(id,3000); RingingService.cancel(id) }
    override fun ringBlocked() = RingingService.blocked(context)
    override fun ring(alert:LiveAlert,title:String) { RingingService.launch(context,alert) }
    override fun show(alert:LiveAlert,title:String,late:Boolean) {
        val item=FlexiblePlanner.parts(alert.id).first
        check(blocked(item)==null) {"Notification controls unavailable"}
        val open = openIntent(context, alert)
        fun action(label:String,op:String)=NotificationCompat.Action.Builder(0,label,requireNotNull(pending(alert,op,PendingIntent.FLAG_UPDATE_CURRENT))).build()
        val hidden=NotificationCompat.Builder(context,channel(item)).setSmallIcon(R.drawable.ic_rhythm).setContentTitle("Daily Reminders").setContentText("A local reminder").build()
        val notification=NotificationCompat.Builder(context,channel(item)).setSmallIcon(R.drawable.ic_rhythm).setContentTitle(title)
            .setContentText(if (late) "Delayed reminder" else "Reminder")
            .setVisibility(NotificationCompat.VISIBILITY_SECRET).setPublicVersion(hidden).setContentIntent(open).setAutoCancel(true)
            .setDeleteIntent(requireNotNull(pending(alert,"dismiss",PendingIntent.FLAG_UPDATE_CURRENT)))
            .apply {
                if (!alert.test) {
                    val storage = Preferences(context)
                    val minutes = preferredSnooze(storage)
                    val row = FlexiblePlanner(storage, persistMigration = false) { LocalDate.now() }
                        .occurrences(alert.day).firstOrNull { it.id == alert.id }
                    addAction(action("Dismiss", "dismiss"))
                    if (storage.get(SNOOZE_PREFERENCE) == ASK_SNOOZE) addAction(
                        NotificationCompat.Action.Builder(0, "Snooze…", openIntent(context, alert, snooze = true)).build())
                    else addAction(action("Snooze ${minutes}m", "snooze$minutes"))
                    if (row?.version?.kind == ItemKind.ROUTINE) addAction(action("Done", "done"))
                }
            }.build()
        try {NotificationManagerCompat.from(context).notify(alert.id,3000,notification)} catch(e:SecurityException) {throw IllegalStateException("Notification permission changed",e)}
    }
}

class LiveReminders(private val context:Context,private val now:()->Instant={Instant.now()},private val zone:()->ZoneId={ZoneId.systemDefault()},
    private val platform:LivePlatform=AndroidLivePlatform(context)) {
    companion object {
        fun migrated(context:Context)=Preferences(context).liveSnapshot() != null
        private fun signature(row:PlannedOccurrence,config:GentleConfig):String {
            val compatibleConfig=if(!config.ringing) "GentleConfig(enabled=${config.enabled}, precise=${config.precise})" else "GentleConfig(enabled=${config.enabled}, precise=${config.precise}, ringing=${config.ringing}, sound=${config.sound}, vibrate=${config.vibrate})"
            // Calendar duration and visual alarm presentation do not alter the trigger.
            val compatibleVersion = row.version.toString().substringBeforeLast(", durationMinutes=") + ")"
            val text="${row.id}|${row.date}|${row.time}|$compatibleVersion|$compatibleConfig"
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
        }
    }
    private val kv=Preferences(context)
    private val store=LiveStore(kv)
    private fun planner()=FlexiblePlanner(kv) {now().atZone(zone()).toLocalDate()}
    private fun event(state:LiveState,id:String,status:String,detail:String):LiveState {
        val last=state.events.lastOrNull {it.id==id}
        if(last?.status==status && last.detail==detail) return state
        return state.copy(events=state.events+AlertEvent(id,status,detail.take(512),now()))
    }
    fun initialize():LiveState {
        store.read()?.let {return it}
        val date=now().atZone(zone()).toLocalDate();val missingPlan=kv.planSnapshot() == null
        val p = FlexiblePlanner(kv, persistMigration = false, includeLegacyRoutines = kv.hasLegacyRhythmData()) { date }
        val plan = p.state
        // Existing plans and explicit legacy choices retain their alarm settings.
        val hasSavedPlanOrSwitch = !missingPlan || kv.get(RhythmStore.REMINDERS_KEY) != null
        var state = LiveState(configs = plan.items.mapValues { (id, item) ->
            val wasConfigured = hasSavedPlanOrSwitch || kv.get("schedule.$id") != null
            GentleConfig(wasConfigured && item.at(date)?.gentleEnabled == true)
        })
        // Import only a still-matching current/future legacy occurrence; never fabricate a past schedule.
        if(RhythmStore(kv).remindersEnabled) Routines.all.forEach { routine ->
            runCatching {
                val parts=kv.get("alarm_v2.${routine.id}")?.split('|') ?: return@runCatching
                val at=Instant.ofEpochMilli(parts[2].toLong());val d=at.atZone(zone()).toLocalDate()
                val row=p.occurrences(d).firstOrNull {it.itemId==routine.id && it.id==FlexiblePlanner.identity(routine.id,d)} ?: return@runCatching
                val c=state.configs.getValue(routine.id)
                if(d>=date && row.scheduled && row.time!=null && c.enabled && row.state==HistoryState.UNLOGGED && parts[1]==zone().id && d.atTime(row.time).atZone(zone()).toInstant().toEpochMilli()==at.toEpochMilli()) {
                    val a=LiveAlert(row.id,d,at,UUID.randomUUID().toString(),signature(row,c),false,zoneId=zone().id)
                    state=state.copy(pending=state.pending+(a.id to a))
                }
            }
        }
        // Migration marker and state in ONE durable commit; legacy values/logs are untouched.
        kv.saveLive(state, plan = plan.takeIf { missingPlan })
        retireLegacy()
        return state
    }
    private fun retireLegacy() {
        val alarm=context.getSystemService(AlarmManager::class.java)
        (Routines.all.mapIndexed {i,r->(1000+i) to r.id}+listOf(ReminderScheduler.TEST_CODE to ReminderScheduler.TEST)).forEach { (code,id)->
            val intent=Intent(context,ReminderReceiver::class.java).setAction(ReminderScheduler.ACTION).putExtra("routine_id",id)
            PendingIntent.getBroadcast(context,code,intent,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {alarm.cancel(it);it.cancel()}
        }
    }
    fun state()=initialize()
    fun config(item:String):GentleConfig {
        val state=initialize();val p=planner();return state.configs[item] ?: GentleConfig(p.state.items[item]?.at(now().atZone(zone()).toLocalDate())?.gentleEnabled ?: false)
    }
    private fun checkSound(value:GentleConfig,previous:GentleConfig?=null) {
        require(value.toneTitle.length<=128 && value.toneTitle.none {Character.isISOControl(it)}) {"Invalid device tone title."}
        require(!value.ringing || value.precise) { "Ringing requires explicit precise timing; no approximate fallback." }
        require(BuiltInAlarm.find(value.sound) != null || (value.sound.startsWith("private:") && AudioPolicy.opaque(value.sound.removePrefix("private:"))) || (value.sound.startsWith("tone:") && PrivateSounds.deviceTone(context,Uri.parse(value.sound.removePrefix("tone:"))))) { "Invalid local sound selection." }
        if(value.sound.startsWith("private:") && (value.enabled || previous?.sound!=value.sound))SoundLibrary(context).ensureSelectable(value.sound)
    }
    fun configure(item:String,value:GentleConfig) {
        PrivateSounds.locked {
            val state=initialize();require(planner().state.items.containsKey(item));checkSound(value,state.configs[item])
            store.save(state.copy(configs=state.configs+(item to value)))
        }
        sync()
    }
    fun createPlanned(version:PlanVersion,value:GentleConfig):CreatedPlan = savePlanned(version, value)

    fun savePlanned(version: PlanVersion, value: GentleConfig, item: String? = null, enableFirstAlert: Boolean = false): CreatedPlan {
        if (item == null && value.enabled && version.rule.kind == RepeatKind.ONCE && version.time != null) {
            val scheduled = requireNotNull(version.rule.oneDate).atTime(version.time).atZone(zone()).toInstant()
            require(scheduled > now()) { "Choose a future time for a one-time reminder." }
        }
        val id=PrivateSounds.locked {
            val state=initialize();checkSound(value, item?.let { state.configs[it] })
            val today = now().atZone(zone()).toLocalDate()
            val versionInUse = item?.let { planner().state.items.getValue(it).at(today) }
            require(versionInUse == null || version.from <= today || value == config(requireNotNull(item))) {
                "Save the future schedule with its current alert, then edit the alert separately."
            }
            PlannedCreation.save(kv,state,version,value,today,item,enableFirstAlert)
        }
        // A scheduling/health failure after the atomic save must not leave a retryable duplicate form.
        val error=runCatching {sync()}.exceptionOrNull()
        if(error!=null)runCatching {audioHealth(FlexiblePlanner.identity(id,version.from),"RECONCILE_BLOCKED",error.message ?: "Plan saved; reminder reconciliation failed")}
        val warning=error?.let {"Plan saved. Reminder reconciliation needs attention: ${it.message}"} ?: runCatching {when {
            value.enabled && !RhythmStore(kv).remindersEnabled->"Plan saved. Local reminders are globally Off; enable them in Settings."
            value.enabled && value.precise && !platform.exactAllowed()->"Plan saved. Alarm not enabled: exact access is missing; no approximate fallback."
            value.enabled->platform.blocked(id)?.let {"Plan saved. $it"} ?: state().events.lastOrNull {it.id.startsWith("$id@") && it.status.endsWith("BLOCKED")}?.let {"Plan saved. ${it.status}: ${it.detail}"}
            else->null
        }}.getOrElse {"Plan saved. Reminder health could not be read: ${it.message}"}
        return CreatedPlan(id,warning)
    }
    fun exactAllowed()=platform.exactAllowed()
    private fun row(p:FlexiblePlanner,alert:LiveAlert)=p.occurrences(alert.day).firstOrNull {it.id==alert.id}
    private fun valid(p:FlexiblePlanner,a:LiveAlert,state:LiveState):PlannedOccurrence? {
        if(!RhythmStore(kv).remindersEnabled)return null
        val item=FlexiblePlanner.parts(a.id).first
        if(p.isArchived(item, now().atZone(zone()).toLocalDate()))return null
        if(a.zoneId!=zone().id)return null
        if(a.day<now().atZone(zone()).toLocalDate() && (p.state.items[item]?.at(now().atZone(zone()).toLocalDate())?.from ?: LocalDate.MIN)>a.day)return null
        if (item in p.state.deleted) return null
        val r=row(p,a) ?: return null;val c=state.configs[item] ?: GentleConfig(r.version.gentleEnabled)
        return r.takeIf {it.scheduled && it.state==HistoryState.UNLOGGED && c.enabled && a.precise==c.precise && it.time!=null && signature(it,c)==a.signature}
    }
    private fun blocked(r:PlannedOccurrence,c:GentleConfig):String? {
        if(c.precise && !platform.exactAllowed())return "Alarm not enabled: exact access denied or revoked. No automatic approximate fallback."
        if(c.ringing && !c.precise) return "Alarm not enabled: Ringing requires precise timing."
        return platform.blocked(r.itemId) ?: if(c.ringing) platform.ringBlocked() else null
    }
    fun sync(mode:AlarmSync=AlarmSync.RECONCILE) {
        var state=initialize();retireLegacy()
        val p=planner();val current=now();val currentZone=zone();val date=current.atZone(currentZone).toLocalDate()
        val master=RhythmStore(kv).remindersEnabled
        val old=state.pending;val keep=mutableMapOf<String,LiveAlert>();val active=state.active.toMutableMap();val arm=mutableListOf<LiveAlert>()
        old.values.forEach { a ->
            if(a.test) {
                if(master && mode!=AlarmSync.REBUILD && FlexiblePlanner.parts(a.id).first !in p.state.deleted && platform.blocked(FlexiblePlanner.parts(a.id).first)==null) {keep[a.id]=a;if(mode==AlarmSync.RESTORE_PENDING || !platform.exists(a))arm+=a}
                else state=event(state,a.id,"CANCELLED","Test cancelled")
                return@forEach
            }
            val r=valid(p,a,state);val c=r?.let {state.configs[it.itemId] ?: GentleConfig(it.version.gentleEnabled)}
            val error=if(r!=null && c!=null)blocked(r,c) else null
            // A zone/clock/boot rebuild is explicit. Ordinary reconciliation preserves unchanged overdue.
            val wallMatches=r?.time?.let {a.day.atTime(it).atZone(currentZone).toInstant().toEpochMilli()==a.trigger.toEpochMilli()} ?: false
            if(mode!=AlarmSync.REBUILD && r!=null && error==null && (a.snooze || wallMatches)) {
                val updated = a.copy(alarmClock = c?.ringing == true)
                keep[a.id] = updated
                if(mode==AlarmSync.RESTORE_PENDING || updated!=a || !platform.exists(a)) arm+=updated
            } else state=event(state,a.id,if(error!=null) "BLOCKED" else "CANCELLED",error ?: "Schedule changed, disabled, or explicit future rebuild")
        }
        active.values.toList().forEach { a ->
            val r=valid(p,a,state);val c=r?.let {state.configs[it.itemId] ?: GentleConfig(it.version.gentleEnabled)}
            if(mode==AlarmSync.REBUILD || r==null || (c!=null && blocked(r,c)!=null)) {active.remove(a.id);platform.dismiss(a.id);state=event(state,a.id,"CANCELLED","Alert invalidated; routine history retained")}
        }
        if(master) p.state.items.values.forEach { item ->
            val c=state.configs[item.id] ?: GentleConfig(item.at(date)?.gentleEnabled ?: false)
            if(c.enabled && item.id !in p.state.deleted) p.upcomingOccurrences(item.id,date,3) { r ->
                r.scheduled && r.state==HistoryState.UNLOGGED && r.time!=null && state.closed[r.id]!=signature(r,c) &&
                    (r.id in keep || r.date.atTime(r.time).atZone(currentZone).toInstant().toEpochMilli()>current.toEpochMilli())
            }.forEach { r ->
                val day=r.date;val sig=signature(r,c)
                val at=Instant.ofEpochMilli(day.atTime(r.time).atZone(currentZone).toInstant().toEpochMilli())
                if(r.id !in keep) {
                    val error=blocked(r,c)
                    if(error!=null) state=event(state,r.id,"BLOCKED",error)
                    else {
                        val alert=LiveAlert(r.id,day,at,UUID.randomUUID().toString(),sig,c.precise,zoneId=currentZone.id,alarmClock=c.ringing)
                        keep[alert.id]=alert;arm+=alert
                    }
                }
            }
        }
        state=state.copy(configs=state.configs - p.state.deleted.keys,pending=keep,active=active);store.save(state)
        old.values.filter {keep[it.id]?.token!=it.token}.forEach(platform::cancel)
        arm.distinctBy {it.token}.forEach { a ->
            try {
                platform.arm(a)
                state=store.read()!!
                val status=if(a.test) "TEST_QUEUED" else if(a.snooze) "SNOOZE_QUEUED" else "QUEUED"
                val detail = if (a.alarmClock) "Ringing alarm scheduled for ${a.trigger}."
                    else "${if(a.precise) "Precise" else "Approximate"} reminder queued for ${a.trigger}; Android may delay delivery."
                store.save(event(state,a.id,status,detail))
            } catch(e:Exception) {
                state=store.read()!!
                state=event(state.copy(pending=state.pending-a.id),a.id,"SCHEDULE_BLOCKED",e.message ?: "Scheduling failed")
                store.save(state);platform.cancel(a)
            }
        }
    }
    fun handle(intent:Intent) {
        if(intent.action!=AndroidLivePlatform.ACTION)return
        val id=intent.getStringExtra("occurrence") ?: return;val token=intent.getStringExtra("token") ?: return;val op=intent.getStringExtra("operation") ?: return
        var state=initialize();val p=planner()
        val a=(if(op=="fire")state.pending[id] else state.active[id])?.takeIf {it.token==token} ?: return
        if(a.test) {
            if(op!="fire" || now()<a.trigger || !RhythmStore(kv).remindersEnabled || platform.blocked(FlexiblePlanner.parts(a.id).first)!=null)return
            state=event(state.copy(pending=state.pending-id),id,"TEST_DELIVERED","One-shot test, not a routine report");store.save(state);platform.cancel(a)
            runCatching {platform.show(a,"Daily Reminders test",false)}
            return
        }
        val r=valid(p,a,state) ?: run {sync();return}
        val c=state.configs[r.itemId] ?: GentleConfig(r.version.gentleEnabled)
        val error=blocked(r,c)
        if(error!=null) {store.save(event(state.copy(pending=state.pending-id,active=state.active-id),id,"BLOCKED",error));platform.cancel(a);platform.dismiss(id);return}
        if(op!="fire" && c.ringing && !RingingService.accepts(a,intent.getStringExtra("ring_session"))) return
        when(op) {
            "fire" -> {
                if(now()<a.trigger)return
                val late=java.time.Duration.between(a.trigger,now()).seconds>60
                state=event(state.copy(pending=state.pending-id,active=state.active+(id to a),closed=state.closed+(id to a.signature)),id,if(late)"DELIVERED_LATE" else "DELIVERED",if(c.ringing) "Ringing requested; no outcome recorded" else "Gentle notification; no outcome recorded")
                store.save(state);platform.cancel(a)
                try {if(c.ringing) platform.ring(a,r.title) else platform.show(a,r.title,late)} catch(e:Exception) {val current=store.read()!!;store.save(event(current.copy(active=current.active-id),id,"POST_BLOCKED",e.message ?: "Notification/service start failed"))}
                sync()
                CalendarSync.publishSoon(context)
            }
            "snooze5", "snooze10", "snooze_at" -> {
                val current = now()
                val until = when (op) {
                    "snooze5" -> current.plusSeconds(300)
                    "snooze10" -> current.plusSeconds(600)
                    else -> Instant.ofEpochMilli(intent.getLongExtra("snooze_until", 0))
                }
                if (until <= current || until > current.plusSeconds(7 * 24 * 3600L)) return
                val next = a.copy(trigger = Instant.ofEpochMilli(until.toEpochMilli()), token = UUID.randomUUID().toString(), snooze = true, alarmClock = c.ringing)
                // Keep the current alarm active if the replacement cannot be armed.
                try {
                    platform.arm(next)
                    val detail = if (c.ringing) "Snoozed until $until" else
                        "Snooze requested for $until. Doze can enforce nine-minute per-app spacing or delay delivery further."
                    store.save(event(state.copy(active = state.active - id, pending = state.pending + (id to next)),
                        id, "SNOOZE_QUEUED", detail))
                } catch (e: Exception) {
                    platform.cancel(next)
                    store.save(event(state, id, "SNOOZE_BLOCKED", e.message ?: "Snooze failed"))
                    if (op == "snooze_at") throw e
                    return
                }
                platform.dismiss(id)
            }
            "skip" -> {
                if(a.day<now().atZone(zone()).toLocalDate())p.report(id,Entry(Outcome.SKIPPED)) else p.skip(id,"Skipped from notification")
                store.save(event(state.copy(active=state.active-id,pending=state.pending-id),id,"SKIPPED_ALERT","Explicit occurrence skip; no Done or reading report"));platform.dismiss(id);platform.cancel(a);sync()
            }
            "done" -> {
                if (r.version.kind != ItemKind.ROUTINE) return
                p.report(id, Entry(Outcome.DONE, loggedAt = now()))
                store.save(state.copy(active = state.active - id, pending = state.pending - id))
                platform.dismiss(id)
                platform.cancel(a)
                sync()
                CalendarSync.publishSoon(context)
            }
            "dismiss","stop" -> {store.save(event(state.copy(active=state.active-id),id,"DISMISSED","No outcome or reading report"));platform.dismiss(id)}
        }
    }
    fun test(item:String) {
        initialize();require(planner().state.items.containsKey(item));check(RhythmStore(kv).remindersEnabled) {"Enable reminders first"};check(platform.blocked(item)==null) {"Notification permission/channel required"}
        val id=FlexiblePlanner.identity(item,now().atZone(zone()).toLocalDate(),"test")
        val a=LiveAlert(id,now().atZone(zone()).toLocalDate(),Instant.ofEpochMilli(now().plusSeconds(10).toEpochMilli()),UUID.randomUUID().toString(),"TEST",false,zoneId=zone().id,test=true)
        var state=initialize();val previous=state.pending[id];state=event(state.copy(pending=state.pending+(id to a)),id,"TEST_QUEUED","Requested about 10 seconds; Android may delay")
        store.save(state);previous?.let(platform::cancel)
        try {platform.arm(a)}catch(e:Exception){store.save(event(state.copy(pending=state.pending-id),id,"TEST_BLOCKED",e.message ?: "Test scheduling failed"));throw e}
    }
    fun audioHealth(id:String,status:String,detail:String) { store.save(event(initialize(),id,status,detail)) }
    fun ringing(a:LiveAlert):Pair<PlannedOccurrence,GentleConfig>? {
        val s=initialize();if(s.active[a.id]?.token!=a.token)return null
        val r=valid(planner(),a,s) ?: return null;val c=s.configs[r.itemId] ?: return null
        return if(c.ringing && c.precise && blocked(r,c)==null) r to c else null
    }
    fun endRinging(a:LiveAlert,status:String,detail:String) {
        val s=initialize();if(s.active[a.id]?.token==a.token)store.save(event(s.copy(active=s.active-a.id),a.id,status,detail))
    }
    fun recoverInterruptedRinging() {
        initialize().active.values.forEach { a -> if(config(FlexiblePlanner.parts(a.id).first).ringing && !RingingService.contains(a.token)) endRinging(a,"RING_INTERRUPTED","Prior process ended; sound was not restarted. No routine failure.") }
    }
    fun sessionAction(id:String,occurrenceToken:String,sessionToken:String,operation:String) {
        require(operation in setOf("stop","dismiss","snooze5","snooze10","skip","done"))
        val a=state().active[id]?.takeIf {it.token==occurrenceToken} ?: return
        handle(AndroidLivePlatform.intent(context,a,operation).putExtra("ring_session",sessionToken))
    }
    internal fun snooze(alert: LiveAlert, until: Instant, session: String? = RingingService.sessionToken(alert)) {
        val start = now()
        require(until > start && until <= start.plusSeconds(7 * 24 * 3600L)) { "Choose a time in the next seven days." }
        check(state().active[alert.id]?.token == alert.token) { "This alert has already changed." }
        handle(AndroidLivePlatform.intent(context, alert, "snooze_at").putExtra("snooze_until", until.toEpochMilli())
            .putExtra("ring_session", session))
        check(state().pending[alert.id]?.trigger == Instant.ofEpochMilli(until.toEpochMilli())) { "Snooze was unavailable." }
    }

    fun action(id:String,operation:String) {val a=state().active[id] ?: return;handle(AndroidLivePlatform.intent(context,a,operation).putExtra("ring_session",RingingService.sessionToken(a)))}
}
