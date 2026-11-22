package dev.gaurav.dailyrhythm

import android.content.Context
import android.database.Cursor
import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

@Entity(tableName = "metadata")
internal data class MetadataRow(@PrimaryKey val id: String, val payload: String)

@Entity(tableName = "reminders")
internal data class ReminderRow(@PrimaryKey val id: String, val payload: String)

@Entity(tableName = "occurrences", indices = [Index("date"), Index(value = ["reminderId", "date"])])
internal data class OccurrenceRow(@PrimaryKey val id: String, val reminderId: String, val date: String, val payload: String)

@Entity(tableName = "live_alerts")
internal data class LiveAlertRow(@PrimaryKey val id: String, val payload: String)

@Entity(tableName = "alert_events")
internal data class AlertEventRow(@PrimaryKey val id: Int, val payload: String)

@Entity(tableName = "settings")
internal data class SettingRow(@PrimaryKey val id: String, val payload: String)

@Dao
internal interface ReminderRows {
    @Upsert fun upsert(row: MetadataRow)
    @Upsert fun upsert(row: ReminderRow)
    @Upsert fun upsert(row: OccurrenceRow)
    @Upsert fun upsert(row: LiveAlertRow)
    @Upsert fun upsert(row: AlertEventRow)
    @Upsert fun upsert(row: SettingRow)

    @Query("DELETE FROM metadata WHERE id = :id") fun deleteMetadata(id: String)
    @Query("DELETE FROM reminders WHERE id = :id") fun deleteReminder(id: String)
    @Query("DELETE FROM occurrences WHERE id = :id") fun deleteOccurrence(id: String)
    @Query("DELETE FROM live_alerts WHERE id = :id") fun deleteAlert(id: String)
    @Query("DELETE FROM alert_events WHERE id >= :id") fun deleteEventsFrom(id: Int)
    @Query("DELETE FROM settings WHERE id = :id") fun deleteSetting(id: String)

    @RawQuery fun rows(query: SupportSQLiteQuery): Cursor
}

@Database(entities = [MetadataRow::class, ReminderRow::class, OccurrenceRow::class, LiveAlertRow::class,
    AlertEventRow::class, SettingRow::class], version = 1)
internal abstract class ReminderDatabase : RoomDatabase() {
    abstract fun rows(): ReminderRows
}

/** The planner owns scheduling semantics. Each transaction publishes only changed reminder,
 * occurrence and live-alarm records before callers can observe a new snapshot. */
internal class ReminderRepository(context: Context) {
    private val legacy = context.getSharedPreferences("rhythm_v1", Context.MODE_PRIVATE)
    // Alarm callbacks use a synchronous durable write before acknowledging their PendingIntent.
    // Read and decode once, then serve immutable cached state and write only affected rows.
    private val database = Room.databaseBuilder(context, ReminderDatabase::class.java, "daily_reminders.db")
        .allowMainThreadQueries().build()
    private val dao get() = database.rows()
    private var cached: StoredReminders? = null
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    internal fun close() = database.close()

    @Synchronized
    fun read(): StoredReminders {
        cached?.let { return it }
        val meta = rows(META)
        val state = if (meta[MIGRATION] == null) {
            // The previous app stores a FLEX binary checkpoint and a JSON live state. Decode both
            // before writing anything, then commit imported rows and the marker together.
            val values = legacy.all.mapValues { (_, value) -> value as String }
            val initial = StoredReminders(
                values[FlexiblePlanner.KEY]?.let(FlexCodec::decode),
                values[LiveStore.KEY]?.let(LiveStore::decode),
                values - FlexiblePlanner.KEY - LiveStore.KEY
            )
            database.transaction {
                persist(StoredReminders(), initial)
                set(META, MIGRATION, "1")
            }
            initial
        } else {
            require(meta[MIGRATION] == "1")
            StoredReminders(
                meta[PLAN]?.let { readPlan(FlexCodec.decode(it)) },
                if (meta[LIVE] == "1") readLive() else null,
                rows(SETTINGS)
            )
        }
        cached = state
        return state
    }

