package dev.gaurav.dailyrhythm

import android.Manifest
import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal data class CalendarTarget(val id: Long, val name: String, val account: String, val type: String) {
    fun isSameCalendar(other: CalendarTarget) = id == other.id && account == other.account && type == other.type
}

internal data class CalendarOccurrence(
    val id: String, val title: String, val start: Long, val end: Long, val zone: String, val allDay: Boolean
)

private fun calendarDate(start: Long, zone: String): LocalDate =
    java.time.Instant.ofEpochMilli(start).atZone(ZoneId.of(zone)).toLocalDate()

internal fun calendarOccurrences(planner: FlexiblePlanner, today: LocalDate, zone: ZoneId,
                                 from: LocalDate, existing: Set<String>,
                                 now: Instant = today.atStartOfDay(zone).toInstant()): Map<String, CalendarOccurrence> {
    val pastDays = (planner.state.reports.keys + planner.state.changes.keys + existing).mapNotNull { id ->
        val day = planner.state.changes[id]?.lastOrNull()?.date ?: FlexiblePlanner.parts(id).second
        day.takeIf { it < today && (it >= from || id in existing) }
    }
    val oneOffs = planner.state.items.values.filter { it.id !in planner.state.deleted }
        .flatMap { it.versions }.mapNotNull { it.rule.oneDate?.takeIf { date -> date >= today } }
    val moved = planner.state.changes.values.mapNotNull { it.lastOrNull()?.date?.takeIf { date -> date >= today } }
    val cutoff = today.plusDays(30)
    val days = ((0L until 30).map(today::plusDays) + oneOffs + moved + pastDays).distinct()
    val occurrences = mutableMapOf<String, CalendarOccurrence>()
    for (day in days) {
        for (row in planner.occurrences(day)) {
            if (row.state == HistoryState.NOT_SCHEDULED || day >= today && row.itemId in planner.state.deleted) continue
            if (day >= cutoff && row.version.rule.kind != RepeatKind.ONCE && row.id !in planner.state.changes) continue
            if (day < today && row.id !in existing && row.state == HistoryState.UNLOGGED) continue
            if (day >= today && !row.scheduled && !row.isLogged) continue
            val label = when (row.state) {
                HistoryState.DONE -> "Done"
                HistoryState.LATE -> "Done late"
                HistoryState.SKIPPED -> "Skipped"
                HistoryState.UNLOGGED -> when {
                    day < today -> "No record"
                    row.dueAt(zone) <= now -> "Pending"
                    else -> ""
                }
                else -> ""
            }
            val title = row.title + if (label.isEmpty()) "" else " · $label"
            val allDay = row.time == null
            val eventZone = if (allDay) ZoneOffset.UTC else zone
            val start = if (allDay) day.atStartOfDay(eventZone) else day.atTime(row.time).atZone(eventZone)
            val end = if (allDay) start.plusDays(1) else start.plusMinutes(row.version.durationMinutes.toLong())
            occurrences[row.id] = CalendarOccurrence(row.id, title, start.toInstant().toEpochMilli(),
                end.toInstant().toEpochMilli(), if (allDay) "UTC" else zone.id, allDay)
        }
    }
    return occurrences
}

internal class CalendarSync(private val context: Context, private val clock: () -> Instant = Instant::now) {
    companion object {
        private const val KEY = "calendar.connection.v1"
        private const val STATUS = "calendar.status.v1"
        private const val IMMEDIATE = 4210
        private const val DAILY = 4211
        internal const val CLOCK = 4212
        private val connectionLock = Any()
        private val reconciliationLock = Any()
        private val foreground = Executors.newSingleThreadScheduledExecutor { run -> Thread(run, "calendar-publish") }
        private var queued: Future<*>? = null

        /** Make foreground edits visible to the local provider without waiting for a background job. */
        fun publishSoon(context: Context) {
            val app = context.applicationContext
            if (!allowed(app) || Preferences(app).get(KEY) == null) return
            synchronized(connectionLock) {
                queued?.cancel(false)
                queued = foreground.schedule({ runCatching { CalendarSync(app).reconcile() } }, 200, TimeUnit.MILLISECONDS)
            }
        }

        val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        fun allowed(context: Context) = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        fun planChanged(context: Context) {
            if (allowed(context) && Preferences(context).get(KEY) != null) {
                runCatching { CalendarSync(context).request() }
            }
        }
    }

