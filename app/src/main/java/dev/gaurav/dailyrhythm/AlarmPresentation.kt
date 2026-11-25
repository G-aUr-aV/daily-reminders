package dev.gaurav.dailyrhythm

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
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
            display = AlarmDisplay(alert.token, if (private) "Reminders alarm" else row.title, time, queued,
                !private && row.version.kind == ItemKind.ROUTINE, row.version.category.takeUnless { private },
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
        if (operation == "done" && (display?.canComplete != true || lockedForAlarm(context))) return
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

/** ComposeView attached directly to WindowManager needs its own window-scoped owners. */
private class OverlayLifecycle : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun dispose() { registry.currentState = Lifecycle.State.DESTROYED }
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

internal class UnlockedAlarm(private val context: Context) {
    private var windows: WindowManager? = null
    private var lifecycle: OverlayLifecycle? = null
    private var panel: AlarmPanel? = null
    private var token: String? = null
    private var attempted: String? = null

    fun refresh(snapshot: RingingSnapshot?) {
        if (snapshot == null || lockedForAlarm(context) || !Settings.canDrawOverlays(context) ||
            !alarmPresentationAllowed(context)) {
            hide()
            return
        }
        if (token == snapshot.sessionToken) {
            if (panel?.refresh(snapshot, false) != true) hide()
            return
        }
        hide()
        if (attempted == snapshot.sessionToken) return
        attempted = snapshot.sessionToken
        runCatching {
            val windowContext = if (Build.VERSION.SDK_INT >= 30) {
                val displayManager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                val display = requireNotNull(displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY))
                context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else context
            val windowManager = windowContext.getSystemService(WindowManager::class.java)
            val next = AlarmPanel(windowContext, snapshot.sessionToken)
            if (!next.refresh(snapshot, false)) return
            val owner = OverlayLifecycle()
            next.view.setViewTreeLifecycleOwner(owner)
            next.view.setViewTreeSavedStateRegistryOwner(owner)
            try {
                windowManager.addView(next.view, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE).apply {
                    @Suppress("DEPRECATION")
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                })
            } catch (error: Exception) {
                owner.dispose()
                next.view.disposeComposition()
                throw error
            }
            lifecycle = owner
            windows = windowManager
            panel = next
            token = snapshot.sessionToken
        }
    }

    fun hide() {
        panel?.let { runCatching { windows?.removeViewImmediate(it.view) } }
        panel?.view?.disposeComposition()
        lifecycle?.dispose()
        lifecycle = null
        windows = null
        panel = null
        token = null
    }
}
