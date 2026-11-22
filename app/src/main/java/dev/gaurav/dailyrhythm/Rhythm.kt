package dev.gaurav.dailyrhythm

import java.time.*

/** All calendar logic stays independent of Android so real JVM tests can exercise it. */
data class Routine(val id: String, val title: String, val note: String, val defaultTime: LocalTime?)
object Routines {
    val all = listOf(
        Routine("wake", "Wake up", "A gentle beginning", LocalTime.of(6, 0)),
        Routine("exercise", "Morning exercise", "Make a little room to move", null),
        Routine("pack", "Start packing", "Gather what you need", LocalTime.of(7, 0)),
        Routine("depart", "Leave for office", "The day opens up", LocalTime.of(7, 30)),
        Routine("shuttle", "Take office shuttle", "Your ride, your time", null),
        Routine("bed", "Bedtime", "Reported bedtime, not measured sleep", LocalTime.of(23, 0))
    )
    fun byId(id: String) = all.firstOrNull { it.id == id }
}
enum class Outcome { DONE, LATE, SKIPPED, UNLOGGED }
data class Entry(val outcome: Outcome = Outcome.UNLOGGED, val actual: LocalTime? = null, val loggedAt: Instant? = null)
data class Schedule(val time: LocalTime?, val enabled: Boolean)
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}
class RhythmStore(private val kv: KeyValue) {
    companion object { internal const val REMINDERS_KEY = "reminders" }
    var remindersEnabled: Boolean
        get() = kv.get(REMINDERS_KEY) == "true"
        set(value) = kv.put(REMINDERS_KEY, value.toString())
    fun schedule(routine: Routine): Schedule {
        val raw = kv.get("schedule.${routine.id}") ?: return Schedule(routine.defaultTime, true)
        val parts = raw.split('|')
        return Schedule(parseTime(parts.getOrNull(0)), parts.getOrNull(1) == "true")
    }
    fun setSchedule(id: String, schedule: Schedule) {
        requireNotNull(Routines.byId(id))
        kv.put("schedule.$id", "${schedule.time ?: ""}|${schedule.enabled}")
    }
    fun entry(date: LocalDate, id: String): Entry {
        val parts = kv.get("entry.$date.$id")?.split('|') ?: return Entry()
        val status = runCatching { Outcome.valueOf(parts[0]) }.getOrDefault(Outcome.UNLOGGED)
        return if (status == Outcome.UNLOGGED) Entry() else Entry(status, parseTime(parts.getOrNull(1)))
    }
    fun setEntry(date: LocalDate, id: String, entry: Entry) {
        requireNotNull(Routines.byId(id))
        val actual = if (entry.outcome in listOf(Outcome.UNLOGGED, Outcome.SKIPPED)) null else entry.actual
        kv.put("entry.$date.$id", if (entry.outcome == Outcome.UNLOGGED) null else "${entry.outcome}|${actual ?: ""}")
    }
    fun rounds(date: LocalDate): Int = kv.get("rounds.$date")?.toIntOrNull()?.takeIf { it in 0..3 } ?: 0
    fun setRounds(date: LocalDate, value: Int) { require(value in 0..3); kv.put("rounds.$date", value.toString()) }
    private fun parseTime(raw: String?): LocalTime? = runCatching { LocalTime.parse(raw) }.getOrNull()
}
object CalendarLogic {
    fun today(now: Instant, zone: ZoneId): LocalDate = now.atZone(zone).toLocalDate()
    /** A historical selection remains pinned; a Today selection follows midnight and timezone changes. */
    fun selectedDate(pinnedDate: LocalDate?, now: Instant, zone: ZoneId) = pinnedDate ?: today(now, zone)
    /** Strictly future, recalculated in local wall time (not an added 24 hours).
     * java.time moves a DST gap forward; on overlaps the earlier offset is used once per day.
     */
    fun nextTrigger(time: LocalTime, now: Instant, zone: ZoneId): Instant {
        val date = today(now, zone)
        val candidate = date.atTime(time).atZone(zone).toInstant()
        return if (candidate > now) candidate else date.plusDays(1).atTime(time).atZone(zone).toInstant()
    }
    fun sevenDays(end: LocalDate): List<LocalDate> = (6 downTo 0).map { end.minusDays(it.toLong()) }
}
data class DaySummary(val date: LocalDate, val done: Int, val late: Int, val skipped: Int, val unlogged: Int, val rounds: Int) {
    val logged get() = done + late + skipped
    val completed get() = done + late
    val slots get() = logged + unlogged
}
data class WeekSummary(val days: List<DaySummary>) {
    val logged get() = days.sumOf { it.logged }
    val completed get() = days.sumOf { it.completed }
    val unlogged get() = days.sumOf { it.unlogged }
    val skipped get() = days.sumOf { it.skipped }
    val slots get() = days.sumOf { it.slots }
    val completionOfLogged: Double? get() = if (logged == 0) null else completed.toDouble() / logged
}
fun summarize(store: RhythmStore, end: LocalDate): WeekSummary = WeekSummary(CalendarLogic.sevenDays(end).map { date ->
    val entries = Routines.all.map { store.entry(date, it.id).outcome }
    DaySummary(date, entries.count { it == Outcome.DONE }, entries.count { it == Outcome.LATE }, entries.count { it == Outcome.SKIPPED }, entries.count { it == Outcome.UNLOGGED }, store.rounds(date))
})
