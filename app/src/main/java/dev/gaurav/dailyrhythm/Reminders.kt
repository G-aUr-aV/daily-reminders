package dev.gaurav.dailyrhythm

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

interface AtomicKeyValue : KeyValue { fun putAll(values: Map<String,String?>) }

class Preferences(context: Context) : AtomicKeyValue, PlanSnapshots {
    private val app = context.applicationContext
    private val repository = synchronized(databases) { databases.getOrPut(app) { ReminderRepository(app) } }

    internal val changes get() = repository.changes

    internal fun hasLegacyRhythmData() = repository.read().values.keys.any { key ->
        key == RhythmStore.REMINDERS_KEY || key.startsWith("schedule.") || key.startsWith("alarm_v2.") ||
            key.startsWith("entry.") || key.startsWith("rounds.")
    }

    internal fun legacyReportDates() = repository.read().values.keys.asSequence().filter { it.startsWith("entry.") }
        .mapNotNull { runCatching { java.time.LocalDate.parse(it.removePrefix("entry.").substringBeforeLast('.')) }.getOrNull() }
        .toSet()

    internal fun portableValues(): Map<String, String> {
        val snapshot = repository.read()
        return snapshot.values.filterKeys(LocalBackup::portableKey) + listOfNotNull(
            snapshot.plan?.let { FlexiblePlanner.KEY to FlexCodec.encode(it) },
            snapshot.live?.let { LiveStore.KEY to LiveStore.encode(it) }).toMap()
    }

    override fun planSnapshot() = repository.read().plan
    internal fun liveSnapshot() = repository.read().live

    override fun get(key: String): String? {
        val data = repository.read()
        return when (key) {
            FlexiblePlanner.KEY -> data.plan?.let(FlexCodec::encode)
            LiveStore.KEY -> data.live?.let(LiveStore::encode)
            else -> data.values[key]
        }
    }

    override fun savePlan(plan: FlexState) = update { it.copy(plan = plan) }

    internal fun saveLive(state: LiveState, additional: Map<String, String?> = emptyMap(), plan: FlexState? = null) =
        update { data -> data.withValues(additional).let { it.copy(plan = plan ?: it.plan, live = state) } }

    override fun put(key: String, value: String?) = putAll(mapOf(key to value))

    override fun putAll(values: Map<String, String?>) = update { it.withValues(values) }

    private fun StoredReminders.withValues(values: Map<String, String?>): StoredReminders {
        val version = if (FlexiblePlanner.KEY in values) values[FlexiblePlanner.KEY]?.let(FlexCodec::decode) else plan
        val alerts = if (LiveStore.KEY in values) values[LiveStore.KEY]?.let(LiveStore::decode) else live
        val scalars = values - FlexiblePlanner.KEY - LiveStore.KEY
        val updates = scalars.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        return StoredReminders(version, alerts, this.values - scalars.keys + updates)
    }

    private fun update(block: (StoredReminders) -> StoredReminders) {
        if (!repository.update(block)) return
        // Scheduling feedback is separate from the durable transaction. A downstream failure
        // cannot make a committed database update look retryable.
        runCatching { CalendarSync.planChanged(app) }
        runCatching { DailyRemindersWidget.refresh(app) }
    }

    companion object {
        private val databases = java.util.WeakHashMap<Context, ReminderRepository>()
    }
}

fun Context.rhythmStore() = RhythmStore(Preferences(this))

/** Reconcile is a no-op for unchanged alarms; recovery reasserts the SAME stored occurrence.
 * Only explicit clock/boot/update rebuilds discard pending occurrences and choose future times. */
enum class AlarmSync { RECONCILE, RESTORE_PENDING, REBUILD }

private data class Occurrence(val time: LocalTime, val zone: String, val trigger: Instant, val token: String)

