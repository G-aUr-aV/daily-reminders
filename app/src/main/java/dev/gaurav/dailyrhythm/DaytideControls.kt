package dev.gaurav.dailyrhythm

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

internal val LocalPickDay = staticCompositionLocalOf<(LocalDate, (LocalDate) -> Unit) -> Unit> {
    error("Date picker host is missing.")
}

@Composable
internal fun ReminderDateHost(content: @Composable () -> Unit) {
    var request by remember { mutableStateOf<Pair<LocalDate, (LocalDate) -> Unit>?>(null) }
    val pick: (LocalDate, (LocalDate) -> Unit) -> Unit = { day, result -> request = day to result }
    CompositionLocalProvider(LocalPickDay provides pick, content = content)
    request?.let { (day, result) ->
        ReminderCalendar(day, onDismiss = { request = null }, onPick = { request = null; result(it) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReminderDialog(title: String, onDismiss: () -> Unit,
                            footer: @Composable () -> Unit,
                            content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
            .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            val available = maxHeight
            Surface(Modifier.widthIn(max = 430.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.heightIn(max = available).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 4.dp))
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) { footer() }
                }
            }
        }
    }
}

@Composable
private fun ReminderCalendar(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    var entering by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(initial.toString()) }
    val locale = Locale.getDefault()
    val config = LocalConfiguration.current
    val compact = config.screenWidthDp < 360 || config.fontScale > 1.4f || config.screenHeightDp < 600
    val first = WeekFields.of(locale).firstDayOfWeek
    val monthStart = month.atDay(1)
    val leading = (monthStart.dayOfWeek.value - first.value + 7) % 7
    val textDate = if (entering) runCatching { LocalDate.parse(text) }.getOrNull() else selected
    ReminderDialog("Date", onDismiss, footer = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Button(enabled = textDate != null, onClick = { textDate?.let(onPick) }) { Text("Choose date") }
    }) {
        Text(if (compact) shortDate(textDate ?: selected) else dayLabel(textDate ?: selected),
            style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
        if (entering) {
            OutlinedTextField(text, { text = it.take(10) }, singleLine = true, label = { Text("Date (YYYY-MM-DD)") },
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions.Default)
            TextButton(onClick = {
                textDate?.let { selected = it; month = YearMonth.from(it) }
                entering = false
            }) { Text("Show calendar") }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(month.format(DateTimeFormatter.ofPattern(if (compact) "LLL yyyy" else "LLLL yyyy", locale)), Modifier.weight(1f).padding(start = 4.dp),
                    style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { month = month.minusMonths(1) }, modifier = Modifier.semantics {
                    contentDescription = "Previous month"
                }) { ReminderIcon(ReminderGlyph.PREVIOUS) }
                IconButton(onClick = { month = month.plusMonths(1) }, modifier = Modifier.semantics {
                    contentDescription = "Next month"
                }) { ReminderIcon(ReminderGlyph.NEXT) }
            }
            Column {
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { index ->
                        Box(Modifier.weight(1f).heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
                            Text(first.plus(index.toLong()).getDisplayName(TextStyle.NARROW, locale),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                repeat((leading + month.lengthOfMonth() + 6) / 7) { week ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { column ->
                            val day = week * 7 + column - leading + 1
                            if (day in 1..month.lengthOfMonth()) {
                                val date = month.atDay(day)
                                val active = date == selected
                                val colors = MaterialTheme.colorScheme
                                val shape = RoundedCornerShape(12.dp)
                                Surface(Modifier.weight(1f).heightIn(min = 46.dp).padding(1.dp), shape = shape,
                                    color = if (active) colors.primary else colors.surfaceContainerHigh,
                                    contentColor = if (active) colors.onPrimary else colors.onSurface) {
                                    Box(Modifier.clip(shape)
                                        .selectable(selected = active, onClick = { selected = date }, role = Role.RadioButton)
                                        .semantics { contentDescription = dayLabel(date) }, contentAlignment = Alignment.Center) {
                                        Text(day.toString(), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            } else Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { selected = LocalDate.now(); month = YearMonth.from(selected) }) { Text("Today") }
                TextButton(onClick = { text = selected.toString(); entering = true }) { Text("Type date") }
            }
        }
    }
}

@Composable
internal fun EditorGroup(title: String, framed: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
        if (framed) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().animateContentSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
            }
        } else Column(Modifier.fillMaxWidth().animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
internal fun EditorButton(label: String, value: String, modifier: Modifier = Modifier,
                          showChevron: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(if (showChevron) Modifier.weight(1f) else Modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleSmall)
            }
            if (showChevron) ReminderIcon(ReminderGlyph.NEXT)
        }
    }
}

internal data class ReminderChoice<T>(val value: T, val label: String, val help: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceSheet(title: String, selected: T, choices: List<ReminderChoice<T>>,
                              onDismiss: () -> Unit, onPick: (T) -> Unit, groupBreak: Int? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            choices.forEachIndexed { index, option ->
                if (index == groupBreak) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Day types", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 50.dp)
                    .selectable(selected = selected == option.value, onClick = { onPick(option.value) }, role = Role.RadioButton)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.titleMedium)
                        option.help?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    RadioButton(selected == option.value, onClick = null)
                }
            }
        }
    }
}

internal enum class EditorAlert(val label: String, val help: String) {
    OFF("Off", "Keep it on your list"),
    NOTIFICATION("Notification", "A regular Android alert"),
    RINGING("Ringing notification", "Keep ringing with Stop and Snooze in the notification"),
    FULL_SCREEN("Full-screen alarm", "Open alarm controls over the lock screen or other apps");

    companion object {
        fun from(config: GentleConfig) = when {
            !config.enabled -> OFF
            !config.ringing -> NOTIFICATION
            config.fullScreen -> FULL_SCREEN
            else -> RINGING
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DurationDialog(minutes: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val presets = listOf(5, 15, 30, 60)
    var text by remember { mutableStateOf(minutes.toString()) }
    var custom by remember { mutableStateOf(minutes !in presets) }
    val amount = text.toIntOrNull()?.takeIf { it in 1..1440 }
    ReminderDialog("Calendar duration", onDismiss, footer = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Button(onClick = { amount?.let(onSave) }, enabled = amount != null) { Text("Set duration") }
    }) {
        Text("Sets the end time of the calendar event.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in presets) FilterChip(selected = !custom && amount == value,
                onClick = { custom = false; text = value.toString() }, label = { Text(value.toString()) })
        }
        if (custom) OutlinedTextField(text, { text = it.filter(Char::isDigit).take(4) }, label = { Text("Minutes") },
            singleLine = true, keyboardOptions = KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        else TextButton(onClick = { custom = true }) { Text("Custom minutes") }
    }
}
