package dev.gaurav.dailyrhythm

import java.time.*
import java.util.UUID

/** Planning checkpoint only. No Android alarms, audio, permissions or legacy writes here. */
enum class RepeatKind { DAILY, WEEKDAYS, MONTHLY, ONCE, OFFICE, HOME, LEAVE, WEEKEND }

enum class DayType(val label: String, val repeat: RepeatKind) {
    OFFICE("At the office", RepeatKind.OFFICE),
    HOME("Work from home", RepeatKind.HOME),
    LEAVE("Leave", RepeatKind.LEAVE),
    WEEKEND("Weekend", RepeatKind.WEEKEND)
}
enum class MissingDate { SKIP, MONTH_END }
data class RepeatRule(
    val kind: RepeatKind,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthDays: Set<Int> = emptySet(),
    val lastDay: Boolean = false,
    val missing: MissingDate? = null,
    val oneDate: LocalDate? = null
) {
    init {
        require(kind != RepeatKind.WEEKDAYS || weekdays.isNotEmpty()) { "Choose at least one weekday." }
        require(monthDays.all { it in 1..31 })
        require(kind != RepeatKind.MONTHLY || monthDays.isNotEmpty() || lastDay) { "Choose monthly dates or Last day." }
        require(kind != RepeatKind.MONTHLY || monthDays.none { it >= 29 } || missing != null) { "Choose a missing-date policy for the 29th–31st." }
        require(kind != RepeatKind.ONCE || oneDate != null) { "Choose the one-off date." }
    }
    fun monthDates(month: YearMonth): Set<LocalDate> = buildSet {
        monthDays.forEach { n ->
            if (n <= month.lengthOfMonth()) add(month.atDay(n))
            else if (missing == MissingDate.MONTH_END) add(month.atEndOfMonth())
        }
        if (lastDay) add(month.atEndOfMonth())
    }
    fun matches(date: LocalDate, office: Boolean, dayType: DayType? = null): Boolean = when (kind) {
        RepeatKind.DAILY -> true
        RepeatKind.WEEKDAYS -> date.dayOfWeek in weekdays
        RepeatKind.MONTHLY -> date in monthDates(YearMonth.from(date))
        RepeatKind.ONCE -> date == oneDate
        RepeatKind.OFFICE -> dayType?.let { it == DayType.OFFICE } ?: office
        RepeatKind.HOME -> dayType == DayType.HOME
        RepeatKind.LEAVE -> dayType == DayType.LEAVE
        RepeatKind.WEEKEND -> date.dayOfWeek.value > 5
    }
}
enum class ItemKind { ROUTINE, RITUAL }

enum class ReminderCategory(val label: String) {
    WORK("Work"), HEALTH("Health"), PERSONAL("Personal"), FOCUS("Focus"), URGENT("Urgent"),
    WAKING_UP("Waking up"), SLEEP("Sleep")
}

