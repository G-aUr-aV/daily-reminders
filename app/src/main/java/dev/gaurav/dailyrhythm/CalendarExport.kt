package dev.gaurav.dailyrhythm

import java.io.Writer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal data class CalendarRecord(
    val id: String, val day: LocalDate, val time: Instant?, val title: String, val status: String
)

internal fun calendarHistory(
    planner: FlexiblePlanner, events: List<AlertEvent>, legacyDates: Set<LocalDate>, zone: ZoneId
): Sequence<CalendarRecord> {
    val activity = events.asReversed().filter { QuietPresentation.activityLabel(it) != null }
        .groupBy { it.at.atZone(zone).toLocalDate() }
    val reportDates = planner.state.reports.asSequence().filter { it.value.entry.outcome != Outcome.UNLOGGED }
        .map { (id, _) -> planner.state.changes[id]?.lastOrNull()?.date ?: FlexiblePlanner.parts(id).second }
    val days = (activity.keys.asSequence() + reportDates + legacyDates.asSequence()).distinct().sorted()
    return days.flatMap { day ->
        val alerts = activity[day].orEmpty().groupBy { it.id }
        val reports = planner.occurrences(day).filter { it.state in setOf(HistoryState.DONE, HistoryState.LATE, HistoryState.SKIPPED) }
            .associateBy { it.id }
        (alerts.keys + reports.keys).asSequence().map { id ->
            val report = reports[id]
            val alert = alerts[id]?.firstOrNull()
            val title = report?.title ?: runCatching {
                val (item, anchor) = FlexiblePlanner.parts(id)
                planner.state.items[item]?.at(anchor)?.title
            }.getOrNull() ?: "Reminder"
            val at = report?.actual?.let { day.atTime(it).atZone(zone).toInstant() }
                ?: report?.let { planner.entry(id).loggedAt }?.takeIf { it.atZone(zone).toLocalDate() == day }
                ?: alert?.at
            val status = listOfNotNull(report?.let { QuietPresentation.stateLabel(it.state) },
                alert?.let(QuietPresentation::activityLabel)).joinToString(" · ")
            CalendarRecord(id, day, at, title, status)
        }
    }
}

internal object CalendarExport {
    private val timestamp = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun write(output: Writer, records: Sequence<CalendarRecord>, createdAt: Instant) {
        fun line(value: String) {
            var bytes = 0
            var index = 0
            var start = 0
            while (index < value.length) {
                val character = value.codePointAt(index)
                val size = when {
                    character <= 0x7f -> 1
                    character <= 0x7ff -> 2
                    character <= 0xffff -> 3
                    else -> 4
                }
                if (bytes + size > 75) {
                    output.write(value, start, index - start)
                    output.write("\r\n ")
                    start = index
                    bytes = 1
                }
                bytes += size
                index += Character.charCount(character)
            }
            output.write(value, start, value.length - start)
            output.write("\r\n")
        }
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//Daily Reminders//History//EN")
        for (record in records) {
            val uid = UUID.nameUUIDFromBytes("${record.id}|${record.day}".toByteArray(Charsets.UTF_8))
            line("BEGIN:VEVENT")
            line("UID:$uid@daily-rhythm")
            line("DTSTAMP:${timestamp.format(createdAt)}")
            if (record.time == null) {
                line("DTSTART;VALUE=DATE:${record.day.format(DateTimeFormatter.BASIC_ISO_DATE)}")
            } else {
                line("DTSTART:${timestamp.format(record.time)}")
                line("DTEND:${timestamp.format(record.time.plusSeconds(60))}")
            }
            line("SUMMARY:${escape(record.title)}")
            line("DESCRIPTION:${escape(record.status)}")
            line("END:VEVENT")
        }
        line("END:VCALENDAR")
    }

    private fun escape(value: String) = value.replace("\\", "\\\\")
        .replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\n")
        .replace(",", "\\,").replace(";", "\\;")
}
