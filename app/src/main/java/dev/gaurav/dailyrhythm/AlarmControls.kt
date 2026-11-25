package dev.gaurav.dailyrhythm

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** In-app session view only, never fullscreen/overlay and never starts or resurrects a service. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlarmControls(bridge:LiveReminders,onDismiss:()->Unit,onNotice:(String)->Unit,onChange:()->Unit,onOpen:(LiveAlert)->Unit) {
    var tick by remember {mutableIntStateOf(0)}
    var showDiagnostics by remember { mutableStateOf(false) }
    LaunchedEffect(Unit){while(true){delay(1000);tick++}}
    val loaded=remember(tick){runCatching {bridge.state()}}
    val snapshot=RingingService.snapshot()
    val rows=loaded.getOrNull()?.let {s->snapshot?.let {view->runCatching {AlarmPresentation.rows(view,s){a->bridge.ringing(a)?.first?.title ?: "Alert"}}.getOrDefault(emptyList())}}.orEmpty()
    ReminderFormSheet("Alarm session & health", onDismiss, footer = {
        TextButton(onClick = onDismiss) { Text("Close") }
    }) {
        loaded.exceptionOrNull()?.let {Text("Health unavailable: ${it.message}. Nothing was reset.")}
        if(snapshot==null)Text("No active alarm")
        else if(rows.isEmpty())Text("Alarm controls are temporarily unavailable.")
        else {
            val left=((snapshot.deadlineElapsed-SystemClock.elapsedRealtime()).coerceAtLeast(0)+999)/1000
            Text("Stops automatically in ${left/60}:${(left%60).toString().padStart(2,'0')}", style = MaterialTheme.typography.bodyMedium)
            rows.forEach {row->
                Text("${row.status} · ${row.title}",style=MaterialTheme.typography.titleMedium)
                fun perform(op: String) {
                    runCatching { bridge.sessionAction(row.alert.id, row.alert.token, snapshot.sessionToken, op) }
                        .onSuccess { onChange() }.onFailure { onNotice(it.message ?: "Alert action failed.") }
                    tick++
                }
                var moreSnooze by remember(row.alert.token) { mutableStateOf(false) }
                Column {
                    Button(enabled=left>0,onClick={perform("stop")}){Text("Stop alarm")}
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(enabled = left > 0, onClick = { perform("snooze5") }) { Text("Snooze 5 min") }
                        TextButton(enabled = left > 0, onClick = { perform("snooze10") }) { Text("Snooze 10 min") }
                    }
                    TextButton(enabled = left > 0, onClick = { moreSnooze = !moreSnooze }) {
                        Text(if (moreSnooze) "Hide snooze options" else "More snooze options")
                    }
                    if (moreSnooze && left > 0) SnoozeOptions { until ->
                        runCatching { bridge.snooze(row.alert, until, snapshot.sessionToken) }
                            .onSuccess { onChange() }.onFailure { onNotice(it.message ?: "Couldn’t snooze alarm.") }
                        tick++
                    }
                    TextButton(enabled=left>0,onClick={runCatching {
                        if(bridge.state().active[row.alert.id]?.token==row.alert.token && RingingService.accepts(row.alert,snapshot.sessionToken))onOpen(row.alert)
                        else onNotice("Alert changed; no stale content was opened.")
                    }.onFailure {onNotice(it.message ?: "Could not open item; history was not reset.")}}){Text("Open reminder")}
                    if (bridge.ringing(row.alert)?.first?.version?.kind == ItemKind.ROUTINE) {
                        FilledTonalButton(enabled = left > 0, onClick = { perform("done") }) { Text("Done") }
                    }
                }
            }
        }
        if (snapshot == null && loaded.getOrNull()?.events?.isNotEmpty() == true) {
            TextButton(onClick = { showDiagnostics = !showDiagnostics }) { Text(if (showDiagnostics) "Hide diagnostics" else "Show diagnostics") }
            if (showDiagnostics) loaded.getOrNull()?.events?.takeLast(8)?.asReversed()?.forEach {
                Text("${it.status} · ${it.detail}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
