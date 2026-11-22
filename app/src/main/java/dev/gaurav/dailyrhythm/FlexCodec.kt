package dev.gaurav.dailyrhythm

import java.io.*
import java.time.*
import java.util.Base64

/** One additive private-pref value, atomically committed by KeyValue. Corruption never resets it. */
internal object FlexCodec {
    fun encode(state: FlexState): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF("FLEX4"); out.date(state.migratedOn)
            out.writeInt(state.items.size)
            state.items.toSortedMap().forEach { (_, item) ->
                out.writeUTF(item.id); out.date(item.created); out.writeInt(item.versions.size)
                item.versions.forEach { v ->
                    out.date(v.from); out.writeUTF(v.title); out.writeUTF(v.note); out.writeUTF(v.kind.name); out.time(v.time)
                    out.writeUTF(v.rule.kind.name); out.writeUTF(v.rule.weekdays.sortedBy { it.value }.joinToString(",") { it.value.toString() })
                    out.writeUTF(v.rule.monthDays.sorted().joinToString(",")); out.writeBoolean(v.rule.lastDay)
                    out.writeUTF(v.rule.missing?.name ?: ""); out.writeUTF(v.rule.oneDate?.toString() ?: "")
                    out.writeBoolean(v.archived); out.lines(v.content); out.writeInt(v.target); out.writeBoolean(v.gentleEnabled); out.writeBoolean(v.legacyUnknownSchedule); out.writeInt(v.durationMinutes)
                    out.writeUTF(v.category?.name.orEmpty()); out.lines(v.checklist)
                }
            }
            out.writeInt(state.office.size)
            state.office.forEach { v -> out.date(v.from); out.writeUTF(v.weekdays.sortedBy { it.value }.joinToString(",") { it.value.toString() }) }
            out.writeInt(state.officeChanges.size)
            state.officeChanges.toSortedMap().forEach { (date, stack) -> out.date(date); out.writeInt(stack.size); stack.forEach { out.writeUTF(it.choice.name); out.writeUTF(it.reason) } }
            out.writeInt(state.changes.size)
            state.changes.toSortedMap().forEach { (id, stack) -> out.writeUTF(id); out.writeInt(stack.size); stack.forEach { out.writeUTF(it.kind.name); out.date(it.date); out.time(it.time); out.writeUTF(it.reason) } }
            out.writeInt(state.reports.size)
            state.reports.toSortedMap().forEach { (id, report) ->
                out.writeUTF(id)
                out.writeUTF(report.entry.outcome.name)
                out.time(report.entry.actual)
                out.writeUTF(report.titleAtReport)
                out.writeUTF(report.entry.loggedAt?.toString().orEmpty())
            }
            out.writeInt(state.rituals.size)
            state.rituals.toSortedMap().forEach { (id, r) -> out.writeUTF(id); out.writeUTF(r.title); out.lines(r.lines); out.writeInt(r.target); out.writeInt(r.rounds) }
            out.writeInt(state.deleted.size)
            state.deleted.toSortedMap().forEach { (id, date) -> out.writeUTF(id); out.date(date) }
            out.writeInt(state.checkedSteps.size)
            state.checkedSteps.toSortedMap().forEach { (id, steps) -> out.writeUTF(id); out.lines(steps.sorted()) }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray()).also {
            require(it.length <= 8_000_000) { "Planning checkpoint storage limit reached. Existing data was not replaced." }
        }
    }
    fun decode(encoded: String): FlexState = try {
        require(encoded.length <= 8_000_000)
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            val format = input.readUTF()
            require(format in setOf("FLEX1", "FLEX2", "FLEX3", "FLEX4"))
            val migrated = input.date()
            val items = buildMap {
                repeat(input.count()) {
                    val id = input.readUTF(); val created = input.date()
                    val versions = buildList {
                        repeat(input.count()) {
                            val from = input.date(); val title = input.readUTF(); val note = input.readUTF(); val kind = ItemKind.valueOf(input.readUTF()); val time = input.time()
                            val repeat = RepeatKind.valueOf(input.readUTF()); val weekdays = input.readUTF().ints().map(DayOfWeek::of).toSet(); val monthDays = input.readUTF().ints().toSet()
                            val last = input.readBoolean(); val missing = input.readUTF().takeIf { it.isNotEmpty() }?.let(MissingDate::valueOf); val one = input.readUTF().takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
                            val archived = input.readBoolean(); val content = input.lines(); val target = input.readInt(); val gentle = input.readBoolean(); val unknown = input.readBoolean()
                            val duration = if (format == "FLEX1") 15 else input.readInt()
                            val category = if (format == "FLEX4") input.readUTF().takeIf(String::isNotEmpty)?.let(ReminderCategory::valueOf) else null
                            val checklist = if (format == "FLEX4") input.lines() else emptyList()
                            add(PlanVersion(from, title, note, kind, time, RepeatRule(repeat, weekdays, monthDays, last, missing, one), archived, content, target, gentle, unknown, duration, category, checklist))
                        }
                    }
                    require(versions.isNotEmpty() && !containsKey(id))
                    put(id, PlanItem(id, created, versions))
                }
            }
            val office = buildList { repeat(input.count()) { add(OfficeVersion(input.date(), input.readUTF().ints().map(DayOfWeek::of).toSet())) } }
            val officeChanges = buildMap {
                repeat(input.count()) {
                    val date = input.date(); put(date, buildList { repeat(input.count()) { add(OfficeChange(OfficeChoice.valueOf(input.readUTF()), input.readUTF())) } })
                }
            }
            val changes = buildMap {
                repeat(input.count()) {
                    val id = input.readUTF(); FlexiblePlanner.parts(id)
                    put(id, buildList { repeat(input.count()) { add(ItemChange(ExceptionKind.valueOf(input.readUTF()), input.date(), input.time(), input.readUTF())) } })
                }
            }
            val reports = buildMap {
                repeat(input.count()) {
                    val id = input.readUTF()
                    val outcome = Outcome.valueOf(input.readUTF())
                    val actual = input.time()
                    val title = input.readUTF()
                    val loggedAt = if (format == "FLEX3" || format == "FLEX4") input.readUTF().takeIf(String::isNotEmpty)?.let(Instant::parse) else null
                    put(id, Report(Entry(outcome, actual, loggedAt), title))
                }
            }
            val rituals = buildMap {
                repeat(input.count()) {
                    val id = input.readUTF(); val title = input.readUTF(); val lines = input.lines(); val target = input.readInt(); val rounds = input.readInt()
                    require(target in 1..10 && rounds in 0..target)
                    put(id, RitualDay(title, lines, target, rounds))
                }
            }
            val deleted = buildMap {
                if (input.available() > 0) repeat(input.count()) {
                    val id = input.readUTF()
                    require(id in items && !containsKey(id))
                    put(id, input.date())
                }
            }
            val checkedSteps = buildMap {
                if (format == "FLEX4") repeat(input.count()) {
                    val id = input.readUTF()
                    val steps = input.lines()
                    require(steps.size in 1..20 && steps.distinct().size == steps.size &&
                        steps.all { it.isNotBlank() && it.length <= 120 } && !containsKey(id))
                    put(id, steps.toSet())
                }
            }
            require(input.available() == 0 && office.isNotEmpty())
            (changes.keys + reports.keys + rituals.keys + checkedSteps.keys).forEach { id ->
                val (item, anchor) = FlexiblePlanner.parts(id)
                require(items[item]?.at(anchor) != null)
            }
            FlexState(migrated, items, office, officeChanges, changes, reports, rituals, deleted, checkedSteps)
        }
    } catch (error: Exception) {
        throw IllegalStateException("Planning data could not be read. Existing planning and legacy data were not reset or deleted.", error)
    }
    internal data class OccurrenceRecord(
        val changes: List<ItemChange> = emptyList(),
        val report: Report? = null,
        val ritual: RitualDay? = null,
        val checked: Set<String> = emptySet()
    )

    fun encodeRecord(record: OccurrenceRecord): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(record.changes.size)
            record.changes.forEach { change ->
                out.writeUTF(change.kind.name)
                out.date(change.date)
                out.time(change.time)
                out.writeUTF(change.reason)
            }
            out.writeBoolean(record.report != null)
            record.report?.let {
                out.writeUTF(it.entry.outcome.name)
                out.time(it.entry.actual)
                out.writeUTF(it.entry.loggedAt?.toString().orEmpty())
                out.writeUTF(it.titleAtReport)
            }
            out.writeBoolean(record.ritual != null)
            record.ritual?.let {
                out.writeUTF(it.title)
                out.lines(it.lines)
                out.writeInt(it.target)
                out.writeInt(it.rounds)
            }
            out.lines(record.checked.sorted())
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decodeRecord(encoded: String): OccurrenceRecord =
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            val changes = List(input.count()) {
                ItemChange(ExceptionKind.valueOf(input.readUTF()), input.date(), input.time(), input.readUTF())
            }
            val report = if (input.readBoolean()) {
                val entry = Entry(Outcome.valueOf(input.readUTF()), input.time(),
                    input.readUTF().takeIf(String::isNotEmpty)?.let(Instant::parse))
                Report(entry, input.readUTF())
            } else null
            val ritual = if (input.readBoolean()) {
                RitualDay(input.readUTF(), input.lines(), input.readInt(), input.readInt())
            } else null
            val checked = input.lines().toSet()
            require(input.available() == 0)
            OccurrenceRecord(changes, report, ritual, checked)
        }

    private fun DataOutputStream.date(date: LocalDate) = writeUTF(date.toString())
    private fun DataInputStream.date() = LocalDate.parse(readUTF())
    private fun DataOutputStream.time(time: LocalTime?) = writeUTF(time?.toString() ?: "")
    private fun DataInputStream.time(): LocalTime? = readUTF().takeIf { it.isNotEmpty() }?.let(LocalTime::parse)
    private fun DataOutputStream.lines(lines: List<String>) { writeInt(lines.size); lines.forEach(::writeUTF) }
    private fun DataInputStream.lines() = List(count()) { readUTF() }
    private fun DataInputStream.count(): Int = readInt().also { require(it in 0..100_000) }
    private fun String.ints() = if (isEmpty()) emptyList() else split(',').map(String::toInt)
}
