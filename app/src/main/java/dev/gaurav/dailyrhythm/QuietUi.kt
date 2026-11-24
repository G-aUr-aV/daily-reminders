package dev.gaurav.dailyrhythm

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable internal fun clockLabel(time: LocalTime?): String {
    if(time == null) return "Anytime"
    val format = if(DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a"
    return time.format(DateTimeFormatter.ofPattern(format))
}
internal fun dayLabel(date:LocalDate):String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))

internal enum class ReminderGlyph(val resource: Int) {
    ALARM(R.drawable.ic_reminder_alarm),
    LIST(R.drawable.ic_reminder_list),
    HISTORY(R.drawable.ic_reminder_history),
    SETTINGS(R.drawable.ic_reminder_settings),
    BACK(R.drawable.ic_reminder_back),
    ADD(R.drawable.ic_reminder_add),
    NEXT(R.drawable.ic_reminder_next),
    MORE(R.drawable.ic_reminder_more),
    EXPAND(R.drawable.ic_reminder_expand),
    PREVIOUS(R.drawable.ic_reminder_previous),
    DRAG(R.drawable.ic_reminder_drag),
    PENDING(R.drawable.ic_reminder_pending),
    COMPLETE(R.drawable.ic_reminder_complete)
}

@Composable
internal fun ReminderIcon(glyph: ReminderGlyph) {
    Icon(androidx.compose.ui.res.painterResource(glyph.resource), contentDescription = null)
}

@Composable internal fun QuietHeading(text:String, supporting:String?=null) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(text,style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics { heading() })
        supporting?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

/** Native full-height route, not a scrolling alert dialog. Global alert controls remain reachable. */
@Composable internal fun QuietRoute(
    title: String, onBack: () -> Unit, alarmActive: Boolean, onAlarm: () -> Unit,
    footer: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val safeInsets = WindowInsets.safeDrawing.union(WindowInsets.safeGestures.only(WindowInsetsSides.Bottom))
        Column(Modifier.fillMaxSize().windowInsetsPadding(safeInsets)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) { ReminderIcon(ReminderGlyph.BACK) }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            }
            if (alarmActive) AlertAccess(onAlarm)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) { footer() }
        }
    }
}

@Composable internal fun AlertAccess(onOpen:()->Unit) {
    Surface(color=MaterialTheme.colorScheme.errorContainer,modifier=Modifier.fillMaxWidth()) {
        TextButton(onClick=onOpen,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            Text("Active alarm · Open Stop / Snooze",color=MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable internal fun RitualReading(content:RitualDay,date:LocalDate,onBack:()->Unit,onRound:(Int)->Boolean,
                                      alarmActive:Boolean,onAlarm:()->Unit) {
    var saving by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    fun save(delta:Int) {
        if(saving) return
        saving=true
        if(!onRound(delta)) error="Couldn't save. Your previous progress is still here." else error=null
        saving=false
    }
    QuietRoute("Ritual",onBack,alarmActive,onAlarm,footer={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            if(content.rounds < content.target) Button(onClick={save(1)},enabled=!saving,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                Text("I read this round · ${content.rounds+1} of ${content.target}")
            } else Button(onClick=onBack,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("Back to day")}
            if(content.rounds>0) TextButton(onClick={save(-1)},enabled=!saving,modifier=Modifier.fillMaxWidth()) {Text("Undo one round")}
        }
    }) {
        Text(dayLabel(date),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        QuietHeading(content.title)
        Text(if(content.rounds>=content.target) "${content.rounds} of ${content.target} rounds recorded" else "Round ${content.rounds+1} of ${content.target}",style=MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress={content.rounds.toFloat()/content.target},modifier=Modifier.fillMaxWidth().semantics {contentDescription="${content.rounds} of ${content.target} rounds recorded"})
        SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(28.dp),modifier=Modifier.padding(vertical=12.dp)) {
            content.lines.forEachIndexed { index,line ->
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text((index+1).toString().padStart(2,'0'),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                    Text(line,style=MaterialTheme.typography.headlineSmall)
                }
            }
        }}
    }
}

internal val gentleDraftSaver = androidx.compose.runtime.saveable.listSaver<GentleConfig, Any>(
    save={listOf(it.enabled,it.precise,it.ringing,it.sound,it.vibrate,it.toneTitle,it.fullScreen)},
    restore={GentleConfig(it[0] as Boolean,it[1] as Boolean,it[2] as Boolean,it[3] as String,it[4] as Boolean,it[5] as String,it.getOrNull(6) as? Boolean ?: true)}
)

/** One field per value; picker and manual input share the same saved draft string. */
@Composable internal fun QuietPickerField(label:String,value:String,display:String,hint:String,onValue:(String)->Unit,onPick:()->Unit) {
    var manual by androidx.compose.runtime.saveable.rememberSaveable(label) {mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(0.dp)) {
        OutlinedTextField(value=if(manual)value else display,onValueChange=onValue,readOnly=!manual,
            modifier=Modifier.fillMaxWidth(),label={Text(label)},
            supportingText=if(manual) {{Text(hint)}} else null,
            trailingIcon={TextButton(onClick=onPick) {Text("Pick")}})
        TextButton(onClick={manual=!manual}) {Text(if(manual)"Use picker for ${label.lowercase()}" else "Type ${label.lowercase()} manually")}
    }
}
