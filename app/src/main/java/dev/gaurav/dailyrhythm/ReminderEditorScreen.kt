package dev.gaurav.dailyrhythm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun ReminderEditorScreen(
    initial: PlanVersion?, editing: Boolean, newDay: LocalDate, initialReminder: GentleConfig, bridge: LiveReminders, alarmActive: Boolean,
    onAlarm: () -> Unit, onDismiss: () -> Unit, onAndroidSound: (() -> Unit)?, onSave: (PlanVersion, GentleConfig) -> Unit
) {
    val today = LocalDate.now()
    val initialRepeat = initial?.rule?.kind ?: RepeatKind.ONCE
    val initialDate = (initial?.from?.coerceAtLeast(today) ?: newDay).toString()
    val initialOnce = (initial?.rule?.oneDate ?: newDay).toString()
    val initialWeekdays = initial?.rule?.weekdays?.sortedBy { it.value }?.joinToString(",") { it.value.toString() }.orEmpty()
    val initialMonths = (initial?.rule?.monthDays?.sorted()?.map(Int::toString).orEmpty() +
        if (initial?.rule?.lastDay == true) listOf("last") else emptyList()).joinToString(",")
    val initialContent = initial?.content?.joinToString("\n").orEmpty()
    val initialTarget = (initial?.target ?: 1).toString()
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(initial?.category) }
    var checklist by rememberSaveable { mutableStateOf(initial?.checklist.orEmpty().joinToString("\n")) }
    var checklistOpen by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: ItemKind.ROUTINE) }
    var repeat by rememberSaveable { mutableStateOf(initialRepeat) }
    var time by rememberSaveable { mutableStateOf(initial?.time?.toString().orEmpty()) }
    var effective by rememberSaveable { mutableStateOf(initialDate) }
    var oneDate by rememberSaveable { mutableStateOf(initialOnce) }
    var weekdays by rememberSaveable { mutableStateOf(initialWeekdays) }
    var monthly by rememberSaveable { mutableStateOf(initialMonths) }
    var missing by rememberSaveable { mutableStateOf(initial?.rule?.missing) }
    var content by rememberSaveable { mutableStateOf(initialContent) }
    var target by rememberSaveable { mutableStateOf(initialTarget) }
    var reminder by rememberSaveable(stateSaver = gentleDraftSaver) { mutableStateOf(initialReminder) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }
    var sound by rememberSaveable { mutableStateOf(false) }
    var importingSound by remember { mutableStateOf(false) }
    var repeatMenu by remember { mutableStateOf(false) }
    var chooseDays by rememberSaveable { mutableStateOf(false) }
    var categoryMenu by rememberSaveable { mutableStateOf(false) }
    var alertMenu by remember { mutableStateOf(false) }
    var durationMenu by rememberSaveable { mutableStateOf(false) }
    var duration by rememberSaveable { mutableIntStateOf(initial?.durationMinutes ?: 15) }
    var timePicker by rememberSaveable { mutableStateOf(false) }
    var typeSchedule by rememberSaveable { mutableStateOf(false) }
    val pickDay = LocalPickDay.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun closeKeyboard() {
        focus.clearFocus()
        keyboard?.hide()
    }
    val dirty = title != initial?.title.orEmpty() || note != initial?.note.orEmpty() ||
        kind != (initial?.kind ?: ItemKind.ROUTINE) || repeat != initialRepeat ||
        time != initial?.time?.toString().orEmpty() || effective != initialDate || oneDate != initialOnce ||
        weekdays != initialWeekdays || monthly != initialMonths || missing != initial?.rule?.missing ||
        category != initial?.category || checklist != initial?.checklist.orEmpty().joinToString("\n") ||
        content != initialContent || target != initialTarget || reminder != initialReminder || duration != (initial?.durationMinutes ?: 15)

    fun save() {
        try {
            require(title.isNotBlank()) { "Give the reminder a title." }
            val days = weekdays.split(',').mapNotNull(String::toIntOrNull).map(DayOfWeek::of).toSet()
            val dates = monthly.split(',').filter(String::isNotBlank)
            val rule = when (repeat) {
                RepeatKind.WEEKDAYS -> RepeatRule(repeat, weekdays = days)
                RepeatKind.MONTHLY -> RepeatRule(repeat, monthDays = dates.mapNotNull(String::toIntOrNull).toSet(), lastDay = "last" in dates, missing = missing)
                RepeatKind.ONCE -> RepeatRule(repeat, oneDate = LocalDate.parse(oneDate))
                else -> RepeatRule(repeat)
            }
            val from = if (!editing && rule.kind == RepeatKind.ONCE) requireNotNull(rule.oneDate) else LocalDate.parse(effective)
            require(from >= LocalDate.now()) { "Choose today or a future start date." }
            val version = PlanVersion(from, title.trim(), note.trim(), kind, time.takeIf(String::isNotBlank)?.let(LocalTime::parse), rule,
                archived = initial?.archived ?: false, content = if (kind == ItemKind.RITUAL) content.lines().filter(String::isNotBlank) else emptyList(),
                target = if (kind == ItemKind.RITUAL) target.toIntOrNull()?.takeIf { it in 1..10 }
                    ?: throw IllegalArgumentException("Choose 1 to 10 rounds.") else 1,
                gentleEnabled = initial?.gentleEnabled ?: false, durationMinutes = duration, category = category,
                checklist = if (kind == ItemKind.ROUTINE) checklist.lines().map(String::trim).filter(String::isNotEmpty) else emptyList())
            ReminderDraft.validate(version, reminder)
            onSave(version, reminder)
        } catch (e: IllegalArgumentException) {
            error = e.message ?: "Check the reminder details."
        } catch (_: Exception) {
            error = "Couldn’t save. Try again."
        }
    }
    fun repeatLabel(kind: RepeatKind) = when (kind) {
        RepeatKind.ONCE -> "Once"; RepeatKind.DAILY -> "Every day"; RepeatKind.WEEKDAYS -> "Selected days"
        RepeatKind.MONTHLY -> "Monthly"
        else -> DayType.entries.first { it.repeat == kind }.label
    }

    androidx.activity.compose.BackHandler(enabled = dirty) { discard = true }
    fun chooseTime(chosen: LocalTime) {
        time = chosen.toString()
        if (!editing && repeat == RepeatKind.ONCE && LocalDate.parse(oneDate) == today && chosen <= LocalTime.now()) {
            oneDate = today.plusDays(1).toString()
        }
        error = null
    }
    QuietRoute(if (!editing) "New reminder" else "Edit reminder", { if (dirty) discard = true else onDismiss() },
        alarmActive, onAlarm, footer = {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
            Button(onClick = ::save, enabled = !importingSound, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (importingSound) "Importing sound…" else "Save reminder")
            }
        }) {
        TextField(title, { title = it.replace('\n', ' ').take(128); error = null },
            label = { Text("Title") }, maxLines = 3, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            textStyle = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        val chosenTime = time.takeIf(String::isNotBlank)?.let(LocalTime::parse)
        val day = LocalDate.parse(oneDate)
        ReminderSettingsSection("Schedule") {
            ReminderSetting("Time", chosenTime?.let { clockLabel(it) } ?: if (reminder.enabled) "Choose time" else "Anytime") {
                closeKeyboard()
                timePicker = true
            }
            ReminderSetting("Repeat", repeatLabel(repeat)) { closeKeyboard(); repeatMenu = true }
            when (repeat) {
                RepeatKind.ONCE -> ReminderSetting("Date", shortDate(day)) {
                    closeKeyboard()
                    pickDay(day) { oneDate = it.toString(); error = null }
                }
                RepeatKind.WEEKDAYS -> ReminderSetting("Days", weekdays.split(',').mapNotNull(String::toIntOrNull)
                    .joinToString(" · ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
                    .ifEmpty { "Choose days" }) { closeKeyboard(); chooseDays = true }
                RepeatKind.MONTHLY -> ReminderSetting("Days of the month",
                    monthly.split(',').filter(String::isNotBlank).joinToString(", ") { if (it == "last") "Last day" else it }
                        .ifEmpty { "Choose days" }) { closeKeyboard(); chooseDays = true }
                else -> Unit
            }
        }
        ReminderSettingsSection("Alert") {
            ReminderSetting("How it alerts", EditorAlert.from(reminder).label) { closeKeyboard(); alertMenu = true }
            if (reminder.enabled && reminder.ringing) {
                ReminderSetting("Sound & vibration", enabled = !importingSound) { closeKeyboard(); sound = true }
            }
        }
        ReminderSettingsSection("Details") {
            if (kind == ItemKind.ROUTINE) {
                val stepCount = checklist.lineSequence().count(String::isNotBlank)
                ReminderSetting(if (checklistOpen) "Hide checklist" else "Checklist",
                    if (stepCount == 0) null else if (stepCount == 1) "1 step" else "$stepCount steps",
                    expanded = checklistOpen) { checklistOpen = !checklistOpen }
                if (checklistOpen) OutlinedTextField(checklist, { checklist = it.take(2420); error = null },
                    label = { Text("Checklist steps") }, supportingText = { Text("One step per line") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), minLines = 2)
            }
            ReminderSetting(if (more) "Fewer options" else "More options", expanded = more) { more = !more }
            if (more) {
                ReminderSetting("Category", category?.label ?: "None") { closeKeyboard(); categoryMenu = true }
                if (chosenTime != null) ReminderSetting("Calendar duration", "$duration min") { closeKeyboard(); durationMenu = true }
                if (repeat != RepeatKind.ONCE || editing) {
                    val start = LocalDate.parse(effective)
                    ReminderSetting("Starts", shortDate(start)) {
                        closeKeyboard()
                        pickDay(start) { effective = it.toString(); error = null }
                    }
                }
                if (reminder.enabled && !reminder.ringing) {
                    ReminderSettingSwitch("Exact timing", reminder.precise) { reminder = reminder.copy(precise = it) }
                    onAndroidSound?.let { ReminderSetting("Notification sound", onClick = it) }
                }
                ReminderSettingSwitch("Reading ritual", kind == ItemKind.RITUAL) {
                    kind = if (it) ItemKind.RITUAL else ItemKind.ROUTINE
                    error = null
                }
                OutlinedTextField(note, { note = it.take(1024) }, label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                if (kind == ItemKind.RITUAL) {
                    OutlinedTextField(content, { content = it; error = null }, label = { Text("Text or steps") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(target, { target = it; error = null }, label = { Text("Rounds") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }
    if (repeatMenu) {
        val common = listOf(RepeatKind.ONCE, RepeatKind.DAILY, RepeatKind.WEEKDAYS, RepeatKind.MONTHLY)
        ChoiceSheet("Repeat", repeat, (common + DayType.entries.map { it.repeat }).map { ReminderChoice(it, repeatLabel(it)) },
            onDismiss = { repeatMenu = false }, onPick = {
                repeat = it
                repeatMenu = false
                error = null
                chooseDays = it == RepeatKind.WEEKDAYS && weekdays.isBlank() ||
                    it == RepeatKind.MONTHLY && monthly.isBlank()
            }, groupBreak = common.size)
    }
    if (chooseDays && repeat in setOf(RepeatKind.WEEKDAYS, RepeatKind.MONTHLY)) {
        val weekly = repeat == RepeatKind.WEEKDAYS
        RepeatDaysDialog(weekly, if (weekly) weekdays else monthly, missing, onDismiss = { chooseDays = false }) { days, policy ->
            if (weekly) weekdays = days else {
                monthly = days
                missing = policy
            }
            error = null
            chooseDays = false
        }
    }
    if (categoryMenu) ChoiceSheet("Category", category, (listOf(null) + ReminderCategory.entries).map {
        ReminderChoice(it, it?.label ?: "None")
    }, onDismiss = { categoryMenu = false }, onPick = { category = it; categoryMenu = false })
    if (alertMenu) ChoiceSheet("Alert", EditorAlert.from(reminder),
        EditorAlert.entries.map { ReminderChoice(it, it.label, it.help) }, onDismiss = { alertMenu = false }, onPick = { mode ->
            reminder = when (mode) {
                EditorAlert.OFF -> reminder.copy(enabled = false)
                EditorAlert.NOTIFICATION -> reminder.copy(enabled = true, ringing = false, precise = reminder.precise && !reminder.ringing)
                EditorAlert.RINGING, EditorAlert.FULL_SCREEN -> reminder.copy(enabled = true, ringing = true, precise = true,
                    fullScreen = mode == EditorAlert.FULL_SCREEN)
            }
            alertMenu = false
            error = null
        })
    if (sound && reminder.enabled && reminder.ringing) {
        ReminderDialog("Alarm sound", onDismiss = { if (!importingSound) sound = false }, footer = {
            Button(onClick = { sound = false }, enabled = !importingSound) { Text("Done") }
        }) {
            SoundControls(reminder, bridge, onChange = { reminder = it; error = null }, onImporting = { importingSound = it })
        }
    }
    if (durationMenu) DurationDialog(duration, { durationMenu = false }) { duration = it; durationMenu = false }
    if (typeSchedule) QuickScheduleDialog(onDismiss = { typeSchedule = false }) { scheduled ->
        time = scheduled.toLocalTime().toString()
        oneDate = scheduled.toLocalDate().toString()
        if (!editing) effective = oneDate
        error = null
        typeSchedule = false
    }
    if (timePicker) ReminderTimePicker(
        initial = time.takeIf(String::isNotBlank)?.let(LocalTime::parse) ?: LocalTime.now().plusMinutes(5),
        onDismiss = { timePicker = false },
        onAnytime = { reminder = reminder.copy(enabled = false); time = ""; timePicker = false },
        onTypeSchedule = if (repeat == RepeatKind.ONCE) ({
            timePicker = false
            typeSchedule = true
        }) else null,
        onTime = { chosen ->
            chooseTime(chosen)
            timePicker = false
        }
    )
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard changes?") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

@Composable
private fun ReminderSettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(title, Modifier.padding(bottom = 4.dp).semantics { heading() },
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

@Composable
private fun ReminderSetting(
    label: String, value: String? = null, expanded: Boolean? = null, enabled: Boolean = true, onClick: () -> Unit
) {
    val config = LocalConfiguration.current
    val stack = config.screenWidthDp < 360 || config.fontScale > 1.35f
    val valueColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else valueColor)
            if (stack && value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
        }
        if (!stack && value != null) {
            Text(value, Modifier.widthIn(max = 150.dp), style = MaterialTheme.typography.bodyMedium,
                color = valueColor, textAlign = TextAlign.End)
        }
        CompositionLocalProvider(LocalContentColor provides valueColor) {
            Box(Modifier.rotate(if (expanded == true) 180f else 0f)) {
                ReminderIcon(if (expanded == null) ReminderGlyph.NEXT else ReminderGlyph.EXPAND)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
private fun ReminderSettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp)
        .toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onCheckedChange = null)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatDaysDialog(
    weekly: Boolean, days: String, missing: MissingDate?, onDismiss: () -> Unit, onDone: (String, MissingDate?) -> Unit
) {
    var selection by rememberSaveable { mutableStateOf(days) }
    var policy by rememberSaveable { mutableStateOf(missing) }
    val chosen = selection.split(',').filter(String::isNotBlank).toSet()
    val needsPolicy = !weekly && chosen.any { (it.toIntOrNull() ?: 0) >= 29 }
    val options = if (weekly) DayOfWeek.entries.map {
        it.value.toString() to it.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    } else (1..31).map { it.toString() to it.toString() } + ("last" to "Last day")

    ReminderDialog(if (weekly) "Days of the week" else "Days of the month", onDismiss, footer = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Button(onClick = { onDone(selection, policy) }, enabled = chosen.isNotEmpty() && (!needsPolicy || policy != null)) {
            Text("Set days")
        }
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (token, label) ->
                FilterChip(selected = token in chosen, onClick = {
                    val next = if (token in chosen) chosen - token else chosen + token
                    selection = options.map { it.first }.filter { it in next }.joinToString(",")
                }, modifier = Modifier.widthIn(min = 48.dp), label = { Text(label) })
            }
        }
        if (needsPolicy) {
            Text("For shorter months", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MissingDate.entries.forEach { option ->
                    FilterChip(selected = policy == option, onClick = { policy = option },
                        label = { Text(if (option == MissingDate.SKIP) "Skip" else "Use last day") })
                }
            }
        }
    }
}
