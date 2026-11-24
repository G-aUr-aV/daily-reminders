package dev.gaurav.dailyrhythm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate

class DailyRemindersWidget : AppWidgetProvider() {
    companion object {
        internal const val OPEN_CREATOR = "dev.gaurav.dailyrhythm.NEW_REMINDER"
        private const val MARK_DONE = "dev.gaurav.dailyrhythm.WIDGET_DONE"
        private const val ITEM = "item"

        internal fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, DailyRemindersWidget::class.java))
            if (ids.isNotEmpty()) Handler(Looper.getMainLooper()).post {
                runCatching { DailyRemindersWidget().onUpdate(app, manager, ids) }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MARK_DONE) {
            val id = intent.getStringExtra(ITEM) ?: return
            if (intent.data?.authority != "widget" || intent.data?.lastPathSegment != id || id.length > 255) return
            runCatching {
                val today = LocalDate.now()
                val planner = FlexiblePlanner(Preferences(context), persistMigration = false) { today }
                val row = planner.occurrences(today).firstOrNull {
                    it.id == id && it.scheduled && it.state == HistoryState.UNLOGGED && it.version.kind == ItemKind.ROUTINE
                } ?: return@runCatching
                planner.report(row.id, Entry(Outcome.DONE, loggedAt = Instant.now()))
                runCatching { LiveReminders(context).sync() }
                CalendarSync.publishSoon(context)
            }
            refresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val today = LocalDate.now()
        val storage = Preferences(context)
        val rows = runCatching {
            if (storage.planSnapshot() == null) emptyList()
            else {
                val planner = FlexiblePlanner(storage) { today }
                planner.occurrences(today).filter { it.visibleOnAgenda(planner.state.deleted[it.itemId]) }
            }
        }
        ids.forEach { widget ->
            val height = manager.getAppWidgetOptions(widget).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
            val layout = RemoteViews(context.packageName, R.layout.reminder_widget)
            val agenda = rows.getOrDefault(emptyList())
            val done = agenda.count { it.state == HistoryState.DONE || it.state == HistoryState.LATE }
            val pending = agenda.filter { it.state == HistoryState.UNLOGGED }
            val fit = ((height - 72) / 48).coerceIn(0, 8)
            val visible = pending.take(if (pending.size > fit) (fit - 1).coerceAtLeast(0) else fit)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            val add = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
                .setAction(OPEN_CREATOR).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            layout.setTextViewText(R.id.widget_header, "${shortDate(today)} · $done/${agenda.size} done")
            layout.setOnClickPendingIntent(R.id.widget_header, open)
            layout.setOnClickPendingIntent(R.id.widget_add, add)
            visible.forEach { row ->
                val child = RemoteViews(context.packageName, R.layout.reminder_widget_row)
                child.setTextViewText(R.id.widget_title, row.title)
                child.setOnClickPendingIntent(R.id.widget_title, open)
                val routine = row.version.kind == ItemKind.ROUTINE
                child.setViewVisibility(R.id.widget_done, if (routine) View.VISIBLE else View.INVISIBLE)
                if (routine) {
                    val action = Intent(context, DailyRemindersWidget::class.java).setAction(MARK_DONE)
                        .setData(Uri.Builder().scheme("dailyrhythm").authority("widget").appendPath(row.id).build())
                        .putExtra(ITEM, row.id)
                    child.setContentDescription(R.id.widget_done, "Mark ${row.title} done")
                    child.setOnClickPendingIntent(R.id.widget_done,
                        PendingIntent.getBroadcast(context, 0, action, PendingIntent.FLAG_IMMUTABLE))
                }
                layout.addView(R.id.widget_rows, child)
            }
            val footer = when {
                rows.isFailure -> "Open Daily Reminders"
                pending.isEmpty() -> if (agenda.isEmpty()) "Nothing scheduled" else "All caught up"
                visible.isEmpty() -> "${pending.size} pending"
                pending.size > visible.size -> "${pending.size - visible.size} more · Open Today"
                else -> null
            }
            layout.setViewVisibility(R.id.widget_footer, if (footer == null) View.GONE else View.VISIBLE)
            layout.setTextViewText(R.id.widget_footer, footer.orEmpty())
            layout.setOnClickPendingIntent(R.id.widget_footer, open)
            manager.updateAppWidget(widget, layout)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        onUpdate(context, manager, intArrayOf(id))
}
