package dev.gaurav.dailyrhythm

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.*

internal data class SnoozeChoice(val label: String, val until: Instant)

internal fun snoozeChoices(now: Instant, zone: ZoneId): List<SnoozeChoice> {
    val date = now.atZone(zone).toLocalDate()
    val evening = date.atTime(18, 0).atZone(zone).toInstant()
    return buildList {
        add(SnoozeChoice("15 minutes", now.plusSeconds(900)))
        add(SnoozeChoice("30 minutes", now.plusSeconds(1800)))
        add(SnoozeChoice("1 hour", now.plusSeconds(3600)))
        if (evening > now) add(SnoozeChoice("Today at 6:00 PM", evening))
        add(SnoozeChoice("Tomorrow at 9:00 AM", date.plusDays(1).atTime(9, 0).atZone(zone).toInstant()))
    }
}

@Composable
internal fun SnoozeOptions(onChoose: (Instant) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var timeText by remember { mutableStateOf("") }
    val zone = ZoneId.systemDefault()
    // Resolve relative options at the tap. An expired calendar option is never silently rescheduled.
    val choices = snoozeChoices(Instant.now(), zone)
    choices.forEach { choice ->
        LabeledRow(choice.label, onClick = {
            snoozeChoices(Instant.now(), zone).find { it.label == choice.label }?.let { onChoose(it.until) }
        })
    }
    TextButton(onClick = { custom = !custom }) { Text(if (custom) "Hide custom time" else "Custom time") }
    if (custom) {
        val time = runCatching { LocalTime.parse(timeText.trim()) }.getOrNull()
        OutlinedTextField(timeText, { timeText = it.take(5) }, label = { Text("Time (24-hour)") },
            supportingText = { Text("HH:mm. If it has passed, snooze until tomorrow.") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = time != null, modifier = Modifier.heightIn(min = 48.dp), onClick = {
            if (time != null) {
                val now = ZonedDateTime.now(zone)
                val target = now.toLocalDate().atTime(time).atZone(zone)
                onChoose((if (target > now) target else target.plusDays(1)).toInstant())
            }
        }) { Text("Snooze until this time") }
    }
}