    @Synchronized
    fun update(change: (StoredReminders) -> StoredReminders): Boolean {
        val previous = read()
        val next = change(previous)
        if (previous == next) return false
        database.transaction { persist(previous, next) }
        cached = next
        revision.value++
        return previous.plan != next.plan
    }

    private fun persist(before: StoredReminders, after: StoredReminders) {
        updateRows(SETTINGS, before.values, after.values) { _, value -> value }
        if (before.plan != after.plan) updatePlan(before.plan, after.plan)
        if (before.live != after.live) updateLive(before.live, after.live)
    }

    private fun updatePlan(before: FlexState?, after: FlexState?) {
        fun meta(state: FlexState?) = state?.copy(items = emptyMap(), deleted = emptyMap(), changes = emptyMap(),
            reports = emptyMap(), rituals = emptyMap(), checkedSteps = emptyMap())
        if (meta(before) != meta(after)) set(META, PLAN, meta(after)?.let(FlexCodec::encode))
        fun reminders(state: FlexState?): Map<String, Pair<PlanItem, LocalDate?>> =
            state?.items.orEmpty().mapValues { (id, item) -> item to state?.deleted?.get(id) }
        updateRows(REMINDERS, reminders(before), reminders(after)) { id, (item, deleted) ->
            FlexCodec.encode(FlexState(LocalDate.MIN, mapOf(id to item), OFFICE_PLACEHOLDER,
                deleted = if (deleted == null) emptyMap() else mapOf(id to deleted)))
        }
        updateRows(OCCURRENCES, before.records(), after.records()) { _, record -> FlexCodec.encodeRecord(record) }
    }

    private fun readPlan(state: FlexState): FlexState {
        val reminders = rows(REMINDERS).values.map(FlexCodec::decode)
        val records = rows(OCCURRENCES).mapValues { (_, payload) -> FlexCodec.decodeRecord(payload) }
        return state.copy(
            items = reminders.flatMap { it.items.entries }.associate { it.toPair() },
            deleted = reminders.flatMap { it.deleted.entries }.associate { it.toPair() },
            changes = records.mapValues { it.value.changes }.filterValues { it.isNotEmpty() },
            reports = records.mapNotNull { (id, record) -> record.report?.let { id to it } }.toMap(),
            rituals = records.mapNotNull { (id, record) -> record.ritual?.let { id to it } }.toMap(),
            checkedSteps = records.mapValues { it.value.checked }.filterValues { it.isNotEmpty() }
        )
    }

    private fun FlexState?.records(): Map<String, FlexCodec.OccurrenceRecord> {
        if (this == null) return emptyMap()
        return (changes.keys + reports.keys + rituals.keys + checkedSteps.keys).associateWith { id ->
            FlexCodec.OccurrenceRecord(changes[id].orEmpty(), reports[id], rituals[id], checkedSteps[id].orEmpty())
        }
    }

    private fun updateLive(before: LiveState?, after: LiveState?) {
        if ((before == null) != (after == null)) set(META, LIVE, after?.let { "1" })
        updateRows(ALERTS, before?.configs.orEmpty(), after?.configs.orEmpty(), "config/") { id, config ->
            LiveStore.encode(LiveState(configs = mapOf(id to config)))
        }
        updateRows(ALERTS, before?.pending.orEmpty(), after?.pending.orEmpty(), "pending/") { id, alert ->
            LiveStore.encode(LiveState(pending = mapOf(id to alert)))
        }
        updateRows(ALERTS, before?.active.orEmpty(), after?.active.orEmpty(), "active/") { id, alert ->
            LiveStore.encode(LiveState(active = mapOf(id to alert)))
        }
        updateRows(ALERTS, before?.closed.orEmpty(), after?.closed.orEmpty(), "closed/") { _, signature -> signature }
        val previous = before?.events.orEmpty()
        val next = after?.events.orEmpty()
        for (index in next.indices) {
            val event = next[index]
            if (event != previous.getOrNull(index)) {
                set(EVENTS, index.toString(), LiveStore.encode(LiveState(events = listOf(event))))
            }
        }
        if (next.size < previous.size) {
            dao.deleteEventsFrom(next.size)
        }
    }

