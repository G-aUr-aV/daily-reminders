package dev.gaurav.dailyrhythm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Each stage runs inside the installed target APK and uses that version's Room-backed planner. */
@RunWith(AndroidJUnit4::class)
class AgendaUpgradeTest {
    @Test fun savedAgendaSurvivesAnInPlaceUpdate() {
        val stage = InstrumentationRegistry.getArguments().getString("upgradeStage")
        assumeTrue(stage == "seed" || stage == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = LiveReminders(context)
        bridge.initialize()
        val planner = FlexiblePlanner(Preferences(context)) { LocalDate.now() }
        val title = "Morning reset"
        val steps = listOf("Open the curtains", "Water the plants")
        val loggedAt = Instant.parse("2026-01-01T09:30:00Z")
        val completion = Entry(Outcome.DONE, loggedAt = loggedAt)
        if (stage == "seed") {
            val day = LocalDate.now()
            val saved = bridge.createPlanned(PlanVersion(day, title, checklist = steps), GentleConfig(false))
            planner.report(FlexiblePlanner.identity(saved.id, day), completion)
        }
        planner.refresh()
        val item = planner.state.items.values.single { it.versions.first().title == title }
        val version = item.versions.single()
        assertEquals(steps, version.checklist)
        val id = FlexiblePlanner.identity(item.id, version.from)
        assertEquals(completion, planner.entry(id))
        if (stage == "verify") {
            planner.report(id, Entry())
            assertEquals(Entry(), FlexiblePlanner(Preferences(context)) { LocalDate.now() }.entry(id))
        }
    }
}
