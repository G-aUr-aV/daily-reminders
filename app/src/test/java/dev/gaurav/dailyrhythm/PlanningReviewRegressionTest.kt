package dev.gaurav.dailyrhythm

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Reproductions and boundary checks for Tripwire's three Core-1 findings. */
class PlanningReviewRegressionTest {
    private class Memory : KeyValue {
        val data = mutableMapOf<String, String>()
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
    }
    private val today = LocalDate.of(2026, 11, 9)
    private val kv = Memory()
    private fun planner() = FlexiblePlanner(kv) { today }
    private fun pack() = FlexiblePlanner.identity("pack", today)

    @Test fun undoCannotOrphanAnExtraWithReportedOutcome() {
        val p = planner()
        val extra = p.addExtra("pack", today, LocalTime.NOON)
        p.report(extra, Entry(Outcome.DONE, LocalTime.of(12, 3)))
        val before = kv.data.toMap()
        val error = assertThrows(IllegalArgumentException::class.java) { p.undoItem(extra) }
        assertTrue(error.message!!.contains("history"))
        assertEquals(before, kv.data)
        val restored = planner().occurrences(today).single { it.id == extra }
        assertEquals(HistoryState.DONE, restored.state)
        assertEquals(LocalTime.of(12, 3), restored.actual)
    }

    @Test fun undoCannotOrphanExtraRitualProgressOrItsSnapshot() {
        val p = planner()
        val extra = p.addExtra(FlexiblePlanner.AFFIRMATIONS, today, null)
        p.setRounds(extra, 2)
        assertThrows(IllegalArgumentException::class.java) { p.undoItem(extra) }
        val restarted = planner()
        assertEquals(2, restarted.ritual(extra).rounds)
        assertTrue(restarted.occurrences(today).any { it.id == extra })
    }

    @Test fun undoAnUntouchedExtraStillRemovesIt() {
        val p = planner()
        val extra = p.addExtra("pack", today, LocalTime.NOON)
        p.undoItem(extra)
        assertFalse(planner().occurrences(today).any { it.id == extra })
    }

    @Test fun undoSkipOnAReportedExtraRestoresThatExtra() {
        val p = planner()
        val extra = p.addExtra("pack", today, LocalTime.NOON)
        p.report(extra, Entry(Outcome.DONE, LocalTime.of(12, 3)))
        p.skip(extra)
        p.undoItem(extra)
        val row = p.occurrences(today).single { it.id == extra }
        assertTrue(row.scheduled)
        assertEquals(HistoryState.DONE, row.state)
        assertEquals(LocalTime.NOON, row.time)
        assertThrows(IllegalArgumentException::class.java) { p.undoItem(extra) }
    }

    @Test fun movingBeforeItemStartIsRejectedWithoutChangingTheOriginal() {
        val p = planner()
        val start = today.plusWeeks(1)
        val item = p.create(PlanVersion(start, "Future routine", time = LocalTime.of(9, 0)))
        val occurrence = FlexiblePlanner.identity(item, start)
        val before = kv.data.toMap()
        val error = assertThrows(IllegalArgumentException::class.java) { p.move(occurrence, today, LocalTime.NOON) }
        assertTrue(error.message!!.contains("start date"))
        assertEquals(before, kv.data)
        assertTrue(planner().occurrences(start).single { it.id == occurrence }.scheduled)
        assertFalse(p.occurrences(today).any { it.id == occurrence })
    }

    @Test fun movingExactlyToItemStartIsValid() {
        val p = planner()
        val start = today.plusWeeks(1)
        val item = p.create(PlanVersion(start, "Future routine", time = LocalTime.of(9, 0)))
        val occurrence = FlexiblePlanner.identity(item, start.plusDays(1))
        p.move(occurrence, start, LocalTime.NOON)
        val row = planner().occurrences(start).single { it.id == occurrence }
        assertTrue(row.scheduled)
        assertEquals(LocalTime.NOON, row.time)
    }

    @Test fun movingBackBeforeStartDoesNotRemoveAnExistingValidMove() {
        val p = planner()
        val start = today.plusWeeks(1)
        val item = p.create(PlanVersion(start, "Future routine", time = LocalTime.of(9, 0)))
        val occurrence = FlexiblePlanner.identity(item, start)
        p.move(occurrence, start.plusDays(2), LocalTime.NOON)
        val before = kv.data.toMap()
        assertThrows(IllegalArgumentException::class.java) { p.move(occurrence, today, null) }
        assertEquals(before, kv.data)
        assertTrue(planner().occurrences(start.plusDays(2)).single { it.id == occurrence }.scheduled)
    }

    @Test fun skippingATimedMoveKeepsItsTimeAfterRestart() {
        val p = planner()
        val occurrence = pack()
        val tuesday = today.plusDays(1)
        p.move(occurrence, tuesday, LocalTime.NOON)
        p.skip(occurrence, "Leave")
        val row = planner().occurrences(tuesday).single { it.id == occurrence }
        assertEquals(HistoryState.SKIPPED, row.state)
        assertEquals(LocalTime.NOON, row.time)
        assertFalse(row.scheduled)
    }

    @Test fun skippingAnAnytimeMoveDoesNotRestoreTheDefaultTime() {
        val p = planner()
        val occurrence = pack()
        val tuesday = today.plusDays(1)
        p.move(occurrence, tuesday, null)
        p.skip(occurrence)
        val restarted = planner()
        assertNull(restarted.occurrences(tuesday).single { it.id == occurrence }.time)
        restarted.undoItem(occurrence)
        val row = restarted.occurrences(tuesday).single { it.id == occurrence }
        assertNull(row.time)
        assertTrue(row.scheduled)
    }

    @Test fun repeatedSkipAndUndoRetainTheLatestPlacement() {
        val p = planner()
        val occurrence = pack()
        val tuesday = today.plusDays(1)
        p.move(occurrence, tuesday, LocalTime.NOON)
        p.move(occurrence, tuesday, LocalTime.of(13, 0))
        repeat(2) { p.skip(occurrence) }
        repeat(2) {
            assertEquals(LocalTime.of(13, 0), p.occurrences(tuesday).single { it.id == occurrence }.time)
            p.undoItem(occurrence)
        }
        assertTrue(p.occurrences(tuesday).single { it.id == occurrence }.scheduled)
        p.undoItem(occurrence)
        assertEquals(LocalTime.NOON, p.occurrences(tuesday).single { it.id == occurrence }.time)
    }

    @Test fun skippingAnExtraKeepsItsExplicitTime() {
        val p = planner()
        val extra = p.addExtra("pack", today, LocalTime.of(15, 0))
        p.skip(extra)
        assertEquals(LocalTime.of(15, 0), planner().occurrences(today).single { it.id == extra }.time)
    }

    @Test fun skippingAnUnmovedBaseKeepsItsDefaultTime() {
        val p = planner()
        p.skip(pack())
        assertEquals(LocalTime.of(7, 0), planner().occurrences(today).single { it.id == pack() }.time)
    }
}
