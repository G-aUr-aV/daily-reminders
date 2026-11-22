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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

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
            val dark = isSystemInDarkTheme()
            val preferences = remember { Preferences(this) }
            var amoled by remember { mutableStateOf(startup.isSuccess && preferences.get("theme.amoled") == "true") }
            val fallback = if(dark)darkColorScheme(
                primary=Color(0xFFB8C6FF),onPrimary=Color(0xFF142959),primaryContainer=Color(0xFF293957),onPrimaryContainer=Color(0xFFE2E8FE),
                secondary=Color(0xFFBBC5DC),onSecondary=Color(0xFF202631),secondaryContainer=Color(0xFF323C55),onSecondaryContainer=Color(0xFFE2E8FE),
                tertiary=Color(0xFFB8C6FF),onTertiary=Color(0xFF142959),tertiaryContainer=Color(0xFF293957),onTertiaryContainer=Color(0xFFE2E8FE),
                background=Color(0xFF12151B),onBackground=Color(0xFFECEFF7),surface=Color(0xFF12151B),onSurface=Color(0xFFECEFF7),
                surfaceVariant=Color(0xFF282E3B),onSurfaceVariant=Color(0xFFB9C2D4),surfaceContainer=Color(0xFF1B202A),surfaceContainerLow=Color(0xFF171C25),surfaceContainerLowest=Color(0xFF101218),surfaceContainerHigh=Color(0xFF232A37),surfaceContainerHighest=Color(0xFF303848),outline=Color(0xFF858FA2),outlineVariant=Color(0xFF353D4C))
            else lightColorScheme(
                primary=Color(0xFF344CA8),onPrimary=Color.White,primaryContainer=Color(0xFFDCE4FF),onPrimaryContainer=Color(0xFF1E306B),
                secondary=Color(0xFF344CA8),onSecondary=Color.White,secondaryContainer=Color(0xFFE3E9FD),onSecondaryContainer=Color(0xFF202D55),
                tertiary=Color(0xFF344CA8),onTertiary=Color.White,tertiaryContainer=Color(0xFFDCE4FF),onTertiaryContainer=Color(0xFF1E306B),
                background=Color(0xFFF7F8FC),onBackground=Color(0xFF202631),surface=Color(0xFFF7F8FC),onSurface=Color(0xFF202631),
                surfaceVariant=Color(0xFFE8ECF5),onSurfaceVariant=Color(0xFF4A556A),surfaceContainer=Color(0xFFEDF0F7),surfaceContainerLow=Color(0xFFF0F2F8),surfaceContainerLowest=Color.White,surfaceContainerHigh=Color(0xFFE7EBF5),surfaceContainerHighest=Color(0xFFDCE2EF),outline=Color(0xFF687386),outlineVariant=Color(0xFFD7DDE9))
            val base = if (android.os.Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else fallback
            val colors = if (dark && amoled) base.copy(background = Color.Black, surface = Color.Black,
                surfaceContainerLowest = Color.Black) else base
            val type=Typography(
                headlineLarge=androidx.compose.ui.text.TextStyle(fontSize=34.sp,lineHeight=40.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold),
                headlineMedium=androidx.compose.ui.text.TextStyle(fontSize=26.sp,lineHeight=32.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold))
            MaterialTheme(colorScheme=colors,typography=type) {
                if(startup.isFailure) Surface {Text("Daily Reminders could not load reminders. Existing data was not reset. ${startup.exceptionOrNull()?.message}")}
                else ReminderDateHost {
                    val navigation = incomingIntent
                    DailyRhythmApp(navigation?.let { validatedNotificationOpen(this, it) }, amoledBlack = amoled,
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