internal enum class ReminderSample(
    val title: String,
    val time: LocalTime,
    val repeat: RepeatKind,
    val steps: List<String> = emptyList()
) {
    OFFICE("Pack your bag", LocalTime.of(7, 0), RepeatKind.OFFICE),
    HOME("Plan your workday", LocalTime.of(9, 0), RepeatKind.HOME),
    LEAVE("Water the plants", LocalTime.of(10, 0), RepeatKind.LEAVE),
    WEEKEND("Go for a walk", LocalTime.of(8, 0), RepeatKind.WEEKEND),
    MORNING("Morning reset", LocalTime.of(8, 0), RepeatKind.DAILY, listOf(
        "Take a few slow breaths.",
        "Choose one priority for today.",
        "Write down the first step."
    ));

    fun draft(date: LocalDate) = PlanVersion(date, title,
        kind = if (steps.isEmpty()) ItemKind.ROUTINE else ItemKind.RITUAL,
        time = time, rule = RepeatRule(repeat), content = steps)
}
data class PlanVersion(
    val from: LocalDate, val title: String, val note: String = "", val kind: ItemKind = ItemKind.ROUTINE,
    val time: LocalTime? = null, val rule: RepeatRule = RepeatRule(RepeatKind.DAILY),
    val archived: Boolean = false, val content: List<String> = emptyList(), val target: Int = 1,
    val gentleEnabled: Boolean = false, val legacyUnknownSchedule: Boolean = false,
    val durationMinutes: Int = 15,
    val category: ReminderCategory? = null,
    val checklist: List<String> = emptyList()
) {
    init {
        require(title.isNotBlank() && title.length <= 128)
        require(note.length <= 1024 && target in 1..10)
        require(durationMinutes in 1..1440) { "Duration must be 1 to 1440 minutes." }
        require(content.size <= 64 && content.all { it.length <= 4096 })
        require(checklist.size <= 20 && checklist.distinct().size == checklist.size &&
            checklist.all { it.isNotBlank() && it.length <= 120 } &&
            (kind == ItemKind.ROUTINE || checklist.isEmpty())) { "Use up to 20 unique steps of 120 characters each." }
        require(kind != ItemKind.RITUAL || content.any { it.isNotBlank() }) { "Add ritual text or steps." }
    }
}
data class PlanItem(val id: String, val created: LocalDate, val versions: List<PlanVersion>) {
    fun at(date: LocalDate): PlanVersion? = versions.filter { it.from <= date }.maxByOrNull { it.from }
}
data class OfficeVersion(val from: LocalDate, val weekdays: Set<DayOfWeek>)
enum class OfficeChoice { ON, OFF, USUAL, LEAVE }
data class OfficeChange(val choice: OfficeChoice, val reason: String)
enum class ExceptionKind { SKIP, MOVE, EXTRA }
data class ItemChange(val kind: ExceptionKind, val date: LocalDate, val time: LocalTime?, val reason: String)
data class Report(val entry: Entry, val titleAtReport: String)
data class RitualDay(val title: String, val lines: List<String>, val target: Int, val rounds: Int)
data class FlexState(
    val migratedOn: LocalDate,
    val items: Map<String, PlanItem>, val office: List<OfficeVersion>,
    val officeChanges: Map<LocalDate, List<OfficeChange>> = emptyMap(),
    val changes: Map<String, List<ItemChange>> = emptyMap(),
    val reports: Map<String, Report> = emptyMap(), val rituals: Map<String, RitualDay> = emptyMap(),
    val deleted: Map<String, LocalDate> = emptyMap(),
    val checkedSteps: Map<String, Set<String>> = emptyMap()
)
enum class HistoryState { DONE, LATE, SKIPPED, UNLOGGED, NOT_SCHEDULED }
data class PlannedOccurrence(
    val id: String, val itemId: String, val anchor: LocalDate, val date: LocalDate,
    val version: PlanVersion, val title: String, val time: LocalTime?, val scheduled: Boolean,
    val state: HistoryState, val actual: LocalTime?, val explanation: String, val extra: Boolean
)

internal val PlannedOccurrence.isLogged: Boolean
    get() = state == HistoryState.DONE || state == HistoryState.LATE || state == HistoryState.SKIPPED

internal fun PlannedOccurrence.visibleOnAgenda(deletedOn: LocalDate?): Boolean =
    scheduled || isLogged && (deletedOn == null || date < deletedOn)

internal fun PlannedOccurrence.dueAt(zone: ZoneId): Instant =
    date.atTime(time ?: LocalTime.MIDNIGHT).atZone(zone).toInstant()

internal data class HabitWeek(val planned: Int, val done: Int, val skipped: Int, val unrecorded: Int, val pending: Int)

internal fun habitWeek(rows: List<PlannedOccurrence>, now: Instant, zone: ZoneId): HabitWeek {
    val tracked = rows.filter { it.state != HistoryState.NOT_SCHEDULED && !it.version.legacyUnknownSchedule }
    val done = tracked.count { it.state in setOf(HistoryState.DONE, HistoryState.LATE) }
    val skipped = tracked.count { it.state == HistoryState.SKIPPED }
    val today = now.atZone(zone).toLocalDate()
    val unrecorded = tracked.count { it.state == HistoryState.UNLOGGED && it.date < today }
    return HabitWeek(tracked.size, done, skipped, unrecorded, tracked.size - done - skipped - unrecorded)
}
data class ChangePreview(val date: LocalDate, val officeBefore: Boolean, val officeAfter: Boolean,
    val removed: List<String>, val added: List<String>, val existingExceptions: List<String>, val preservedReports: List<String>)
