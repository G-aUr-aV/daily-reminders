package dev.gaurav.dailyrhythm

import android.app.Application
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class FlexiblePlannerAndroidTest {
    private lateinit var app: Application
    @Before fun setup() { app=RuntimeEnvironment.getApplication();app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun additiveMigrationAndRestartPreserveLegacyPreferencesAndAlarmLedger() {
        val prefs=Preferences(app); val old=RhythmStore(prefs); val date=LocalDate.of(2026,11,9)
        old.remindersEnabled=true; old.setRounds(date,2); old.setEntry(date,"wake",Entry(Outcome.LATE,LocalTime.of(6,15)))
        old.setSchedule("depart",Schedule(LocalTime.of(7,40),false)); prefs.put("alarm_v2.pack","unmodified-existing-alarm")
        val before=app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).all.toMap()
        val planner=FlexiblePlanner(prefs) {date}
        planner.changeOffice(date,date,OfficeChoice.OFF,"Leave")
        val extra=planner.addExtra("pack",date,LocalTime.of(10,0)); planner.report(extra,Entry(Outcome.DONE))
        val recreated=FlexiblePlanner(Preferences(app)) {date.plusDays(1)}
        assertEquals(planner.state,recreated.state)
        val after=app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).all.filterKeys {it!=FlexiblePlanner.KEY}
        assertEquals(before,after)
        assertEquals(2,recreated.ritual(FlexiblePlanner.identity(FlexiblePlanner.AFFIRMATIONS,date)).rounds)
        assertEquals("unmodified-existing-alarm",prefs.get("alarm_v2.pack"))
    }
    @Test fun planningPreviewCompositionCanStartAndExitWithoutEnablingReminders() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        // Use a fresh ComposeView rather than asynchronously replacing the existing home composition.
        val preview=androidx.compose.ui.platform.ComposeView(controller.get())
        preview.setContent { MaterialTheme { FlexiblePlannerLab(onExit={}) } }
        controller.get().setContentView(preview)
        preview.createComposition()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val view=controller.get().window.decorView
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1920,android.view.View.MeasureSpec.EXACTLY))
        view.layout(0,0,1080,1920)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertNotNull(Preferences(app).get(FlexiblePlanner.KEY))
        assertFalse(app.rhythmStore().remindersEnabled)
        assertTrue(shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.isEmpty())
        controller.pause().stop().destroy()
    }
    @Test fun newPlannerReportsAndRoundSnapshotsArePrivateAndDoNotRewriteLegacyLogs() {
        val date=LocalDate.of(2026,11,9); val planner=FlexiblePlanner(Preferences(app)) {date}
        val ritual=planner.create(PlanVersion(date,"Custom reading",kind=ItemKind.RITUAL,content=listOf("Local only"),target=2))
        val id=FlexiblePlanner.identity(ritual,date); planner.setRounds(id,1)
        val reloaded=FlexiblePlanner(Preferences(app)) {date}
        assertEquals(1,reloaded.ritual(id).rounds)
        assertEquals(Entry(),reloaded.entry(id))
        assertNull(Preferences(app).get("rounds.$date"))
        assertEquals(0,app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
