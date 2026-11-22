package dev.gaurav.dailyrhythm

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalendarSyncTest {
    @Test fun lifecycleOnlyWritesOwnedEventsInTheSelectedAccount() {
        val context = RuntimeEnvironment.getApplication()
        val provider = CalendarFixture()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        Shadows.shadowOf(context).grantPermissions(*CalendarSync.permissions)
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        var now = today.atTime(8, 0).atZone(zone).toInstant()
        val sync = CalendarSync(context) { now }
        val planner = FlexiblePlanner(Preferences(context), persistMigration = false) { today }
        // Initialize via the production clean-install path, then operate the planner.
        LiveReminders(context).initialize()
        planner.refresh()
        val item = planner.create(PlanVersion(today, "Water plants", time = LocalTime.NOON, durationMinutes = 45))
        val occurrence = FlexiblePlanner.identity(item, today)
        val title = Events.TITLE
        sync.select(sync.calendars().last())
        val jobs = context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs
        assertEquals(setOf(true, false), jobs.map { it.isPeriodic }.toSet())
        assertTrue(jobs.all { it.service.className == CalendarSyncJob::class.java.name })
        assertFalse(sync.reconcile())
        assertEquals(32, provider.events.size)
        val created = provider.events.entries.first { it.value.getAsString(title) == "Water plants" }
        assertEquals(7L, created.value.getAsLong(Events.CALENDAR_ID).toLong())
        assertEquals(45 * 60_000L, created.value.getAsLong(Events.DTEND) - created.value.getAsLong(Events.DTSTART))
        assertNull(created.value.getAsString(Events.DESCRIPTION))
        val previousUid = created.value.getAsString(Events.UID_2445)
        // Model a calendar row already published by 0.4.1.
        created.value.remove(Events.UID_2445)
        created.value.put(Events.DESCRIPTION, "Daily Rhythm ·\n" + previousUid.removeSuffix("@dev.gaurav.dailyrhythm"))

        assertFalse(sync.reconcile())
        assertEquals(32, provider.events.size)
        assertEquals(previousUid, provider.events.getValue(created.key).getAsString(Events.UID_2445))
        assertNull(provider.events.getValue(created.key).getAsString(Events.DESCRIPTION))
        now = today.atTime(12, 1).atZone(zone).toInstant()
        assertFalse(sync.reconcile())
        assertEquals("Water plants · Pending", provider.events[created.key]?.getAsString(title))
        val originalStart = created.value.getAsLong(Events.DTSTART)
        planner.report(occurrence, Entry(Outcome.DONE, LocalTime.NOON))
        assertFalse(sync.reconcile())
        assertEquals("Water plants · Done", provider.events[created.key]?.getAsString(title))
        planner.report(occurrence, Entry())
        sync.reconcile()
        assertEquals("Water plants · Pending", provider.events[created.key]?.getAsString(title))
        assertEquals(originalStart, provider.events[created.key]?.getAsLong(Events.DTSTART))
        planner.move(occurrence, today, LocalTime.of(19, 0))
        sync.reconcile()
        assertEquals("Water plants", provider.events[created.key]?.getAsString(title))
        assertNotEquals(originalStart, provider.events[created.key]?.getAsLong(Events.DTSTART))
        planner.skip(occurrence)
        sync.reconcile()
        assertEquals("Water plants · Skipped", provider.events[created.key]?.getAsString(title))
        sync.select(sync.calendars().first())
        sync.reconcile()
        assertEquals(1, provider.events.values.count { it.getAsLong(Events.CALENDAR_ID) == 7L })
        planner.delete(item)
        sync.reconcile()
        assertEquals(setOf("Dentist", "Call"), provider.events.values.map { it.getAsString(title) }.toSet())
        assertTrue(planner.occurrences(today).none { it.scheduled && it.itemId == item })
    }

    @Test fun projectionKeepsFarOneOffsAndAllDayRowsAndUsesEachDayType() {
        val today = LocalDate.of(2026, 11, 16)
        val values = mutableMapOf<String, String>()
        val planner = FlexiblePlanner(object : KeyValue {
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        }) { today }
        val far = today.plusDays(80)
        val once = planner.create(PlanVersion(far, "Plan trip", rule = RepeatRule(RepeatKind.ONCE, oneDate = far)))
        val office = planner.create(PlanVersion(today, "Commute", rule = RepeatRule(RepeatKind.OFFICE), time = LocalTime.NOON))
        val leave = planner.create(PlanVersion(today, "Beach", rule = RepeatRule(RepeatKind.LEAVE)))
        val off = today.plusDays(1)
        planner.setDayType(off, DayType.LEAVE)
        val projection = calendarOccurrences(planner, today, ZoneOffset.UTC, today, emptySet())
        assertTrue(projection.getValue(FlexiblePlanner.identity(once, far)).allDay)
        assertTrue(projection.getValue(FlexiblePlanner.identity(leave, off)).allDay)
        assertFalse(projection.getValue(FlexiblePlanner.identity(office, today)).allDay)
        assertFalse(FlexiblePlanner.identity(office, off) in projection)
        assertFalse(FlexiblePlanner.identity(office, far) in projection)
        planner.delete(once)
        assertFalse(FlexiblePlanner.identity(once, far) in calendarOccurrences(planner, today, ZoneOffset.UTC, today, emptySet()))
        planner.refresh()
        assertEquals(today, planner.state.deleted[once])
    }

    @Test fun pastPublishedOccurrenceShowsNoRecordAndCanBeCorrectedInPlace() {
        val today = LocalDate.of(2026, 11, 19)
        val yesterday = today.minusDays(1)
        val values = mutableMapOf<String, String>()
        val planner = FlexiblePlanner(object : KeyValue {
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        }) { yesterday }
        val item = planner.create(PlanVersion(yesterday, "Stretch", time = LocalTime.of(7, 0)))
        val id = FlexiblePlanner.identity(item, yesterday)
        val pending = calendarOccurrences(planner, today, ZoneOffset.UTC, yesterday, setOf(id)).getValue(id)
        assertEquals("Stretch · No record", pending.title)
        planner.report(id, Entry(Outcome.DONE, loggedAt = today.atStartOfDay().toInstant(ZoneOffset.UTC)))
        val done = calendarOccurrences(planner, today, ZoneOffset.UTC, yesterday, setOf(id)).getValue(id)
        assertEquals(pending.copy(title = "Stretch · Done"), done)
        val rows = (0L..6L).flatMap { planner.occurrences(today.minusDays(it)) }.filter { it.itemId == item }
        val now = today.atTime(9, 0).toInstant(ZoneOffset.UTC)
        assertEquals(HabitWeek(2, 1, 0, 0, 1), habitWeek(rows, now, ZoneOffset.UTC))
    }

    private class CalendarFixture : ContentProvider() {
        private var serial = 2L
        val events = linkedMapOf(1L to unrelated(4L, "Dentist"), 2L to unrelated(7L, "Call"))
        private fun unrelated(calendar: Long, title: String) = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar)
            put(Events.TITLE, title)
            put(Events.DESCRIPTION, "My event")
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
            val columns = requireNotNull(projection).map { it }.toTypedArray()
            return MatrixCursor(columns).apply {
                if (uri == Calendars.CONTENT_URI) {
                    addRow(arrayOf(4L, "Personal", "a@example.com", "com.google"))
                    addRow(arrayOf(7L, "Routines", "b@example.com", "com.google"))
                } else {
                    assertEquals(Events.CONTENT_URI, uri)
                    assertNotNull(selection)
                    val (calendar, uid, legacy) = requireNotNull(args)
                    events.filterValues {
                        val current = it.getAsString(Events.UID_2445).orEmpty()
                        val description = it.getAsString(Events.DESCRIPTION).orEmpty()
                        it.getAsString(Events.CALENDAR_ID) == calendar &&
                            (current.startsWith(uid.substringBefore('%')) && current.endsWith(uid.substringAfter('%')) ||
                                description.startsWith(legacy.substringBefore('%')) &&
                                description.contains(legacy.substringAfter('%').substringBefore('%')))
                    }.forEach { (id, value) ->
                        addRow(columns.map { if (it == Events._ID) id else value.get(it) })
                    }
                }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            events[++serial] = ContentValues(values)
            return ContentUris.withAppendedId(uri, serial)
        }
        private fun owned(selection: String?, args: Array<out String>?): Long {
            val (id, calendar, marker) = requireNotNull(args)
            val event = events.getValue(id.toLong())
            assertEquals(calendar, event.getAsString(Events.CALENDAR_ID))
            val column = if (selection.orEmpty().contains(Events.UID_2445)) Events.UID_2445 else Events.DESCRIPTION
            assertEquals(marker, event.getAsString(column))
            return id.toLong()
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            events.remove(owned(selection, args))
            return 1
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            events.getValue(owned(selection, args)).putAll(requireNotNull(values))
            return 1
        }
    }
}
