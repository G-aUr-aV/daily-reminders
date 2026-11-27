package dev.gaurav.dailyrhythm

import android.app.Application
import android.appwidget.AppWidgetManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DailyRemindersWidgetTest {
    @Test fun refreshKeepsOneRowAndItsButtonsOpenCreateAndCompleteTheReminder() {
        val app: Application = RuntimeEnvironment.getApplication()
        val today = LocalDate.now()
        val storage = Preferences(app)
        LiveReminders(app).initialize()
        FlexiblePlanner(storage) { today }.create(PlanVersion(today, "Water the plants"))
        val manager = AppWidgetManager.getInstance(app)
        val host = shadowOf(manager)
        val id = host.createWidget(DailyRemindersWidget::class.java, R.layout.reminder_widget)
        val widget = DailyRemindersWidget()
        fun refresh(): View {
            widget.onUpdate(app, manager, intArrayOf(id))
            return host.getViewFor(id)
        }

        refresh()
        val view = refresh()
        assertEquals(1, view.findViewById<ViewGroup>(R.id.widget_rows).childCount)
        view.findViewById<View>(R.id.widget_add).performClick()
        assertEquals(DailyRemindersWidget.OPEN_CREATOR, shadowOf(app).nextStartedActivity.action)
        view.findViewById<View>(R.id.widget_done).performClick()
        val action = shadowOf(app).broadcastIntents.last { it.component?.className == DailyRemindersWidget::class.java.name }
        widget.onReceive(app, action)
        widget.onReceive(app, action)
        val completed = refresh()
        assertEquals(0, completed.findViewById<ViewGroup>(R.id.widget_rows).childCount)
        assertEquals("All caught up", completed.findViewById<TextView>(R.id.widget_footer).text)
        assertTrue(completed.findViewById<TextView>(R.id.widget_header).text.endsWith("1/1 done"))
    }
}