class ReminderScheduler(
    private val context: Context,
    private val now: () -> Instant = { Instant.now() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    companion object {
        const val CHANNEL = "daily_rhythm_v1"
        const val ACTION = "dev.gaurav.dailyrhythm.REMIND"
        const val TEST = "test"
        const val TEST_CODE = 1900
        const val TOKEN = "occurrence_token"
        const val TRIGGER = "occurrence_trigger"
    }
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = Preferences(context)
    private fun occurrence(id: String): Occurrence? = runCatching {
        val parts = requireNotNull(preferences.get("alarm_v2.$id")).split('|')
        require(parts.size == 4 && parts[3].isNotBlank())
        Occurrence(LocalTime.parse(parts[0]), ZoneId.of(parts[1]).id, Instant.ofEpochMilli(parts[2].toLong()), parts[3])
    }.getOrNull()
    private fun save(id: String, value: Occurrence?) {
        val key = "alarm_v2.$id"
        val encoded = value?.let { "${it.time}|${it.zone}|${it.trigger.toEpochMilli()}|${it.token}" }
        if (preferences.get(key) != encoded) preferences.put(key, encoded)
    }

    fun ensureChannel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Daily routine", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Gentle local routine reminders. Delivery can be delayed by Android."
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        })
    }
    fun availability(): String? {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return "Notification permission is off. Allow it in Android settings or when enabling reminders."
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "App notifications are blocked in Android settings."
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return "The Daily routine channel is off. Open channel settings to enable it."
        return null
    }
    private fun requestCode(id: String) = if (id == TEST) TEST_CODE else 1000 + Routines.all.indexOfFirst { it.id == id }.also { require(it >= 0) }
    private fun intent(id: String) = Intent(context, ReminderReceiver::class.java).setAction(ACTION).putExtra("routine_id", id)
    private fun existing(id: String): PendingIntent? = PendingIntent.getBroadcast(context, requestCode(id), intent(id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    private fun cancel(id: String) {
        existing(id)?.let { alarm.cancel(it); it.cancel() }
    }
    private fun arm(id: String, value: Occurrence) {
        // Do not UPDATE_CURRENT: a stale, already-dispatched intent must retain its old token.
        // This path is used only for new/changed/consumed occurrences or explicit recovery.
        cancel(id)
        val pending = PendingIntent.getBroadcast(context, requestCode(id),
            intent(id).putExtra(TOKEN, value.token).putExtra(TRIGGER, value.trigger.toEpochMilli()),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, value.trigger.toEpochMilli(), pending)
    }
    private fun next(id: String, time: LocalTime, current: Instant, currentZone: ZoneId) {
        val value = Occurrence(time, currentZone.id, CalendarLogic.nextTrigger(time, current, currentZone), UUID.randomUUID().toString())
        // Publish the new token before replacing the alarm: an old delivery is now stale.
        // If the process dies between these steps, activity-start recovery reasserts this record.
        save(id, value)
        arm(id, value)
    }
    fun sync(mode: AlarmSync = AlarmSync.RECONCILE) {
        ensureChannel()
        val store = context.rhythmStore()
        val current = now()
        val currentZone = zone()
        if (!store.remindersEnabled) cancelTest()
        Routines.all.forEach { routine ->
            val schedule = store.schedule(routine)
            if (!store.remindersEnabled || !schedule.enabled || schedule.time == null) {
                save(routine.id, null)
                cancel(routine.id)
            } else {
                val saved = occurrence(routine.id)
                val unchanged = saved != null && saved.time == schedule.time && saved.zone == currentZone.id
                if (mode != AlarmSync.REBUILD && unchanged) {
                    // A past-due occurrence may still be waiting in Android's inexact alarm queue.
                    // Never convert it to tomorrow merely because Today resumed or another item changed.
                    if (mode == AlarmSync.RESTORE_PENDING || existing(routine.id) == null) arm(routine.id, requireNotNull(saved))
                } else next(routine.id, schedule.time, current, currentZone)
            }
        }
    }
    /** Accept one matching due occurrence. Duplicate/stale/legacy payloads cannot advance the chain. */
    fun consume(delivered: Intent): Boolean {
        if (delivered.action != ACTION) return false
        val id = delivered.getStringExtra("routine_id") ?: return false
        val routine = Routines.byId(id) ?: return false
        val saved = occurrence(id) ?: return false
        if (delivered.getStringExtra(TOKEN) != saved.token || delivered.getLongExtra(TRIGGER, Long.MIN_VALUE) != saved.trigger.toEpochMilli()) return false
        val store = context.rhythmStore()
        val schedule = store.schedule(routine)
        val currentZone = zone()
        if (!store.remindersEnabled || !schedule.enabled || schedule.time != saved.time || saved.zone != currentZone.id) {
            sync() // Reconcile an edit/zone change that occurred before its normal sync finished.
            return false
        }
        val current = now()
        if (current < saved.trigger) return false
        // Schedule first: denial or a notification error must not break the daily chain.
        next(id, saved.time, current, currentZone)
        return true
    }
    fun test() {
        check(context.rhythmStore().remindersEnabled) { "Turn on Daily reminders before queuing a test." }
        check(availability() == null) { "Enable notification permission and the Daily routine channel first." }
        // Independent stable identity; tests never alter routine occurrence records.
        val pending = PendingIntent.getBroadcast(context, TEST_CODE, intent(TEST), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10_000, pending)
    }
    fun cancelTest() = cancel(TEST)
    fun post(id: String) {
        if (availability() != null) return
        val title = if (id == TEST) "Your test reminder" else Routines.byId(id)?.title ?: return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val hidden = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_rhythm)
            .setContentTitle("Daily Reminders").setContentText("A routine reminder").build()
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_rhythm)
            .setContentTitle(title).setContentText(if (id == TEST) "Local notifications are working. Delivery timing may vary." else "A gentle nudge. Open Daily Reminders to log how it went.")
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(hidden).setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        try { NotificationManagerCompat.from(context).notify(requestCode(id), notification) }
        catch (_: SecurityException) { /* Permission may have changed between the check and posting. */ }
    }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AndroidLivePlatform.ACTION) {
            runCatching { LiveReminders(context).handle(intent) }
            return
        }
        if (LiveReminders.migrated(context)) return
        if (intent.action != ReminderScheduler.ACTION) return
        val id = intent.getStringExtra("routine_id") ?: return
        val scheduler = ReminderScheduler(context)
        if (id == ReminderScheduler.TEST) {
            if (context.rhythmStore().remindersEnabled) scheduler.post(id)
            return
        }
        if (scheduler.consume(intent)) scheduler.post(id)
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            CalendarSync.planChanged(context)
            runCatching { DailyRemindersWidget.refresh(context) }
        }
        if (LiveReminders.migrated(context)) {
            if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
                val mode = if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) AlarmSync.RESTORE_PENDING else AlarmSync.REBUILD
                runCatching { LiveReminders(context).sync(mode) }
            }
            return
        }
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            if (Preferences(context).hasLegacyRhythmData()) {
                ReminderScheduler(context).apply { cancelTest(); sync(AlarmSync.REBUILD) }
            }
        }
    }
}
