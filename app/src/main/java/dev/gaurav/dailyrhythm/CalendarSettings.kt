package dev.gaurav.dailyrhythm

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CalendarSettings(revision: Int, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val sync = remember(context) { CalendarSync(context) }
    var localRevision by remember { mutableIntStateOf(0) }
    val selected = remember(revision, localRevision) { runCatching { sync.target() }.getOrNull() }
    val status = remember(revision, localRevision) { sync.status() }
    var show by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    var accountChange by remember { mutableStateOf<CalendarTarget?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var calendars by remember { mutableStateOf<List<CalendarTarget>?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CalendarSync.allowed(context)) {
            denied = false
            pick = true
        } else denied = true
    }
    fun choose() {
        show = false
        if (CalendarSync.allowed(context)) pick = true
        else permission.launch(CalendarSync.permissions)
    }
    fun save(target: CalendarTarget?) {
        runCatching { sync.select(target); CalendarSync.publishSoon(context) }.onSuccess {
            localRevision++
            pick = false
            disconnect = false
            accountChange = null
        }.onFailure { onNotice("Couldn’t save the calendar connection. Try again.") }
    }
    LaunchedEffect(pick) {
        if (pick) {
            calendars = null
            calendars = withContext(Dispatchers.IO) { runCatching { sync.calendars() }.getOrNull() } ?: emptyList()
        }
    }

    LabeledRow("Calendar sync", selected?.name ?: "Off", onClick = { show = true })
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("Calendar sync") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selected == null) Text("Choose a calendar from your phone. Daily Reminders will add reminder titles, times and outcomes automatically.")
                else {
                    Text(selected.name, style = MaterialTheme.typography.titleMedium)
                    Text(selected.account, style = MaterialTheme.typography.bodyMedium)
                    Text(if (CalendarSync.allowed(context)) status.orEmpty() else "Calendar permission needed")
                    Text("Repeating reminders appear up to 30 days ahead. Changes start in Daily Reminders.")
                    if (CalendarSync.allowed(context)) {
                        LabeledRow("Sync now", onClick = {
                            runCatching { sync.request(); CalendarSync.publishSoon(context) }.onSuccess { show = false; onNotice("Calendar sync requested.") }
                                .onFailure { onNotice("Couldn’t request calendar sync. Try again.") }
                        })
                        LabeledRow("Disconnect", onClick = { show = false; disconnect = true })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::choose) { Text(if (selected == null) "Choose calendar" else "Change calendar") } },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Close") } }
    )
    if (pick) AlertDialog(
        onDismissRequest = { pick = false },
        title = { Text("Choose calendar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val choices = calendars
                when {
                    choices == null -> Text("Looking for calendars…")
                    choices.isEmpty() -> Text("No writable calendars found. Add an account or turn on calendar sync in Android Settings.")
                    else -> choices.forEach { target ->
                        Column {
                            LabeledRow(target.name.ifBlank { "Calendar" }, onClick = {
                                if (selected == null || selected.isSameCalendar(target)) save(target)
                                else { pick = false; accountChange = target }
                            })
                            Text(target.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { pick = false }) { Text("Cancel") } }
    )
    if (denied) AlertDialog(
        onDismissRequest = { denied = false },
        title = { Text("Allow calendar access") },
        text = { Text("Daily Reminders needs access to list your calendars and update the reminders it adds.") },
        confirmButton = { TextButton(onClick = {
            denied = false
            openSystem(context, systemActivity(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"))))
        }) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = { denied = false }) { Text("Cancel") } }
    )
    if (disconnect || accountChange != null) AlertDialog(
        onDismissRequest = { disconnect = false; accountChange = null },
        title = { Text(if (disconnect) "Disconnect calendar?" else "Change calendar?") },
        text = { Text("Daily Reminders will remove its upcoming events from ${selected?.name ?: "the old calendar"}. Past events stay there.") },
        confirmButton = { TextButton(onClick = { save(accountChange) }) { Text(if (disconnect) "Disconnect" else "Change") } },
        dismissButton = { TextButton(onClick = { disconnect = false; accountChange = null }) { Text("Cancel") } }
    )
}
