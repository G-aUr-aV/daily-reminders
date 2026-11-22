package dev.gaurav.dailyrhythm

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReviewMigrationBackupTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val day = LocalDate.of(2026, 11, 21)
    private val occurrence = FlexiblePlanner.identity("pack", day)
    private val completion = Instant.parse("2026-11-21T08:02:00Z")

    @Test fun versionThreeCheckpointMigratesWithItsHistoryCalendarSettingAndPendingToken() {
        val checkpoint = legacyCheckpoint()
        val live = LiveState(configs = mapOf("pack" to GentleConfig(true)),
            pending = mapOf(occurrence to LiveAlert(occurrence, day, completion, "legacy-token", "signature", false)),
            events = listOf(AlertEvent(occurrence, "QUEUED", "Legacy event", completion)))
        val prefs = app.getSharedPreferences("rhythm_v1", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString(FlexiblePlanner.KEY, checkpoint).putString(LiveStore.KEY, LiveStore.encode(live))
            .putString("calendar.connection.v1", "preserved-on-this-device").commit())
        val imported = ReminderRepository(app).let { repository ->
            try { repository.read() } finally { repository.close() }
        }
        assertEquals("Pack bag", imported.plan?.items?.get("pack")?.at(day)?.title)
        assertEquals(completion, imported.plan?.reports?.get(occurrence)?.entry?.loggedAt)
        assertEquals(live, imported.live)
        assertEquals("preserved-on-this-device", imported.values["calendar.connection.v1"])
        assertEquals(checkpoint, prefs.getString(FlexiblePlanner.KEY, null))
        // Reopening must read the committed record rows, even if the old checkpoint changes.
        assertTrue(prefs.edit().putString(FlexiblePlanner.KEY, "invalid after migration").commit())
        val reopened = ReminderRepository(app)
        try { assertEquals(imported, reopened.read()) } finally { reopened.close() }
    }

    @Test fun corruptLegacyDataCanBeRetriedWithoutErasingTheOldCheckpoint() {
        val prefs = app.getSharedPreferences("rhythm_v1", Context.MODE_PRIVATE)
        val plan = legacyCheckpoint()
        assertTrue(prefs.edit().putString(FlexiblePlanner.KEY, plan).putString(LiveStore.KEY, "unreadable").commit())
        val repository = ReminderRepository(app)
        try {
            assertThrows(IllegalStateException::class.java) { repository.read() }
            assertEquals("unreadable", prefs.getString(LiveStore.KEY, null))
            assertEquals(plan, prefs.getString(FlexiblePlanner.KEY, null))
            assertTrue(prefs.edit().putString(LiveStore.KEY, LiveStore.encode(LiveState())).commit())
            assertEquals("Pack bag", repository.read().plan?.items?.get("pack")?.at(day)?.title)
        } finally {
            repository.close()
        }
    }

    @Test fun backupRecoversReminderChecklistHistoryAndSoundWithCorruptionAndWrongPasswordRejected() {
        val storage = Preferences(app)
        val planner = FlexiblePlanner(storage, includeLegacyRoutines = false) { day }
        val id = planner.create(PlanVersion(day, "Pack bag", time = LocalTime.of(8, 0),
            category = ReminderCategory.WORK, checklist = listOf("Charger", "Keys")))
        val record = FlexiblePlanner.identity(id, day)
        assertFalse(planner.setStep(record, "Charger", true, completion))
        assertTrue(planner.setStep(record, "Keys", true, completion))
        assertTrue(planner.state.checkedSteps[FlexiblePlanner.identity(id, day.plusDays(1))].isNullOrEmpty())

        val soundId = UUID.randomUUID().toString()
        val sound = File(PrivateSounds(app).directory(), "$soundId.wav")
        app.resources.openRawResource(R.raw.alarm_fallback).use { source -> sound.outputStream().use { source.copyTo(it) } }
        SoundLibrary(app).register(soundId, "My alarm sound")
        val config = GentleConfig(false, sound = "private:$soundId")
        LiveStore(storage).save(LiveState(configs = mapOf(id to config),
            events = listOf(AlertEvent(record, "DISMISSED", "Stopped manually", completion))))
        val output = ByteArrayOutputStream()
        val password = "local test password".toCharArray()
        LocalBackup.export(app, output, password)
        val bytes = output.toByteArray()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("My alarm sound"))
        assertThrows(AEADBadTagException::class.java) {
            LocalBackup.read(app, ByteArrayInputStream(bytes), "wrong password".toCharArray())
        }
        assertThrows(Exception::class.java) {
            LocalBackup.read(app, ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), password)
        }
        assertEquals(Outcome.DONE, planner.entry(record).outcome)

        storage.putAll(mapOf(FlexiblePlanner.KEY to null, LiveStore.KEY to null))
        assertTrue(sound.delete())
        LocalBackup.read(app, ByteArrayInputStream(bytes), password).use { it.restore(app) }
        val restored = FlexiblePlanner(storage) { day }
        assertEquals(ReminderCategory.WORK, restored.state.items[id]?.at(day)?.category)
        assertEquals(Entry(Outcome.DONE, loggedAt = completion), restored.entry(record))
        assertEquals(setOf("Charger", "Keys"), restored.state.checkedSteps[record])
        assertEquals(config, LiveStore(storage).read()?.configs?.get(id))
        assertEquals("My alarm sound", SoundLibrary(app).entries().single().name)
        assertTrue(PrivateSounds(app).file(soundId).isFile)
    }

    private fun legacyCheckpoint(): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF("FLEX3")
            out.writeUTF(day.toString())
            out.writeInt(1) // reminder count
            out.writeUTF("pack")
            out.writeUTF(day.toString())
            out.writeInt(1) // versions
            out.writeUTF(day.toString())
            out.writeUTF("Pack bag")
            out.writeUTF("Remember the laptop")
            out.writeUTF("ROUTINE")
            out.writeUTF("08:00")
            out.writeUTF("DAILY")
            out.writeUTF("")
            out.writeUTF("")
            out.writeBoolean(false)
            out.writeUTF("")
            out.writeUTF("")
            out.writeBoolean(false)
            out.writeInt(0)
            out.writeInt(1)
            out.writeBoolean(true)
            out.writeBoolean(false)
            out.writeInt(15)
            out.writeInt(1) // office patterns
            out.writeUTF(day.toString())
            out.writeUTF("")
            out.writeInt(0)
            out.writeInt(0)
            out.writeInt(1) // reports
            out.writeUTF(occurrence)
            out.writeUTF("DONE")
            out.writeUTF("08:02")
            out.writeUTF("Pack bag")
            out.writeUTF(completion.toString())
            out.writeInt(0) // rituals
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
}
