package dev.gaurav.dailyrhythm

import android.app.KeyguardManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZonedDateTime

/** Run on a dedicated PIN-locked emulator with notification, exact-alarm and full-screen access. */
@RunWith(AndroidJUnit4::class)
class LockedReminderTest {
    @Test fun scheduledAlarmCanCompleteItsRoutineWithoutUnlocking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assertTrue("Configure a test PIN first", keyguard.isDeviceSecure)

        val bridge = LiveReminders(context)
        bridge.initialize()
        val due = ZonedDateTime.now().plusSeconds(45).withNano(0)
        val date = due.toLocalDate()
        val title = "Private lock-screen test"
        val saved = bridge.savePlanned(PlanVersion(date, title, time = due.toLocalTime(),
            rule = RepeatRule(RepeatKind.ONCE, oneDate = date), checklist = listOf("Private checklist step")),
            GentleConfig(enabled = true, precise = true, ringing = true, fullScreen = true), enableFirstAlert = true)
        assertNull(saved.warning)
        val id = FlexiblePlanner.identity(saved.id, date)
        assertTrue(bridge.state().pending.containsKey(id))

        device.sleep()
        assertTrue("The keyguard should be active before delivery", waitUntil(15_000) { keyguard.isDeviceLocked })
        val reminder = device.wait(Until.findObject(By.text("Daily Reminders alarm")), 150_000)
        assertNotNull("Expected the scheduled full-screen alarm. Recent events: ${bridge.state().events.takeLast(5)}", reminder)
        assertFalse(device.hasObject(By.text(title)))
        assertFalse(device.hasObject(By.text("Private checklist step")))
        val done = device.wait(Until.findObject(By.text("Done")), 10_000)
        assertNotNull(done)
        requireNotNull(done).click()
        val storage = Preferences(context)
        assertTrue("The locked action should write the outcome and end the session", waitUntil(20_000) {
            FlexiblePlanner(storage) { LocalDate.now() }.entry(id).outcome == Outcome.DONE &&
                id !in bridge.state().active && RingingService.snapshot() == null
        })
        assertTrue("Completing the reminder must leave the device locked", keyguard.isDeviceLocked)
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (condition()) return true
            SystemClock.sleep(250)
        } while (SystemClock.elapsedRealtime() < end)
        return false
    }
}
