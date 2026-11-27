package dev.gaurav.dailyrhythm

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import java.time.Instant

internal fun alarmAccessIntent(context: Context, action: String): Intent? =
    systemActivity(context, Intent(action, Uri.parse("package:${context.packageName}")))
        ?: systemActivity(context, Intent(action))

internal fun alarmPresentationAllowed(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    if ((manager.getNotificationChannel(RingingService.CHANNEL)?.importance
            ?: NotificationManager.IMPORTANCE_NONE) < NotificationManager.IMPORTANCE_HIGH) return false
    return when (manager.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_NONE -> false
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> Build.VERSION.SDK_INT >= 30 &&
            runCatching {
                manager.consolidatedNotificationPolicy.priorityCategories and
                    NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
            }.getOrDefault(false)
        else -> true
    }
}

internal fun lockedForAlarm(context: Context) =
    context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
        !context.getSystemService(PowerManager::class.java).isInteractive

private fun ringingAlert(session: String, snapshot: RingingSnapshot?): LiveAlert? =
    snapshot?.takeIf { it.sessionToken == session }?.alerts?.firstOrNull()?.takeIf { RingingService.accepts(it, session) && RingingService.usesFullScreen(it) }

internal data class AlarmDisplay(val token: String, val title: String, val time: String, val queued: Int,
                                 val canComplete: Boolean, val category: ReminderCategory?, val steps: List<String>)

internal class AlarmPanel(private val context: Context, private val session: String,
                          private val live: LiveReminders = LiveReminders(context)) {
    private var current: LiveAlert? = null
    var display by mutableStateOf<AlarmDisplay?>(null)
        private set

    val view = ComposeView(context).apply {
        setContent {
            this@AlarmPanel.display?.let { data ->
                androidx.compose.runtime.key(data.token, data.canComplete) {
                    AlarmScreen(data, onAction = ::perform, onSnooze = ::snooze)
                }
            }
        }
    }

    fun refresh(snapshot: RingingSnapshot?, private: Boolean): Boolean {
        val alert = ringingAlert(session, snapshot) ?: return false
        val queued = requireNotNull(snapshot).alerts.size - 1
        if (current?.token != alert.token || showingPrivate != private) {
            val row = live.ringing(alert)?.first ?: return false
            val trigger = row.date.atTime(requireNotNull(row.time)).atZone(java.time.ZoneId.systemDefault()).toInstant()
            val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date.from(trigger))
            display = AlarmDisplay(alert.token, if (private) "Daily Reminders alarm" else row.title, time, queued,
                row.version.kind == ItemKind.ROUTINE, row.version.category.takeUnless { private },
                if (private) emptyList() else row.version.checklist.take(3))
            showingPrivate = private
            current = alert
        } else if (display?.queued != queued) {
            display = display?.copy(queued = queued)
        }
        return true
    }

    private var showingPrivate = false

    fun perform(operation: String) {
        val alert = current ?: return
        if (operation == "done" && display?.canComplete != true) return
        runCatching { live.sessionAction(alert.id, alert.token, session, operation) }.onFailure(::showError)
    }

    private fun snooze(until: Instant) {
        val alert = current ?: return
        runCatching { live.snooze(alert, until, session) }.onFailure(::showError)
    }

    private fun showError(error: Throwable) {
        android.widget.Toast.makeText(context, error.message ?: "Couldn’t change this alarm.", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Launched only by the current ringing notification. The keyguard stays locked. */
class AlarmActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var panel: AlarmPanel? = null
    private val update = object : Runnable {
        override fun run() {
            if (alarmPresentationAllowed(this@AlarmActivity) &&
                runCatching { panel?.refresh(RingingService.snapshot(), lockedForAlarm(this@AlarmActivity)) }.getOrNull() == true) {
                handler.postDelayed(this, 250)
            } else finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.data?.takeIf { it.scheme == "dailyrhythm" && it.authority == "ringing" }
            ?.lastPathSegment
        val snapshot = RingingService.snapshot()
        if (token == null || ringingAlert(token, snapshot) == null) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        panel = AlarmPanel(this, token)
        if (panel?.refresh(snapshot, lockedForAlarm(this)) != true) {
            finish()
            return
        }
        setContentView(panel?.view)
    }

    override fun onStart() {
        super.onStart()
        handler.post(update)
    }

    override fun onStop() {
        handler.removeCallbacks(update)
        super.onStop()
    }
}
