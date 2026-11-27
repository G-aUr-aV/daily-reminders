package dev.gaurav.dailyrhythm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun shortDate(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))

@Composable
internal fun TodayAgenda(rows: List<PlannedOccurrence>, now: Instant, today: LocalDate, zone: ZoneId,
                         live: LiveState, onOpen: (String) -> Unit, onDone: (String) -> Unit, onSnooze: (LiveAlert) -> Unit) {
    var showLogged by rememberSaveable(rows.firstOrNull()?.date?.toString()) { mutableStateOf(false) }
    val (pending, logged) = rows.partition { it.state == HistoryState.UNLOGGED }
    val (due, upcoming) = pending.partition { it.dueAt(zone) <= now }
    listOf("Needs attention" to due, "Upcoming" to upcoming).forEach { (label, group) ->
        if (group.isNotEmpty()) {
            Text(label, Modifier.padding(top = 16.dp, bottom = 6.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            group.forEach { row ->
                val snooze = live.pending[row.id]?.takeIf { it.snooze }
                val status = when {
                    row.id in live.active -> "Alert active"
                    snooze != null -> "Snoozed until ${clockLabel(snooze.trigger.atZone(zone).toLocalTime())}"
                    row.date < today -> "No record"
                    else -> null
                }
                key(row.id) {
                    val done = if (row.date == today && row.version.kind == ItemKind.ROUTINE) ({ onDone(row.id) }) else null
                    val active = live.active[row.id]
                    val snoozeAction = active?.let { alert -> { onSnooze(alert) } }
                    AgendaSwipe(done, snoozeAction) { onComplete ->
                        TodayAgendaRow(row, status, { onOpen(row.id) }, onComplete)
                    }
                }
            }
        }
    }
    if (logged.isNotEmpty()) {
        TextButton(onClick = { showLogged = !showLogged }, modifier = Modifier.padding(top = 8.dp)) {
            Text(if (showLogged) "Hide logged" else "Logged (${logged.size})")
            Spacer(Modifier.width(4.dp))
            val rotation by animateFloatAsState(if (showLogged) 180f else 0f, tween(160), label = "Logged disclosure")
            Box(Modifier.graphicsLayer { rotationZ = rotation }) { ReminderIcon(ReminderGlyph.EXPAND) }
        }
        AnimatedVisibility(visible = showLogged) {
            Column {
                logged.forEach { row ->
                    TodayAgendaRow(row, QuietPresentation.stateLabel(row.state), { onOpen(row.id) }, null)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaSwipe(done: (() -> Unit)?, snooze: (() -> Unit)?, content: @Composable ((() -> Unit)?) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val complete: (() -> Unit)? = done?.let { action ->
        { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); action() }
    }
    if (complete == null && snooze == null) {
        content(null)
        return
    }
    val currentDone by rememberUpdatedState(complete)
    val currentSnooze by rememberUpdatedState(snooze)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.StartToEnd -> currentDone?.invoke()
            SwipeToDismissBoxValue.EndToStart -> currentSnooze?.invoke()
            SwipeToDismissBoxValue.Settled -> Unit
        }
        false
    })
    SwipeToDismissBox(state, enableDismissFromStartToEnd = complete != null, enableDismissFromEndToStart = snooze != null,
        backgroundContent = {
            val start = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            if (state.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Surface(Modifier.fillMaxSize(), color = if (start) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        horizontalArrangement = if (start) Arrangement.Start else Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        ReminderIcon(if (start) ReminderGlyph.COMPLETE else ReminderGlyph.ALARM)
                        Text(if (start) " Done" else " Snooze")
                    }
                }
            }
        }) { Surface(color = MaterialTheme.colorScheme.background) { content(complete) } }
}

@Composable
private fun TodayAgendaRow(row: PlannedOccurrence, status: String?, onOpen: () -> Unit, onDone: (() -> Unit)?) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onDone != null) {
                IconButton(onClick = onDone,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Mark ${row.title} done" },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                    ReminderIcon(ReminderGlyph.PENDING)
                }
            } else if (row.state == HistoryState.DONE || row.state == HistoryState.LATE) {
                Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { ReminderIcon(ReminderGlyph.COMPLETE) }
                }
            }
            Row(Modifier.weight(1f).clickable(onClick = onOpen).heightIn(min = 60.dp)
                .padding(start = if (onDone == null) 4.dp else 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    row.version.category?.let { CategoryLabel(it) }
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(clockLabel(row.time), Modifier.widthIn(max = 84.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HabitWeekSheet(end: LocalDate, rows: List<PlannedOccurrence>, now: Instant, zone: ZoneId, onDismiss: () -> Unit) {
    val groups = remember(rows) {
        rows.groupBy { it.itemId }.values.sortedBy { it.first().title.lowercase() }
    }
    var item by rememberSaveable { mutableStateOf<String?>(null) }
    var choose by remember { mutableStateOf(false) }
    val selected = groups.firstOrNull { it.first().itemId == item } ?: groups.firstOrNull()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietHeading("7-day summary", "${shortDate(end.minusDays(6))} – ${shortDate(end)}")
            if (selected == null) {
                Text("No reminders were planned in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (groups.size == 1) {
                    Text(selected.first().title, style = MaterialTheme.typography.titleMedium)
                } else Box {
                    EditorButton("Reminder", selected.first().title, Modifier.fillMaxWidth(), showChevron = true) { choose = true }
                    DropdownMenu(choose, onDismissRequest = { choose = false }) {
                        groups.forEach { group ->
                            val first = group.first()
                            DropdownMenuItem(text = { Text(first.title) }, onClick = { item = first.itemId; choose = false })
                        }
                    }
                }
                val summary = habitWeek(selected, now, zone)
                Text("${summary.done} of ${summary.planned} done", style = MaterialTheme.typography.headlineMedium)
                LinearProgressIndicator(progress = { summary.done.toFloat() / summary.planned }, modifier = Modifier.fillMaxWidth())
                Column {
                    LabeledRow("Skipped", summary.skipped.toString())
                    LabeledRow("No record", summary.unrecorded.toString())
                    if (summary.pending > 0) LabeledRow("Still open", summary.pending.toString())
                }
            }
        }
    }
}

@Composable
internal fun AgendaRow(title: String, subtitle: String? = null, time: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        time?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 86.dp))
        }
        ReminderIcon(ReminderGlyph.NEXT)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun LabeledRow(title: String, value: String? = null, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        value?.let {
            Text(it, modifier = Modifier.widthIn(max = 140.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) ReminderIcon(ReminderGlyph.NEXT)
    }
}

@Composable
internal fun EmptyLabel(title: String, hint: String) {
    Column(Modifier.padding(top = 32.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SetupAction(message: String, action: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderTimePicker(initial: LocalTime, onDismiss: () -> Unit, onAnytime: () -> Unit, onTime: (LocalTime) -> Unit) {
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour)
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val compact = config.screenWidthDp < 360 || config.fontScale > 1.4f || config.screenHeightDp < 520
    var type by rememberSaveable { mutableStateOf(compact) }
    fun formattedHour(hour: Int) = if (is24Hour) hour.toString().padStart(2, '0') else ((hour + 11) % 12 + 1).toString()
    var hourText by rememberSaveable { mutableStateOf(formattedHour(state.hour)) }
    var minuteText by rememberSaveable { mutableStateOf(state.minute.toString().padStart(2, '0')) }
    var afternoon by rememberSaveable { mutableStateOf(state.hour >= 12) }
    val hour = hourText.toIntOrNull()
    val minute = minuteText.toIntOrNull()
    val validHour = hour != null && hour in (if (is24Hour) 0..23 else 1..12)
    val validMinute = minute != null && minute in 0..59
    val compactInput = compact && type
    val selected = if (compactInput && validHour && validMinute) {
        val entered = requireNotNull(hour)
        LocalTime.of(if (is24Hour) entered else (entered % 12) + if (afternoon) 12 else 0, requireNotNull(minute))
    } else if (!compactInput) LocalTime.of(state.hour, state.minute) else null
    val colors = TimePickerDefaults.colors(
        clockDialColor = MaterialTheme.colorScheme.surfaceContainer,
        selectorColor = MaterialTheme.colorScheme.primary,
        containerColor = MaterialTheme.colorScheme.surface,
        timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        timeSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceContainer
    )
    ReminderDialog(if (type) "Enter time" else "Choose time", onDismiss, footer = {
        TextButton(onClick = onAnytime) { Text("Anytime") }
        Button(onClick = { selected?.let(onTime) }, enabled = selected != null) { Text("Set time") }
    }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                compactInput -> {
                    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(hourText, { hourText = it.filter(Char::isDigit).take(2) },
                            Modifier.weight(1f), label = { Text("Hour") }, singleLine = true,
                            textStyle = MaterialTheme.typography.headlineSmall, keyboardOptions = keyboard,
                            isError = hourText.isNotEmpty() && !validHour)
                        OutlinedTextField(minuteText, { minuteText = it.filter(Char::isDigit).take(2) },
                            Modifier.weight(1f), label = { Text("Minute") }, singleLine = true,
                            textStyle = MaterialTheme.typography.headlineSmall, keyboardOptions = keyboard,
                            isError = minuteText.isNotEmpty() && !validMinute)
                    }
                    if (!is24Hour) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(false, true).forEachIndexed { index, pm ->
                            SegmentedButton(selected = afternoon == pm, onClick = { afternoon = pm },
                                shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                                Text(if (pm) "PM" else "AM")
                            }
                        }
                    }
                }
                type -> TimeInput(state, colors = colors)
                else -> TimePicker(state, colors = colors, layoutType = TimePickerLayoutType.Vertical)
            }
            TextButton(onClick = {
                if (compactInput && selected != null) {
                    state.hour = selected.hour
                    state.minute = selected.minute
                } else if (!type && compact) {
                    hourText = formattedHour(state.hour)
                    minuteText = state.minute.toString().padStart(2, '0')
                    afternoon = state.hour >= 12
                }
                type = !type
            }) { Text(if (type) "Use clock dial" else "Type time") }
        }
    }
}
