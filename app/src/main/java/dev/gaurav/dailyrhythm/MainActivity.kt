package dev.gaurav.dailyrhythm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*

internal fun notificationSettingsIntent(context:Context,channel:Boolean):Intent? = notificationSettingsIntent(context,if(channel)ReminderScheduler.CHANNEL else null)
internal fun notificationSettingsIntent(context:Context,channel:String?):Intent? {
    val intent=Intent(if(channel!=null)Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
    if(channel!=null)intent.putExtra(Settings.EXTRA_CHANNEL_ID,channel)
    return systemActivity(context,intent)
}
internal fun systemActivity(context:Context,intent:Intent):Intent? {
    val activity=context.packageManager.resolveActivity(intent,PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo ?: return null
    if(!activity.exported || activity.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)==0)return null
    return intent.setComponent(ComponentName(activity.packageName,activity.name))
}
internal fun openSystem(context:Context,intent:Intent?) {
    try {
        if(intent==null)Toast.makeText(context,"System settings unavailable. Open Android settings manually.",Toast.LENGTH_LONG).show()
        else context.startActivity(intent)
    }catch(_:RuntimeException){Toast.makeText(context,"System settings could not be opened. No alternate handler used.",Toast.LENGTH_LONG).show()}
}
internal fun validatedNotificationOpen(context:Context,intent:Intent):String? {
    val id=intent.getStringExtra(AndroidLivePlatform.OPEN) ?: return null
    if(id.length>255)return null
    val data=intent.data ?: return null
    if(data.scheme!="dailyrhythm" || data.authority!="open" || data.pathSegments.size!=1)return null
    val token=runCatching {LiveStore(Preferences(context)).read()?.active?.get(id)?.token}.getOrNull() ?: return null
    return id.takeIf {token==data.lastPathSegment}
}
class MainActivity:ComponentActivity() {
    private var incomingIntent by mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntent = intent
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        incomingIntent = intent
        val startup=runCatching {LiveReminders(this).apply {sync(AlarmSync.RESTORE_PENDING);recoverInterruptedRinging()}; PrivateSounds(this).cleanTemps()}
        if (startup.isSuccess) runCatching { CalendarSync.planChanged(this) }
        enableEdgeToEdge()
        @Suppress("DEPRECATION")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            val preferences = remember { Preferences(this) }
            var theme by remember { mutableStateOf(if (startup.isSuccess) Appearance.read(preferences) else Appearance.SYSTEM) }
            val dark = when (theme) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.LIGHT -> false
                Appearance.DARK -> true
            }
            SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            var amoled by remember { mutableStateOf(startup.isSuccess && preferences.get("theme.amoled") == "true") }
            AgendaTheme(dark = dark, pureBlack = amoled) {
                if(startup.isFailure) Surface {Text("Daily Reminders could not load reminders. Existing data was not reset. ${startup.exceptionOrNull()?.message}")}
                else ReminderDateHost {
                    val navigation = incomingIntent
                    DailyRhythmApp(navigation?.let { validatedNotificationOpen(this, it) }, amoledBlack = amoled,
                        appearance = theme, onAppearanceChange = { selected ->
                            preferences.put(Appearance.KEY, selected.name)
                            theme = selected
                        },
                        openCreator = navigation?.takeIf { it.action == DailyRemindersWidget.OPEN_CREATOR },
                        notificationRequest = navigation,
                        onIntentOpened = {
                            incomingIntent = null
                            setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                        }) { enabled ->
                        preferences.put("theme.amoled", enabled.toString())
                        amoled = enabled
                    }
                }
            }
        }
    }
}