    private fun readLive(): LiveState {
        val alerts = rows(ALERTS)
        val configs = mutableMapOf<String, GentleConfig>()
        val pending = mutableMapOf<String, LiveAlert>()
        val active = mutableMapOf<String, LiveAlert>()
        val closed = mutableMapOf<String, String>()
        alerts.forEach { (key, payload) ->
            val id = key.substringAfter('/')
            when (key.substringBefore('/')) {
                "config" -> configs[id] = LiveStore.decode(payload).configs.getValue(id)
                "pending" -> pending[id] = LiveStore.decode(payload).pending.getValue(id)
                "active" -> active[id] = LiveStore.decode(payload).active.getValue(id)
                "closed" -> closed[id] = payload
                else -> error("Unknown alarm storage record.")
            }
        }
        val events = rows(EVENTS, "id").values.map { LiveStore.decode(it).events.single() }
        return LiveState(configs, pending, active, closed, events)
    }

    private fun rows(table: String, order: String? = null): Map<String, String> {
        // A legacy item may have many versions with long ritual texts. Read rare large values
        // in slices so they also work on devices with a small SQLite CursorWindow.
        val slice = 200_000
        val ordering = order?.let { " ORDER BY $it" }.orEmpty()
        return dao.rows(SimpleSQLiteQuery("SELECT id, length(payload), substr(payload, 1, $slice) FROM $table$ordering")).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val length = cursor.getInt(1)
                    val first = cursor.getString(2)
                    val payload = if (length <= slice) first else buildString {
                        append(first)
                        var offset = slice + 1
                        while (offset <= length) {
                            dao.rows(SimpleSQLiteQuery("SELECT substr(payload, $offset, $slice) FROM $table WHERE id = ?", arrayOf(id))).use {
                                check(it.moveToFirst())
                                append(it.getString(0))
                            }
                            offset += slice
                        }
                    }
                    put(id, payload)
                }
            }
        }
    }

    private fun set(table: String, id: String, value: String?) {
        when (table) {
            META -> if (value == null) dao.deleteMetadata(id) else dao.upsert(MetadataRow(id, value))
            REMINDERS -> if (value == null) dao.deleteReminder(id) else dao.upsert(ReminderRow(id, value))
            OCCURRENCES -> if (value == null) dao.deleteOccurrence(id) else {
                val (reminder, date) = FlexiblePlanner.parts(id)
                dao.upsert(OccurrenceRow(id, reminder, date.toString(), value))
            }
            ALERTS -> if (value == null) dao.deleteAlert(id) else dao.upsert(LiveAlertRow(id, value))
            EVENTS -> dao.upsert(AlertEventRow(id.toInt(), requireNotNull(value)))
            SETTINGS -> if (value == null) dao.deleteSetting(id) else dao.upsert(SettingRow(id, value))
            else -> error("Unknown database table.")
        }
    }

    private fun <T : Any> updateRows(table: String, before: Map<String, T>, after: Map<String, T>,
                                                    prefix: String = "", encode: (String, T) -> String) {
        (before.keys - after.keys).forEach { id -> set(table, prefix + id, null) }
        after.forEach { (id, value) -> if (before[id] != value) set(table, prefix + id, encode(id, value)) }
    }

    private fun RoomDatabase.transaction(block: () -> Unit) = runInTransaction(Runnable(block))

    companion object {
        private const val META = "metadata"
        private const val REMINDERS = "reminders"
        private const val OCCURRENCES = "occurrences"
        private const val ALERTS = "live_alerts"
        private const val EVENTS = "alert_events"
        private const val SETTINGS = "settings"
        private const val MIGRATION = "legacy_import"
        private const val PLAN = "planner"
        private const val LIVE = "live"
        private val OFFICE_PLACEHOLDER = listOf(OfficeVersion(LocalDate.MIN, emptySet()))
    }
}

internal data class StoredReminders(val plan: FlexState? = null, val live: LiveState? = null,
                                    val values: Map<String, String> = emptyMap())
