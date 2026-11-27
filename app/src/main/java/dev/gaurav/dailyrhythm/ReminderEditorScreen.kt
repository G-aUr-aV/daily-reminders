package dev.gaurav.dailyrhythm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderEditorScreen(
    initial: PlanVersion?, editing: Boolean, newDay: LocalDate, initialReminder: GentleConfig, bridge: LiveReminders, alarmActive: Boolean,
    onAlarm: () -> Unit, onDismiss: () -> Unit, onArchive: (() -> Unit)?, onDelete: (() -> Unit)?, onRestore: (() -> Unit)?,
    onAndroidSound: (() -> Unit)?, onSave: (PlanVersion, GentleConfig) -> Unit
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
    var checklistOpen by rememberSaveable { mutableStateOf(initial?.checklist?.isNotEmpty() == true) }
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
        OutlinedTextField(title, { title = it.take(128); error = null }, label = { Text("Title") }, singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
        val chosenTime = time.takeIf(String::isNotBlank)?.let(LocalTime::parse)
        Surface(onClick = { closeKeyboard(); timePicker = true }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp).heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Time", style = MaterialTheme.typography.labelLarge)
                    Text(if (chosenTime != null) clockLabel(chosenTime) else if (reminder.enabled) "Choose time" else "Anytime",
                        style = MaterialTheme.typography.headlineLarge)
                }
                ReminderIcon(ReminderGlyph.ALARM)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!editing && repeat == RepeatKind.ONCE && oneDate == today.toString()) {
                SuggestionChip(onClick = {
                    val targetTime = java.time.ZonedDateTime.now().plusMinutes(30)
                    oneDate = targetTime.toLocalDate().toString()
                    time = targetTime.toLocalTime().withSecond(0).withNano(0).toString()
                    closeKeyboard()
                    error = null
                }, label = { Text("In 30 min") })
            }
            listOf("Morning" to LocalTime.of(8, 0), "Afternoon" to LocalTime.of(13, 0),
                "Evening" to LocalTime.of(19, 0)).forEach { (label, preset) ->
                FilterChip(selected = chosenTime == preset, onClick = { chooseTime(preset) },
                    label = { Text("$label · ${clockLabel(preset)}") })
            }
        }
        if (repeat == RepeatKind.ONCE) {
            TextButton(onClick = { closeKeyboard(); typeSchedule = true }) { Text("Type date and time") }
        }
        EditorGroup("Schedule") {
            val config = androidx.compose.ui.platform.LocalConfiguration.current
            val stack = config.screenWidthDp < 360 || config.fontScale > 1.35f
            val width = if (stack) Modifier.fillMaxWidth() else Modifier
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorButton("Repeat", repeatLabel(repeat), width, stack) { closeKeyboard(); repeatMenu = true }
                if (repeat == RepeatKind.ONCE) {
                    val day = LocalDate.parse(oneDate)
                    EditorButton("Date", shortDate(day), width, stack) { closeKeyboard(); pickDay(day) { oneDate = it.toString(); error = null } }
                }
            }
            if (repeat == RepeatKind.WEEKDAYS) {
                val chosen = weekdays.split(',').mapNotNull(String::toIntOrNull).toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(selected = day.value in chosen, onClick = {
                            weekdays = (if (day.value in chosen) chosen - day.value else chosen + day.value).sorted().joinToString(",")
                        }, label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
                    }
                }
            }
            if (repeat == RepeatKind.MONTHLY) {
                val chosen = monthly.split(',').filter(String::isNotBlank).toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ((1..31).map(Int::toString) + "last").forEach { token ->
                        FilterChip(selected = token in chosen, onClick = {
                            monthly = (if (token in chosen) chosen - token else chosen + token).joinToString(",")
                        }, modifier = Modifier.widthIn(min = 48.dp), label = { Text(if (token == "last") "Last day" else token) })
                    }
                }
                if (chosen.any { (it.toIntOrNull() ?: 0) >= 29 }) {
                    Text("For shorter months", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MissingDate.entries.forEach { value ->
                            FilterChip(selected = missing == value, onClick = { missing = value },
                                label = { Text(if (value == MissingDate.SKIP) "Skip" else "Use last day") })
                        }
                    }
                }
            }

        }
        EditorGroup("Alert") {
            val activeMode = EditorAlert.from(reminder)
            EditorButton("How it alerts", activeMode.label, modifier = Modifier.fillMaxWidth(), showChevron = true) { closeKeyboard(); alertMenu = true }
            if (reminder.enabled && reminder.ringing) {
                TextButton(onClick = { if (!importingSound) { closeKeyboard(); sound = true } }) { Text("Sound & vibration") }
            }
        }
        if (kind == ItemKind.ROUTINE) {
            TextButton(onClick = { checklistOpen = !checklistOpen }) { Text(if (checklistOpen) "Hide checklist" else "Checklist") }
            if (checklistOpen) OutlinedTextField(checklist, { checklist = it.take(2420) },
                label = { Text("Checklist steps") }, supportingText = { Text("One step per line") },
                modifier = Modifier.fillMaxWidth(), minLines = 2)
        }
        if (onRestore != null) TextButton(onClick = onRestore) { Text("Restore reminder") }
        TextButton(onClick = { more = !more }) { Text(if (more) "Fewer options" else "More options") }
        if (more) EditorGroup("Details") {
            Text("Category", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(null) + ReminderCategory.entries).forEach { option ->
                    FilterChip(selected = category == option, onClick = { category = option },
                        label = { Text(option?.label ?: "None") })
                }
            }
            if (chosenTime != null) EditorButton("Calendar duration", "$duration min", Modifier.fillMaxWidth(), showChevron = true) {
                closeKeyboard(); durationMenu = true
            }
            if (repeat != RepeatKind.ONCE || editing) {
                val start = LocalDate.parse(effective)
                EditorButton("Starts", shortDate(start)) { pickDay(start) { effective = it.toString() } }
            }
            if (reminder.enabled && !reminder.ringing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Exact timing", Modifier.weight(1f))
                    Switch(reminder.precise, onCheckedChange = { reminder = reminder.copy(precise = it) },
                        modifier = Modifier.semantics { contentDescription = "Exact timing" })
                }
                onAndroidSound?.let { LabeledRow("Notification sound", onClick = it) }
            }
            OutlinedTextField(note, { note = it.take(1024) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reading ritual", Modifier.weight(1f))
                Switch(kind == ItemKind.RITUAL, onCheckedChange = { kind = if (it) ItemKind.RITUAL else ItemKind.ROUTINE },
                    modifier = Modifier.semantics { contentDescription = "Reading ritual" })
            }
            if (kind == ItemKind.RITUAL) {
                OutlinedTextField(content, { content = it }, label = { Text("Text or steps") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(target, { target = it }, label = { Text("Rounds") }, modifier = Modifier.fillMaxWidth())
            }
            if (onArchive != null) TextButton(onClick = onArchive) { Text("Archive reminder") }
        }
        if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete reminder", color = MaterialTheme.colorScheme.error) }
    }
    if (repeatMenu) {
        val common = listOf(RepeatKind.ONCE, RepeatKind.DAILY, RepeatKind.WEEKDAYS, RepeatKind.MONTHLY)
        ChoiceSheet("Repeat", repeat, (common + DayType.entries.map { it.repeat }).map { ReminderChoice(it, repeatLabel(it)) },
            onDismiss = { repeatMenu = false }, onPick = { repeat = it; repeatMenu = false; error = null }, groupBreak = common.size)
    }
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
        onTime = { chosen ->
            chooseTime(chosen)
            timePicker = false
        }
    )
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard changes?") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}
