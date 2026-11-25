package dev.gaurav.dailyrhythm

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

private val schedulePattern = Regex("""(today|tomorrow|\d{4}-\d{2}-\d{2})\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")

internal fun parseQuickSchedule(text: String, today: LocalDate): LocalDateTime? {
    val (dateText, hours, minutes, meridiem) = schedulePattern.matchEntire(text.trim().lowercase(Locale.ROOT))?.destructured ?: return null
    val day = when (dateText) {
        "today" -> today
        "tomorrow" -> today.plusDays(1)
        else -> runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return null
    }
    val hour = hours.toInt()
    val minute = minutes.toIntOrNull() ?: 0
    if (day < today || minute !in 0..59) return null
    val hourOfDay = if (meridiem.isEmpty()) hour.takeIf { it in 0..23 } ?: return null
    else if (hour !in 1..12) return null
    else hour % 12 + (if (meridiem == "pm") 12 else 0)
    return day.atTime(LocalTime.of(hourOfDay, minute))
}

@Composable
internal fun QuickScheduleDialog(onDismiss: () -> Unit, onApply: (LocalDateTime) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val parsed = parseQuickSchedule(text, LocalDate.now())
    val available = parsed?.takeIf { it > LocalDateTime.now() }
    ReminderFormSheet("Type date and time", onDismiss, footer = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Button(enabled = available != null, onClick = { available?.let(onApply) }) { Text("Use schedule") }
    }) {
        OutlinedTextField(text, { text = it.take(60) }, label = { Text("Date and time") }, singleLine = true,
            supportingText = { Text("Try tomorrow 9am or today 18:30.") }, modifier = Modifier.fillMaxWidth())
        available?.let { Text(dayLabel(it.toLocalDate()) + " · " + clockLabel(it.toLocalTime())) }
    }
}