    private data class Connection(val owner: String, val target: CalendarTarget?, val from: LocalDate,
                                  val retired: List<CalendarTarget>)
    private data class Owned(val localId: Long, val marker: String, val legacy: Boolean,
                             val occurrence: CalendarOccurrence, val descriptionAfterMigration: String? = null,
                             val stripMarker: Boolean = false)
    private val storage = Preferences(context)
    private val provider = context.contentResolver
    private val jobs = context.getSystemService(JobScheduler::class.java)

    fun target() = connection()?.target
    fun status() = storage.get(STATUS)

    private fun JSONObject.target() = CalendarTarget(getLong("id"), getString("name"), getString("account"), getString("type"))
    private fun CalendarTarget.json() = JSONObject().put("id", id).put("name", name).put("account", account).put("type", type)
    private fun connection() = storage.get(KEY)?.let { raw ->
        val data = JSONObject(raw)
        val retired = data.getJSONArray("retired")
        Connection(data.getString("owner"), data.optJSONObject("target")?.target(), LocalDate.parse(data.getString("from")),
            (0 until retired.length()).map { retired.getJSONObject(it).target() })
    }

    private fun save(connection: Connection, status: String) {
        val value = JSONObject().put("owner", connection.owner).put("target", connection.target?.json())
            .put("from", connection.from.toString()).put("retired", JSONArray(connection.retired.map { it.json() }))
        storage.putAll(mapOf(KEY to value.toString(), STATUS to status))
    }

    fun select(target: CalendarTarget?) = synchronized(connectionLock) {
        val previous = connection()
        if (target == null && previous == null) return
        val retired = previous?.retired.orEmpty().toMutableList()
        previous?.target?.takeUnless { target != null && it.isSameCalendar(target) }?.let { left ->
            if (retired.none { it.isSameCalendar(left) }) retired.add(left)
        }
        retired.removeAll { target != null && it.isSameCalendar(target) }
        val from = if (target != null && previous?.target?.isSameCalendar(target) == true) previous.from else LocalDate.now()
        save(Connection(previous?.owner ?: UUID.randomUUID().toString(), target, from, retired),
            if (target == null) "Disconnecting…" else "Waiting to sync")
        request()
    }

    fun request() {
        val state = connection() ?: return
        if (!allowed(context)) return
        val service = ComponentName(context, CalendarSyncJob::class.java)
        if (state.target == null && state.retired.isEmpty()) {
            jobs.cancel(DAILY)
            jobs.cancel(IMMEDIATE)
            jobs.cancel(CLOCK)
            return
        }
        if (state.target != null && jobs.getPendingJob(DAILY) == null) {
            jobs.schedule(JobInfo.Builder(DAILY, service).setPersisted(true)
                .setPeriodic(TimeUnit.DAYS.toMillis(1)).build())
        }
        if (state.target == null) {
            jobs.cancel(DAILY)
            jobs.cancel(CLOCK)
        }
        jobs.schedule(JobInfo.Builder(IMMEDIATE, service).setPersisted(true).setMinimumLatency(750).build())
    }

