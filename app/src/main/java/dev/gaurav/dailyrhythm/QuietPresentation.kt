package dev.gaurav.dailyrhythm

import java.time.LocalDate
import java.time.ZoneId

/** Pure presentation queries: never initialize/freeze ritual snapshots while drawing a list. */
internal object QuietPresentation {
    fun rounds(state: FlexState, row: PlannedOccurrence, legacyRounds: Int = 0): Pair<Int,Int> {
        val saved = state.rituals[row.id]
        if (saved != null) return saved.rounds to saved.target
        val legacy = row.itemId == FlexiblePlanner.AFFIRMATIONS && FlexiblePlanner.parts(row.id).third == "base" && legacyRounds > 0
        return if (legacy) legacyRounds to 3 else 0 to row.version.target
    }
    fun next(rows: List<PlannedOccurrence>, state: FlexState, date: LocalDate, today: LocalDate,
             legacyRounds: (PlannedOccurrence) -> Int = { 0 }): PlannedOccurrence? {
        if (date != today) return null
        return rows.filter { row -> row.date == date && row.scheduled && row.time != null && row.state == HistoryState.UNLOGGED &&
            (row.version.kind != ItemKind.RITUAL || rounds(state,row,legacyRounds(row)).let { it.first < it.second }) }
            .minWithOrNull(compareBy<PlannedOccurrence> { it.time }.thenBy { it.id })
    }
    fun noFocusLabel(rows:List<PlannedOccurrence>):String {
        val timedUnreported=rows.count {it.scheduled && it.time!=null && it.state==HistoryState.UNLOGGED}
        if(timedUnreported>0) return "Reading rounds recorded · $timedUnreported timed outcome(s) still unreported"
        if(rows.any {it.scheduled && it.time==null && it.state==HistoryState.UNLOGGED}) return "Timed outcomes reported · Anytime items below"
        return "No remaining timed reports"
    }
    fun stateLabel(state: HistoryState) = when(state) {
        HistoryState.DONE -> "Done"
        HistoryState.LATE -> "Late · self-reported"
        HistoryState.SKIPPED -> "Skipped"
        HistoryState.UNLOGGED -> "Not reported"
        HistoryState.NOT_SCHEDULED -> "Not scheduled"
    }

    fun reminderActivity(events: List<AlertEvent>, date: LocalDate, zone: ZoneId): List<Pair<AlertEvent, String>> =
        events.asReversed().mapNotNull { event ->
            if (event.at.atZone(zone).toLocalDate() != date) return@mapNotNull null
            val label = activityLabel(event) ?: return@mapNotNull null
            event to label
        }

    fun activityLabel(event: AlertEvent): String? = when (event.status) {
                "DELIVERED", "DELIVERED_LATE" -> when {
                    event.detail.startsWith("Ringing requested") -> "Alarm triggered"
                    event.status == "DELIVERED_LATE" -> "Notification delivered late"
                    else -> "Notification delivered"
                }
                "DISMISSED", "RING_STOPPED" -> "Stopped"
                "SNOOZE_QUEUED" -> "Snoozed"
                "SNOOZE_BLOCKED" -> "Snooze could not be scheduled"
                "SKIPPED_ALERT" -> "Occurrence skipped"
                "UNANSWERED" -> "Alarm timed out"
                "POST_BLOCKED", "RING_START_BLOCKED", "RING_BLOCKED" -> "Alert could not run"
                "RING_INTERRUPTED" -> "Alarm interrupted"
                else -> null
            }
}
