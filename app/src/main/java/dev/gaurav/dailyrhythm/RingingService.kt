package dev.gaurav.dailyrhythm

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.UUID

/** Private exact-occurrence audio only. Never boot-started or sticky; process death does not resume. */
open class RingingService : Service() {
    companion object {
        private const val PREVIOUS_CHANNEL = "ringing_controls_v1"
        const val CHANNEL = "ringing_alarm_v2"
        const val NOTIFICATION = 4100
        const val CAP_MS = 300_000L
        private var running: RingingService? = null
        private val starting = mutableSetOf<String>()
        fun launch(context: Context, alert: LiveAlert) {
            starting += alert.token
            try {
                checkNotNull(context.startForegroundService(Intent(context, RingingService::class.java).putExtra("occurrence", alert.id).putExtra("token", alert.token)))
                Handler(Looper.getMainLooper()).postDelayed({
                    if(starting.remove(alert.token)) runCatching {LiveReminders(context).endRinging(alert,"RING_START_BLOCKED","Service did not acknowledge promptly; late start will be rejected.")}
                }, 4500)
            }
            catch(e: Exception) { starting -= alert.token; throw e }
        }
        internal fun usesFullScreen(alert: LiveAlert): Boolean =
            running?.reminders()?.config(FlexiblePlanner.parts(alert.id).first)?.fullScreen == true

        internal fun snapshot():RingingSnapshot? = running?.let {s->s.session?.let {session->RingingSnapshot(session.token,session.deadline,s.playing,session.members.values.toList())}}
        fun contains(token: String) = token in starting || running?.session?.members?.values?.any { it.token == token } == true
        fun sessionToken(alert: LiveAlert): String? = running?.session?.takeIf { it.members[alert.id]?.token == alert.token }?.token
        fun accepts(alert: LiveAlert, token: String?) = token != null && running?.session?.accepts(alert.id, alert.token, token, SystemClock.elapsedRealtime()) == true
        fun cancel(id: String) { running?.let { s ->
            val expected=s.session?.members?.get(id)?.token ?: return@let
            s.handler.post {try {if(s.session?.members?.get(id)?.token==expected)s.remove(id)}catch(e:Exception){s.finishAll("RING_BLOCKED","Cancellation cleanup failed: ${e.message}")}}
        } }
        fun mutedChannel(context: Context): String? {
            val m = context.getSystemService(NotificationManager::class.java)
            return listOf(CHANNEL, PREVIOUS_CHANNEL).firstOrNull {
                m.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE
            }
        }
        fun blocked(context: Context): String? {
            val m = context.getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(CHANNEL, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Controls for active ringing reminders."
                setSound(null, null); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
            return if(mutedChannel(context) != null || !NotificationManagerCompat.from(context).areNotificationsEnabled()) "Ringing controls blocked; alarm not enabled." else null
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var session: RingSession? = null
    private var audio: AudioOutput? = null
    private val soundPins=mutableMapOf<String,java.io.Closeable>()
    private var playing: String? = null
    private var wake: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var ending = false
    internal open fun reminders() = LiveReminders(this)
    internal open fun output(problem:(String)->Unit, ended:()->Unit):AudioOutput = LocalAudio(this,problem,ended) {val s=session;val a=s?.members?.values?.firstOrNull();s!=null && a!=null && SystemClock.elapsedRealtime()<s.deadline && reminders().ringing(a)!=null}
    private val tick = object : Runnable {
        override fun run() {
          try {
            val s = session ?: return
            if(SystemClock.elapsedRealtime() >= s.deadline) { finishAll("UNANSWERED", "Five-minute session deadline; no outcome recorded."); return }
            s.members.values.toList().forEach { a ->
                if(reminders().ringing(a) == null) {
                    reminders().endRinging(a, "RING_BLOCKED", "Ringing prerequisites changed; no routine failure.")
                    remove(a.id)
                }
            }
            if(session != null) handler.postDelayed(this, 1000)
          } catch(e:Exception) {finishAll("RING_BLOCKED","Health recheck failed: ${e.message}")}
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = try { startChecked(intent,startId) } catch(e:Exception) {finishAll("RING_BLOCKED","Service validation failed: ${e.message}");START_NOT_STICKY}
    private fun startChecked(intent:Intent?, startId:Int):Int {
        val id = intent?.getStringExtra("occurrence"); val token = intent?.getStringExtra("token")
        if(token != null) starting -= token
        val bridge = reminders()
        val alert = id?.let { bridge.state().active[it] }?.takeIf { it.token == token }
        if(alert == null || bridge.ringing(alert) == null) {
            if(alert != null) bridge.endRinging(alert, "RING_BLOCKED", "Service rejected stale or unavailable alarm.")
            if(session == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val existing = session
            if(existing == null) {
                ending = false
                session = RingSession(UUID.randomUUID().toString(), SystemClock.elapsedRealtime()+CAP_MS)
                running = this
            }
            val s = session!!
            s.members[alert.id]?.takeIf {it.token!=alert.token}?.let {if(playing==alert.id)releaseAudio();s.members.remove(alert.id);soundPins.remove(it.token)?.close()}
            if(!s.add(alert, SystemClock.elapsedRealtime())) {
                if(s.members[alert.id]?.token != alert.token) bridge.endRinging(alert, "RING_QUEUE_BLOCKED", "Session queue full or deadline reached; no alert silently dropped.")
                if(s.members.isEmpty()) finishAll("UNANSWERED", "Session expired.")
                return START_NOT_STICKY
            }
            // Enter foreground BEFORE focus. Do not rotate the stable user-owned channel.
            val presenting = playing == null
            val notification = notification(newPresentation = presenting)
            if(Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION, notification)
            if(alert.token !in soundPins) PrivateSounds.pin(bridge.config(FlexiblePlanner.parts(alert.id).first).sound)?.let {soundPins[alert.token]=it}
            if(existing == null) {
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DailyRhythm:ring-session").apply { setReferenceCounted(false); acquire((s.deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)) }
                handler.post(tick)
            }
            bridge.audioHealth(alert.id, if(playing == null) "RING_STARTING" else "RING_QUEUED", "Shared fixed five-minute session deadline. Queued alerts do not extend it.")
            if(playing == null) playNext()
        } catch(e: Exception) { finishAll("RING_BLOCKED", "Foreground/audio start failed: ${e.message}") }
        return START_NOT_STICKY
    }
    private fun notification(newPresentation: Boolean = false): Notification {
        val s = requireNotNull(session); val a = s.members.values.first()
        val occurrence = reminders().ringing(a)?.first
        val title = occurrence?.title ?: "Daily Reminders"
        val storage = Preferences(this)
        val minutes = preferredSnooze(storage)
        fun action(label: String, op: String): NotificationCompat.Action {
            val i = AndroidLivePlatform.intent(this, a, op).putExtra("ring_session", s.token)
            val pi = PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Action.Builder(0, label, pi).build()
        }
        val open = AndroidLivePlatform.openIntent(this, a)
        val snooze = if (storage.get(SNOOZE_PREFERENCE) == ASK_SNOOZE)
            NotificationCompat.Action.Builder(0, "Snooze…", AndroidLivePlatform.openIntent(this, a, snooze = true)).build()
        else action("Snooze ${minutes}m", "snooze$minutes")
        val fullScreen = if (newPresentation && usesFullScreen(a) && alarmPresentationAllowed(this)) {
            val target = Intent(this, AlarmActivity::class.java)
                .setData(android.net.Uri.parse("dailyrhythm://ringing/${s.token}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle() else null
            PendingIntent.getActivity(this, 0, target, PendingIntent.FLAG_IMMUTABLE, options)
        } else null
        val stop = action("Stop", "stop")
        val done = if (occurrence?.version?.kind == ItemKind.ROUTINE) action("Done", "done") else null
        val publicControls = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_rhythm).setContentTitle("Daily Reminders alarm")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setSilent(true).setOngoing(true)
            .apply { done?.let { addAction(it) } }
            .addAction(snooze).addAction(stop).build()
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_rhythm).setContentTitle(title)
            .setContentText(if (s.members.size == 1) "Alarm active" else "Alarm active · ${s.members.size - 1} more queued")
            .setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicControls)
            .setCategory(NotificationCompat.CATEGORY_ALARM).setOngoing(true).setOnlyAlertOnce(!newPresentation)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(PendingIntent.getBroadcast(this,0,AndroidLivePlatform.intent(this,a,"dismiss").putExtra("ring_session",s.token),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .apply { done?.let { addAction(it) } }
            .addAction(snooze).addAction(stop)
        if (fullScreen != null) notification.setFullScreenIntent(fullScreen, true)
        return notification.build()
    }
    private fun playNext() {
        val s = session ?: return
        if(SystemClock.elapsedRealtime() >= s.deadline) { finishAll("UNANSWERED", "Five-minute deadline reached."); return }
        val a = s.members.values.firstOrNull() ?: run { shutdown(); return }
        val pair = reminders().ringing(a) ?: run { reminders().endRinging(a, "RING_BLOCKED", "Alert invalidated before playback."); remove(a.id); return }
        playing = a.id
        audio = output({ detail -> if(session?.token==s.token && session?.members?.get(a.id)?.token==a.token)reminders().audioHealth(a.id, "AUDIO_HEALTH", detail) }, {
            if(session?.token==s.token && playing==a.id && session?.members?.get(a.id)?.token==a.token)finishAll("RING_STOPPED", "Audio ended, failed or lost focus; no automatic restart.")
        })
        audio?.play(pair.second.sound, true)
        if(session != null && playing == a.id && pair.second.vibrate) {
            @Suppress("DEPRECATION") val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator = v
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 900), 0))
        }
    }
    private fun updateNotification(newPresentation: Boolean):Boolean = try {
        NotificationManagerCompat.from(this).notify(NOTIFICATION,notification(newPresentation));true
    } catch(e:SecurityException) {
        finishAll("RING_BLOCKED","Notification permission changed; stopped.");false
    } catch(e:Exception) {
        finishAll("RING_BLOCKED","Notification controls unavailable: ${e.message}");false
    }
    private fun releaseAudio() { runCatching {audio?.close()}; audio = null; playing = null; runCatching {vibrator?.cancel()}; vibrator = null }
    private fun remove(id: String) {
        val s = session ?: return
        val removed=s.members.remove(id) ?: return
        if(playing == id) releaseAudio()
        soundPins.remove(removed.token)?.close()
        if (s.members.isEmpty()) shutdown()
        else {
            val presenting = playing == null
            if (!updateNotification(presenting)) return
            if (presenting) playNext()
        }
    }
    private fun finishAll(status: String, detail: String) {
        if(ending) return
        ending = true
        session?.members?.values?.toList()?.forEach { a -> runCatching { reminders().endRinging(a, status, detail) } }
        shutdown()
    }
    private fun shutdown() {
        releaseAudio();soundPins.values.forEach {runCatching {it.close()}};soundPins.clear(); handler.removeCallbacksAndMessages(null)
        wake?.let {runCatching { if(it.isHeld) it.release() }}; wake = null
        session = null; if(running === this) running = null
        runCatching {stopForeground(STOP_FOREGROUND_REMOVE)}; runCatching {stopSelf()}
    }
    override fun onTaskRemoved(rootIntent: Intent?) { finishAll("RING_STOPPED", "App task removed; no automatic restart.") }
    override fun onDestroy() { finishAll("RING_INTERRUPTED", "Service destroyed; no automatic resurrection."); super.onDestroy() }
}