    @SuppressLint("MissingPermission")
    fun calendars(): List<CalendarTarget> {
        if (!allowed(context)) return emptyList()
        val columns = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE)
        return requireNotNull(provider.query(Calendars.CONTENT_URI, columns,
            "${Calendars.CALENDAR_ACCESS_LEVEL}>=? AND ${Calendars.SYNC_EVENTS}=1",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), Calendars.CALENDAR_DISPLAY_NAME)).use { rows ->
            buildList {
                while (rows.moveToNext()) add(CalendarTarget(rows.getLong(0), rows.getString(1).orEmpty(),
                    rows.getString(2).orEmpty(), rows.getString(3).orEmpty()))
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun reconcile(onNext: ((Instant) -> Unit)? = null): Boolean = synchronized(reconciliationLock) {
        if (!allowed(context) || storage.planSnapshot() == null) return false
        val state = connection() ?: return false
        return try {
            val choices = calendars()
            val zone = ZoneId.systemDefault()
            val now = clock()
            val today = now.atZone(zone).toLocalDate()
            var nextTransition: Instant? = null
            val next = state.retired.filter { retired ->
                val current = choices.firstOrNull(retired::isSameCalendar) ?: return@filter true
                val remove = owned(current, state.owner).filter { calendarDate(it.occurrence.start, it.occurrence.zone) >= today }
                apply(remove.map { delete(current, it) })
                false
            }
            val target = state.target?.let { wanted -> choices.firstOrNull(wanted::isSameCalendar) }
            val status = when {
                state.target == null -> if (next.isEmpty()) "Off" else "Old calendar unavailable"
                target == null -> "Calendar unavailable"
                else -> {
                    val planner = FlexiblePlanner(storage, persistMigration = false) { today }
                    val events = owned(target, state.owner)
                    val desired = calendarOccurrences(planner, today, zone, state.from,
                        events.map { it.occurrence.id }.toSet(), now)
                    val changes = mutableListOf<ContentProviderOperation>()
                    val seen = mutableSetOf<String>()
                    for (event in events) {
                        val old = event.occurrence
                        val nextEvent = desired[old.id]
                        if (!seen.add(old.id) || nextEvent == null && calendarDate(old.start, old.zone) >= today) {
                            changes += delete(target, event)
                        } else if (event.legacy || event.stripMarker || nextEvent != null && nextEvent != old) {
                            changes += write(target, nextEvent ?: old, state.owner, event)
                        }
                    }
                    for (event in desired.values) if (event.id !in seen) changes += write(target, event, state.owner)
                    apply(changes)
                    nextTransition = planner.occurrences(today).asSequence()
                        .filter { it.scheduled && it.state == HistoryState.UNLOGGED }
                        .map { it.dueAt(zone) }.filter { it > now }.minOrNull()
                        ?: today.plusDays(1).atStartOfDay(zone).toInstant()
                    "Saved to calendar on this phone"
                }
            }
            synchronized(connectionLock) {
                if (connection() == state && !Thread.currentThread().isInterrupted) {
                    save(state.copy(retired = next), status)
                    nextTransition?.let { if (onNext == null) scheduleClock(it) else onNext(it) }
                }
            }
            state.target == null && next.isNotEmpty()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            true
        } catch (_: Exception) {
            synchronized(connectionLock) {
                if (connection() == state && !Thread.currentThread().isInterrupted)
                    runCatching { storage.put(STATUS, "Sync paused · retrying") }
            }
            true
        }
    }

    internal fun scheduleClock(at: Instant) {
        if (connection()?.target == null || !allowed(context)) return
        val timestamp = at.toEpochMilli()
        if (jobs.getPendingJob(CLOCK)?.extras?.getLong("transition") == timestamp) return
        val delay = (timestamp - System.currentTimeMillis()).coerceAtLeast(0L)
        val service = ComponentName(context, CalendarSyncJob::class.java)
        val extras = android.os.PersistableBundle().apply { putLong("transition", timestamp) }
        jobs.schedule(JobInfo.Builder(CLOCK, service).setPersisted(true).setExtras(extras).setMinimumLatency(delay).build())
    }

    private fun calendarUid(owner: String, id: String) = "$owner/$id@dev.gaurav.dailyrhythm"

    @SuppressLint("MissingPermission")
    private fun owned(calendar: CalendarTarget, owner: String): List<Owned> {
        val columns = arrayOf(Events._ID, Events.UID_2445, Events.DESCRIPTION, Events.TITLE, Events.DTSTART,
            Events.DTEND, Events.EVENT_TIMEZONE, Events.ALL_DAY)
        val legacyPrefix = "Daily Rhythm ·"
        val uidPrefix = "$owner/"
        val suffix = "@dev.gaurav.dailyrhythm"
        val selection = "${Events.CALENDAR_ID}=? AND (${Events.UID_2445} LIKE ? OR ${Events.DESCRIPTION} LIKE ?) AND ${Events.DELETED}=0"
        return requireNotNull(provider.query(Events.CONTENT_URI, columns, selection,
            arrayOf(calendar.id.toString(), "$uidPrefix%$suffix", "$legacyPrefix%$owner/%"), Events._ID)).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val uid = rows.getString(1).orEmpty()
                    val description = rows.getString(2).orEmpty()
                    val current = uid.startsWith(uidPrefix) && uid.endsWith(suffix)
                    val payload = description.takeIf { it.startsWith(legacyPrefix) }
                        ?.removePrefix(legacyPrefix)?.takeIf { it.firstOrNull()?.isWhitespace() == true }?.trimStart()
                        ?.takeIf { it.startsWith(uidPrefix) }
                    val legacyId = payload?.lineSequence()?.first()?.trimEnd()?.removePrefix(uidPrefix)
                    val id = when {
                        current -> uid.removePrefix(uidPrefix).removeSuffix(suffix)
                        legacyId != null -> legacyId
                        else -> continue
                    }
                    if (runCatching { FlexiblePlanner.parts(id) }.isFailure) continue
                    add(Owned(rows.getLong(0), if (current) uid else description, !current,
                        CalendarOccurrence(id, rows.getString(3).orEmpty(), rows.getLong(4), rows.getLong(5),
                            rows.getString(6) ?: "UTC", rows.getInt(7) != 0),
                        payload?.substringAfter('\n', "")?.trim()?.takeIf(String::isNotEmpty),
                        stripMarker = legacyId == id))
                }
            }
        }
    }

    private fun ownership(event: Owned): String {
        val column = if (event.legacy) Events.DESCRIPTION else Events.UID_2445
        return "${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND $column=?"
    }

    private fun ownershipArgs(target: CalendarTarget, event: Owned) =
        arrayOf(event.localId.toString(), target.id.toString(), event.marker)

    private fun delete(target: CalendarTarget, event: Owned) =
        ContentProviderOperation.newDelete(Events.CONTENT_URI)
            .withSelection(ownership(event), ownershipArgs(target, event)).build()

    private fun write(target: CalendarTarget, event: CalendarOccurrence, owner: String, previous: Owned? = null): ContentProviderOperation {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, target.id)
            put(Events.TITLE, event.title)
            put(Events.UID_2445, calendarUid(owner, event.id))
            if (previous?.stripMarker == true) put(Events.DESCRIPTION, previous.descriptionAfterMigration)
            put(Events.DTSTART, event.start)
            put(Events.DTEND, event.end)
            put(Events.EVENT_TIMEZONE, event.zone)
            put(Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(Events.HAS_ALARM, 0)
            put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
        }
        val operation = if (previous == null) ContentProviderOperation.newInsert(Events.CONTENT_URI)
        else ContentProviderOperation.newUpdate(Events.CONTENT_URI)
            .withSelection(ownership(previous), ownershipArgs(target, previous))
        return operation.withValues(values).build()
    }

    private fun apply(changes: List<ContentProviderOperation>) {
        for (batch in changes.chunked(75)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            provider.applyBatch(android.provider.CalendarContract.AUTHORITY, ArrayList(batch))
        }
    }
}

class CalendarSyncJob : JobService() {
    private val worker = Executors.newSingleThreadExecutor()
    private data class Run(val params: JobParameters, val work: Future<*>)
    private val jobs = mutableMapOf<Int, Run>()

    override fun onStartJob(params: JobParameters): Boolean {
        val main = android.os.Handler(mainLooper)
        jobs.remove(params.jobId)?.work?.cancel(true)
        val work = worker.submit {
            val sync = CalendarSync(applicationContext)
            var transition: Instant? = null
            val retry = runCatching {
                if (params.jobId == CalendarSync.CLOCK) sync.reconcile { transition = it } else sync.reconcile()
            }.getOrDefault(true)
            main.post {
                if (jobs[params.jobId]?.params === params) {
                    jobs.remove(params.jobId)
                    jobFinished(params, retry)
                    transition?.let(sync::scheduleClock)
                }
            }
        }
        jobs[params.jobId] = Run(params, work)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.work?.cancel(true)
        return true
    }

    override fun onDestroy() {
        worker.shutdownNow()
        jobs.clear()
        super.onDestroy()
    }
}