data class GentlePlanDelta(val retained: Map<String, Instant>, val cancelled: Set<String>, val added: Map<String, Instant>, val suppressedPast: Set<String>)

internal interface PlanSnapshots {
    fun planSnapshot(): FlexState?
    fun savePlan(plan: FlexState)
}

class FlexiblePlanner(private val kv: KeyValue, private val persistMigration: Boolean = true,
                      private val includeLegacyRoutines: Boolean = true, private val today: () -> LocalDate) {
    companion object {
        const val KEY = "flex.plan.v1"
        const val AFFIRMATIONS = "affirmations"
        fun identity(item: String, date: LocalDate, suffix: String = "base") = "$item@$date#$suffix"
        fun parts(id: String): Triple<String, LocalDate, String> {
            val at = id.indexOf('@'); val hash = id.indexOf('#', at + 1)
            require(at > 0 && hash > at)
            return Triple(id.substring(0, at), LocalDate.parse(id.substring(at + 1, hash)), id.substring(hash + 1))
        }
    }
    private val legacy = RhythmStore(kv)
    private fun loadPlan() = if (kv is PlanSnapshots) kv.planSnapshot() else kv.get(KEY)?.let(FlexCodec::decode)
    private fun persist(plan: FlexState) {
        if (kv is PlanSnapshots) kv.savePlan(plan) else kv.put(KEY, FlexCodec.encode(plan))
    }
    var state: FlexState = loadPlan() ?: migrate().also { if (persistMigration) persist(it) }
        private set
    fun refresh() { state = requireNotNull(loadPlan()) { "Planning state missing; data was not reset." } }
    private fun commit(next: FlexState) { persist(next); state = next }
    private fun future(date: LocalDate) { require(date >= today()) { "Plan changes start today or later; historical reports remain editable." } }
    private fun migrate(): FlexState {
        val date = today()
        if (!includeLegacyRoutines) {
            return FlexState(date, emptyMap(), listOf(OfficeVersion(LocalDate.MIN, emptySet())))
        }
        val office = listOf(OfficeVersion(LocalDate.MIN, DayOfWeek.entries.toSet()),
            OfficeVersion(date, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)))
        val items = Routines.all.associate { old ->
            val schedule = legacy.schedule(old)
            val linked = old.id in setOf("pack", "depart", "shuttle")
            old.id to PlanItem(old.id, LocalDate.MIN, listOf(
                PlanVersion(LocalDate.MIN, old.title, old.note, legacyUnknownSchedule = true),
                PlanVersion(date, old.title, old.note, time = schedule.time,
                    rule = RepeatRule(if (linked) RepeatKind.OFFICE else RepeatKind.DAILY), gentleEnabled = schedule.enabled)
            ))
        }.toMutableMap()
        items[AFFIRMATIONS] = PlanItem(AFFIRMATIONS, LocalDate.MIN, listOf(
            PlanVersion(LocalDate.MIN, "Morning ritual", kind = ItemKind.RITUAL, content = ReminderSample.MORNING.steps,
                target = 3, legacyUnknownSchedule = true),
            PlanVersion(date, "Morning ritual", kind = ItemKind.RITUAL, content = ReminderSample.MORNING.steps, target = 3)
        ))
        return FlexState(date, items, office)
    }
    fun office(date: LocalDate, choices: Map<LocalDate, List<OfficeChange>> = state.officeChanges): Boolean {
        return when (choices[date]?.lastOrNull()?.choice) {
            OfficeChoice.ON -> true
            OfficeChoice.OFF, OfficeChoice.LEAVE -> false
            else -> date.dayOfWeek in (state.office.filter { it.from <= date }.maxByOrNull { it.from }?.weekdays ?: emptySet())
        }
    }
    fun dayType(date: LocalDate, choices: Map<LocalDate, List<OfficeChange>> = state.officeChanges): DayType = when {
        date.dayOfWeek.value > 5 -> DayType.WEEKEND
        choices[date]?.lastOrNull()?.choice == OfficeChoice.LEAVE -> DayType.LEAVE
        office(date, choices) -> DayType.OFFICE
        else -> DayType.HOME
    }

    fun setDayType(date: LocalDate, type: DayType) {
        require(date.dayOfWeek.value <= 5 && type != DayType.WEEKEND) { "Weekend is set automatically." }
        val choice = when (type) {
            DayType.OFFICE -> OfficeChoice.ON
            DayType.HOME -> OfficeChoice.OFF
            DayType.LEAVE -> OfficeChoice.LEAVE
            DayType.WEEKEND -> error("Weekend is set automatically.")
        }
        changeOffice(date, date, choice)
    }
    fun editOffice(weekdays: Set<DayOfWeek>, effective: LocalDate = today()) {
        refresh()
        future(effective)
        commit(state.copy(office = state.office.filterNot { it.from == effective } + OfficeVersion(effective, weekdays)))
    }
    fun create(version: PlanVersion): String {
        refresh()
        future(version.from)
        val id = UUID.randomUUID().toString()
        commit(state.copy(items = state.items + (id to PlanItem(id, version.from, listOf(version)))))
        return id
    }
    fun edit(id: String, version: PlanVersion) {
        refresh()
        future(version.from)
        val item = state.items.getValue(id)
        require(id !in state.deleted) { "This reminder was deleted." }
        require(version.from >= item.created)
        commit(state.copy(items = state.items + (id to item.copy(versions = item.versions.filterNot { it.from == version.from } + version))))
    }
    fun archive(id: String, effective: LocalDate = today()) = setArchived(id, effective, true)
    fun restore(id: String, effective: LocalDate = today()) = setArchived(id, effective, false)

    // The outcome is the source of truth for automatic archiving. Clearing it
    // reopens the one-time reminder, while a manual archive remains in effect.
    internal fun isArchived(item: PlanItem, version: PlanVersion): Boolean =
        version.archived || isCompletedOneTime(item, version)

    internal fun isArchived(itemId: String, date: LocalDate): Boolean {
        val item = state.items[itemId] ?: return false
        return item.at(date)?.let { isArchived(item, it) } == true
    }

    internal fun isCompletedOneTime(item: PlanItem, version: PlanVersion): Boolean {
        if (version.rule.kind != RepeatKind.ONCE) return false
        val outcome = entry(identity(item.id, requireNotNull(version.rule.oneDate))).outcome
        return outcome == Outcome.DONE || outcome == Outcome.LATE
    }

    private fun setArchived(id: String, effective: LocalDate, archived: Boolean) {
        refresh()
        future(effective)
        val item = state.items.getValue(id)
        require(id !in state.deleted) { "This reminder was deleted." }
        val current = requireNotNull(item.at(effective))
        val versions = item.versions.filter { it.from != effective }.map {
            if (it.from > effective) it.copy(archived = archived) else it
        } + current.copy(from = effective, archived = archived)
        commit(state.copy(items = state.items + (id to item.copy(versions = versions))))
    }
    fun delete(id: String) {
        refresh()
        require(id in state.items)
        if (id !in state.deleted) commit(state.copy(deleted = state.deleted + (id to today())))
    }
    fun changeOffice(start: LocalDate, end: LocalDate, choice: OfficeChoice, reason: String = "", weekdaysOnly: Boolean = false) {
        refresh()
        future(start); require(end >= start && end.toEpochDay() - start.toEpochDay() <= 365); require(reason.length <= 256)
        val changes = state.officeChanges.toMutableMap()
        start.datesThrough(end).filter { !weekdaysOnly || it.dayOfWeek.value <= 5 }.forEach { date -> changes[date] = changes[date].orEmpty() + OfficeChange(choice, reason) }
        commit(state.copy(officeChanges = changes))
    }
    fun undoOffice(date: LocalDate) {
        refresh()
        future(date)
        val previous = state.officeChanges[date].orEmpty().dropLast(1)
        commit(state.copy(officeChanges = if (previous.isEmpty()) state.officeChanges - date else state.officeChanges + (date to previous)))
    }
    fun change(id: String, kind: ExceptionKind, targetDate: LocalDate, time: LocalTime?, reason: String = "") {
        refresh()
        val (item, anchor, suffix) = parts(id)
        require(item in state.items && item !in state.deleted); future(state.changes[id]?.lastOrNull()?.date ?: anchor); future(targetDate); require(reason.length <= 256)
        require(kind != ExceptionKind.EXTRA || suffix != "base") { "Use addExtra for a distinct occurrence." }
        require(kind != ExceptionKind.MOVE || targetDate >= state.items.getValue(item).created) { "Choose a date on or after this item's start date (${state.items.getValue(item).created})." }
        require(kind != ExceptionKind.MOVE || targetDate != anchor || time != state.items.getValue(item).at(anchor)?.time) { "Choose a different date or time." }
        commit(state.copy(changes = state.changes + (id to (state.changes[id].orEmpty() + ItemChange(kind, targetDate, time, reason)))))
    }
    fun skip(id: String, reason: String = "") { refresh(); val p = parts(id); val date = state.changes[id]?.lastOrNull()?.date ?: p.second; change(id, ExceptionKind.SKIP, date, null, reason) }
    fun move(id: String, date: LocalDate, time: LocalTime?) = change(id, ExceptionKind.MOVE, date, time)
    fun addExtra(item: String, date: LocalDate, time: LocalTime?): String {
        refresh()
        future(date); require(state.items.containsKey(item))
        val record = state.items.getValue(item)
        require(record.at(date)?.let { !isArchived(record, it) } == true) { "Unarchive this item before adding an occurrence." }
        val id = identity(item, date, UUID.randomUUID().toString())
        change(id, ExceptionKind.EXTRA, date, time, "Added for this date")
        return id
    }
    fun undoItem(id: String) {
        refresh()
        val stack = state.changes[id].orEmpty()
        future(stack.lastOrNull()?.date ?: parts(id).second)
        val previous = stack.dropLast(1)
        future(previous.lastOrNull()?.date ?: parts(id).second)
        // Removing an extra's final placement would make its saved history unreachable.
        // Other Undo operations remain available, including undoing a Skip on that extra.
        require(parts(id).third == "base" || previous.isNotEmpty() || (id !in state.reports && id !in state.rituals && id !in state.checkedSteps)) {
            "This extra has saved history or ritual progress. Keep it and use Skip instead of undoing its creation."
        }
        commit(state.copy(changes = if (previous.isEmpty()) state.changes - id else state.changes + (id to previous)))
    }
    fun entry(id: String): Entry {
        state.reports[id]?.let { return it.entry }
        val (item, anchor, suffix) = parts(id)
        return if (suffix == "base" && Routines.byId(item) != null) legacy.entry(anchor, item) else Entry()
    }
    fun report(id: String, entry: Entry) {
        refresh()
        val (item, anchor) = parts(id)
        val version = requireNotNull(state.items.getValue(item).at(anchor))
        require(entry.outcome != Outcome.LATE || entry.actual != null) { "A new Late report needs an actual local time. Legacy reports remain unchanged." }
        val clean = when (entry.outcome) {
            Outcome.UNLOGGED -> Entry()
            Outcome.SKIPPED -> entry.copy(actual = null)
            else -> entry
        }
        // A tombstone UNLOGGED report intentionally masks, but never deletes, a legacy report.
        commit(state.copy(
            reports = state.reports + (id to Report(clean, state.reports[id]?.titleAtReport
                ?: Routines.byId(item)?.takeIf { legacy.entry(anchor, item).outcome != Outcome.UNLOGGED }?.title ?: version.title)),
            checkedSteps = if (clean.outcome == Outcome.UNLOGGED) state.checkedSteps - id else state.checkedSteps
        ))
    }

    fun setStep(id: String, step: String, checked: Boolean, at: Instant): Boolean {
        refresh()
        val row = occurrences(today()).firstOrNull { it.id == id && it.scheduled && it.state == HistoryState.UNLOGGED }
            ?: return false
        require(step in row.version.checklist)
        val previous = state.checkedSteps[id].orEmpty().intersect(row.version.checklist.toSet())
        val next = if (checked) previous + step else previous - step
        val done = next.size == row.version.checklist.size
        commit(state.copy(
            checkedSteps = if (next.isEmpty()) state.checkedSteps - id else state.checkedSteps + (id to next),
            reports = if (done) state.reports + (id to Report(Entry(Outcome.DONE, loggedAt = at), row.title)) else state.reports
        ))
        return done
    }

    fun ritual(id: String): RitualDay {
        refresh()
        state.rituals[id]?.let { return it }
        val (item, anchor, suffix) = parts(id)
        val version = requireNotNull(state.items.getValue(item).at(anchor))
        require(version.kind == ItemKind.RITUAL)
        val oldRounds = if (item == AFFIRMATIONS && suffix == "base") legacy.rounds(anchor) else 0
        val legacyContent = item == AFFIRMATIONS && oldRounds > 0
        val day = RitualDay(if (legacyContent) "Morning ritual" else version.title,
            if (legacyContent) ReminderSample.MORNING.steps else version.content,
            if (legacyContent) 3 else version.target, oldRounds)
        commit(state.copy(rituals = state.rituals + (id to day)))
        return day
    }
    fun setRounds(id: String, rounds: Int) {
        refresh()
        val day = ritual(id); require(rounds in 0..day.target)
        commit(state.copy(rituals = state.rituals + (id to day.copy(rounds = rounds))))
    }
    fun occurrences(date: LocalDate, choices: Map<LocalDate, List<OfficeChange>> = state.officeChanges): List<PlannedOccurrence> {
        val ids = state.items.values.filter { it.created <= date && it.at(date) != null }.map { identity(it.id, date) }.toMutableSet()
        state.changes.forEach { (id, stack) -> if (stack.lastOrNull()?.date == date) ids += id }
        return ids.mapNotNull { id -> resolve(id, date, choices) }.sortedWith(compareBy<PlannedOccurrence> { it.time == null }.thenBy { it.time }.thenBy { it.id })
    }
    private fun resolve(id: String, date: LocalDate, choices: Map<LocalDate, List<OfficeChange>>): PlannedOccurrence? {
        val (itemId, anchor, suffix) = parts(id)
        val item = state.items.getValue(itemId)
        val version = item.at(anchor) ?: return null
        val action = state.changes[id]?.lastOrNull()
        val extra = suffix != "base"
        if (extra && action == null) return null
        val actualDate = action?.date ?: anchor
        val movedAway = actualDate != date
        if (date != anchor && movedAway) return null
        val archived = item.at(date)?.let { isArchived(item, it) } ?: true
        val explicit = action?.kind in setOf(ExceptionKind.MOVE, ExceptionKind.EXTRA)
        val normal = version.rule.matches(anchor, office(anchor, choices), dayType(anchor, choices))
        val deleted = state.deleted[itemId]?.let { date >= it } == true
        val scheduled = !deleted && !archived && !movedAway && action?.kind != ExceptionKind.SKIP && (explicit || normal)
        val report = entry(id)
        val status = if (movedAway) HistoryState.NOT_SCHEDULED else when (report.outcome) {
            Outcome.DONE -> HistoryState.DONE
            Outcome.LATE -> HistoryState.LATE
            Outcome.SKIPPED -> HistoryState.SKIPPED
            Outcome.UNLOGGED -> when {
                action?.kind == ExceptionKind.SKIP && !movedAway -> HistoryState.SKIPPED
                scheduled -> HistoryState.UNLOGGED
                else -> HistoryState.NOT_SCHEDULED
            }
        }
        val legacyTitle = Routines.byId(itemId)?.takeIf { suffix == "base" && legacy.entry(anchor, itemId).outcome != Outcome.UNLOGGED }?.title
        val title = state.reports[id]?.titleAtReport ?: state.rituals[id]?.title ?: legacyTitle ?: version.title
        val explanation = when {
            movedAway -> "Moved to $actualDate · original reminder suppressed"
            action?.kind == ExceptionKind.SKIP -> "Skipped this date${action.reason.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}"
            deleted -> "Deleted"
            archived -> "Archived · history retained"
            extra -> "Added for this date"
            action?.kind == ExceptionKind.MOVE -> "Moved from $anchor · same occurrence"
            version.legacyUnknownSchedule -> "Legacy tracking slot · past schedule metadata unavailable"
            !scheduled && version.rule.kind == RepeatKind.OFFICE -> "No office this date"
            !scheduled -> "Not scheduled by this pattern"
            else -> "Planned"
        }
        // Skip changes the status, not the last explicit placement. A null placement
        // means Anytime, so it must not fall back to the repeating rule's time.
        val placement = state.changes[id]?.lastOrNull { it.kind != ExceptionKind.SKIP }
        val displayedTime = if (placement != null) placement.time else version.time
        return PlannedOccurrence(id, itemId, anchor, date, version, title, displayedTime,
            scheduled, status, report.actual, explanation, extra)
    }
    fun previewOffice(date: LocalDate, choice: OfficeChoice, reason: String = ""): ChangePreview {
        val next = state.officeChanges + (date to (state.officeChanges[date].orEmpty() + OfficeChange(choice, reason)))
        val before = occurrences(date); val after = occurrences(date, next)
        return ChangePreview(date, office(date), office(date, next), before.filter { it.scheduled && after.none { n -> n.id == it.id && n.scheduled } }.map { it.title },
            after.filter { it.scheduled && before.none { n -> n.id == it.id && n.scheduled } }.map { it.title },
            before.filter { state.changes[it.id].orEmpty().isNotEmpty() }.map { "${it.title}: ${it.explanation}" },
            before.filter { it.state in setOf(HistoryState.DONE, HistoryState.LATE, HistoryState.SKIPPED) }.map { "${it.title}: ${it.state}" })
    }
    fun nextDates(id: String, from: LocalDate = today(), count: Int = 3): List<LocalDate> {
        require(count in 1..20)
        val itemChanges = state.changes.filterKeys { it.startsWith("$id@") }
        return (0L..1461L).asSequence().map { from.plusDays(it) }.filter { date ->
            val ids = setOf(identity(id, date)) + itemChanges.filterValues { it.lastOrNull()?.date == date }.keys
            ids.any { resolve(it, date, state.officeChanges)?.scheduled == true }
        }.take(count).toList()
    }
    /** Alert selection filters real calendar occurrences, never shifts the recurrence from completion. */
    fun upcomingOccurrences(id: String, from: LocalDate, count: Int, eligible: (PlannedOccurrence) -> Boolean): List<PlannedOccurrence> {
        require(count in 1..20)
        val item=state.items.getValue(id)
        val changes=state.changes.filterKeys {it.startsWith("$id@")}
        val current=item.at(from)
        val timed=(current!=null && !current.archived && current.time!=null) || item.versions.any {it.from>from && !it.archived && it.time!=null} || changes.values.any {stack->stack.lastOrNull()?.let {it.date>=from && it.time!=null && it.kind!=ExceptionKind.SKIP}==true}
        if(!timed)return emptyList()
        return (0L..1461L).asSequence().map {from.plusDays(it)}.flatMap {date->
            (setOf(identity(id,date))+changes.filterValues {it.lastOrNull()?.date==date}.keys).asSequence().mapNotNull {resolve(it,date,state.officeChanges)}
        }.filter(eligible).take(count).toList()
    }
    fun previewDates(id: String?, version: PlanVersion): List<LocalDate> {
        future(version.from)
        val key = id ?: "preview"
        val existing = state.items[key]
        val proposed = existing?.copy(versions = existing.versions.filterNot { it.from == version.from } + version)
            ?: PlanItem(key, version.from, listOf(version))
        val encoded = FlexCodec.encode(state.copy(items = state.items + (key to proposed)))
        val overlay = object : KeyValue {
            override fun get(key: String) = if (key == KEY) encoded else kv.get(key)
            override fun put(key: String, value: String?) { error("A preview cannot write data.") }
        }
        return FlexiblePlanner(overlay, today=today).nextDates(key, version.from)
    }
    /** Pure projection ONLY: Android alarm integration is intentionally outside this checkpoint. */
    fun gentleDelta(date: LocalDate, now: Instant, zone: ZoneId, pending: Map<String, Instant>): GentlePlanDelta {
        val desired = occurrences(date).filter { it.scheduled && it.state == HistoryState.UNLOGGED && it.time != null && it.version.gentleEnabled }
            .associate { it.id to date.atTime(it.time).atZone(zone).toInstant() }
        val retained = pending.filter { (id, instant) -> desired[id] == instant }
        val cancelled = pending.keys - retained.keys
        val added = desired.filter { (id, instant) -> id !in retained && instant > now }
        return GentlePlanDelta(retained, cancelled, added, desired.filter { (id, instant) -> id !in retained && instant <= now }.keys)
    }
}
private fun LocalDate.datesThrough(end: LocalDate): List<LocalDate> = (0L..(end.toEpochDay() - toEpochDay())).map { plusDays(it) }
