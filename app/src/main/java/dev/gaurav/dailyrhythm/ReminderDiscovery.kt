package dev.gaurav.dailyrhythm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

internal enum class ReminderFilter(val label: String) {
    ALL("All"), ALARMS("Alarms"), ROUTINES("Routines"), OFFICE("Office"), ARCHIVED("Archived");

    fun matches(version: PlanVersion?, alarm: GentleConfig?, archived: Boolean = version?.archived == true) = when (this) {
        ALL -> !archived
        ALARMS -> !archived && alarm?.enabled == true && alarm.ringing
        ROUTINES -> !archived && version?.kind == ItemKind.ROUTINE
        OFFICE -> !archived && version?.rule?.kind == RepeatKind.OFFICE
        ARCHIVED -> archived
    }
}

internal fun PlanVersion?.matchesSearch(title: String, query: String): Boolean {
    val fields = listOfNotNull(title, this?.note, this?.category?.label) + this?.checklist.orEmpty() + this?.content.orEmpty()
    return query.trim().split(Regex("\\s+")).all { term -> fields.any { it.contains(term, ignoreCase = true) } }
}

@Composable
internal fun ReminderSearch(query: String, onQuery: (String) -> Unit, filter: ReminderFilter,
                            onFilter: (ReminderFilter) -> Unit, label: String) {
    OutlinedTextField(query, { onQuery(it.take(128)) }, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = if (query.isBlank()) null else ({
            TextButton(onClick = { onQuery("") }) { Text("Clear") }
        }))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReminderFilter.entries.forEach { option ->
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                FilterChip(selected = filter == option, onClick = { onFilter(option) }, label = { Text(option.label) })
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun WeekStrip(date: LocalDate, today: LocalDate, onDate: (LocalDate) -> Unit) {
    val monday = date.minusDays(date.dayOfWeek.value.toLong() - 1)
    val minimum = if (LocalConfiguration.current.fontScale > 1.35f) 64.dp else 48.dp
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = ((maxWidth - 24.dp) / 7).coerceAtLeast(minimum)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(7) { index ->
                val day = monday.plusDays(index.toLong())
                val active = day == date
                val reveal = remember(day) { BringIntoViewRequester() }
                if (active) LaunchedEffect(day) { reveal.bringIntoView() }
                Surface(onClick = { onDate(day) }, shape = MaterialTheme.shapes.medium,
                    color = if (active) colors.primaryContainer else colors.surface,
                    contentColor = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    border = if (day == today && !active) BorderStroke(1.dp, colors.primary) else null,
                    modifier = Modifier.width(cell).bringIntoViewRequester(reveal).semantics {
                        selected = active
                        contentDescription = if (day == today) "Today, ${dayLabel(day)}" else dayLabel(day)
                    }) {
                    Column(Modifier.heightIn(min = 54.dp).padding(horizontal = 3.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CategoryLabel(category: ReminderCategory, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val accent = when (category) {
        ReminderCategory.WORK -> Color(0xFF487BE2)
        ReminderCategory.HEALTH -> Color(0xFF388963)
        ReminderCategory.PERSONAL -> Color(0xFF9664BE)
        ReminderCategory.FOCUS -> Color(0xFFB87822)
        ReminderCategory.URGENT -> colors.error
    }
    Surface(color = androidx.compose.ui.graphics.lerp(colors.surface, accent, .17f),
        contentColor = colors.onSurface, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(category.label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
    }
}

internal data class HistorySearchResult(val id: String, val date: LocalDate, val title: String, val label: String?)

internal fun searchHistory(planner: FlexiblePlanner, live: LiveState, legacyDates: Set<LocalDate>,
                           query: String, filter: ReminderFilter, zone: java.time.ZoneId): List<HistorySearchResult> {
    val state = planner.state
    val today = LocalDate.now(zone)
    val events = live.events.mapNotNull { event ->
        QuietPresentation.activityLabel(event)?.let { label -> Triple(event.id, event.at.atZone(zone).toLocalDate(), label) }
    }.groupBy { it.second }
    val reportDates = state.reports.filterValues { it.entry.outcome != Outcome.UNLOGGED }.keys.map { id ->
        state.changes[id]?.lastOrNull()?.date ?: FlexiblePlanner.parts(id).second
    }
    return (events.keys + reportDates + legacyDates).filter { it <= today }.sortedDescending().flatMap { date ->
        val rows = planner.occurrences(date).filter { it.state in setOf(HistoryState.DONE, HistoryState.LATE, HistoryState.SKIPPED) }
            .associateBy { it.id }
        val activity = events[date].orEmpty().associateBy { it.first }
        (rows.keys + activity.keys).mapNotNull { id ->
            val (item, anchor) = runCatching { FlexiblePlanner.parts(id) }.getOrNull() ?: return@mapNotNull null
            val row = rows[id]
            val record = state.items[item]
            val version = row?.version ?: record?.at(anchor)
            val title = row?.title ?: version?.title ?: "Reminder"
            if (filter != ReminderFilter.ALL && !filter.matches(version, live.configs[item], planner.isArchived(item, today)) ||
                !version.matchesSearch(title, query)) return@mapNotNull null
            HistorySearchResult(id, date, title, row?.let { QuietPresentation.stateLabel(it.state) } ?: activity[id]?.third)
        }
    }
}
