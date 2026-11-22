package dev.gaurav.dailyrhythm

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.*

class DayTypesAndCalendarTest {
    private val monday = LocalDate.of(2026, 11, 16)
    private val values = mutableMapOf<String, String>()
    private val kv = object : KeyValue {
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }
    private fun planner() = FlexiblePlanner(kv) { monday }

    @Test fun weekdayTypesProvideExclusiveSchedulesAndWeekendIsAutomatic() {
        val p = planner()
        val ids = DayType.entries.associateWith { type ->
            p.create(PlanVersion(monday, type.label, time = LocalTime.NOON, rule = RepeatRule(type.repeat)))
        }
        val thursday = monday.plusDays(3)
        val saturday = monday.plusDays(5)
        p.setDayType(thursday, DayType.LEAVE)
        assertThrows(IllegalArgumentException::class.java) { p.setDayType(saturday, DayType.OFFICE) }
        assertThrows(IllegalArgumentException::class.java) { p.setDayType(monday, DayType.WEEKEND) }
        val reopened = planner()
        for ((day, type) in listOf(monday to DayType.OFFICE, monday.plusDays(1) to DayType.HOME,
            thursday to DayType.LEAVE, saturday to DayType.WEEKEND, saturday.plusDays(1) to DayType.WEEKEND)) {
            assertEquals(type, reopened.dayType(day))
            assertEquals(listOf(ids.getValue(type)), reopened.occurrences(day).filter { it.scheduled && it.itemId in ids.values }.map { it.itemId })
        }
        reopened.undoOffice(thursday)
        assertEquals(DayType.HOME, reopened.dayType(thursday))
    }

    @Test fun exportGroupsTriggerAndStopAndPreservesActualAndDateOnlyReports() {
        val p = planner()
        val zone = ZoneId.of("Asia/Kolkata")
        val at = monday.atTime(8, 12).atZone(zone).toInstant()
        val alarm = FlexiblePlanner.identity("wake", monday)
        val skip = FlexiblePlanner.identity("exercise", monday)
        p.report(skip, Entry(Outcome.SKIPPED))
        val activity = listOf(AlertEvent(alarm, "DELIVERED", "Ringing requested", at),
            AlertEvent(alarm, "DISMISSED", "", at.plusSeconds(25)))
        val records = calendarHistory(p, activity, emptySet(), zone).toList()
        assertEquals(2, records.size)
        assertEquals("Stopped", records.single { it.id == alarm }.status)
        assertEquals(at.plusSeconds(25), records.single { it.id == alarm }.time)
        assertNull(records.single { it.id == skip }.time)
        val file = StringWriter().also { CalendarExport.write(it, records.asSequence(), at) }.toString()
        assertTrue(file.contains("DTSTART:20261116T024225Z\r\n"))
        assertTrue(file.contains("DTSTART;VALUE=DATE:20261116\r\n"))
        assertEquals(2, file.lineSequence().count { it == "BEGIN:VEVENT" })
    }

    @Test fun exportEscapesSeparatorsAndFoldsUnicodeByUtf8Length() {
        val title = "Kitchen, office; \\\r\nEND:VEVENT " + "🌿".repeat(28)
        val record = CalendarRecord("one", monday, null, title, "Done")
        val file = StringWriter().also { CalendarExport.write(it, sequenceOf(record), Instant.EPOCH) }.toString()
        assertTrue(file.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertEquals(1, file.lineSequence().count { it == "END:VEVENT" })
        assertTrue(file.replace("\r\n ", "").contains("Kitchen\\, office\\; \\\\\\nEND:VEVENT " + "🌿".repeat(28)))
    }
}
