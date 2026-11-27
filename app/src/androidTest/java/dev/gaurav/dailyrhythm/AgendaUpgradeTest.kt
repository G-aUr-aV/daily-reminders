package dev.gaurav.dailyrhythm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The CI runner installs the previous APK, seeds it, then replaces it with the same-signed update. */
@RunWith(AndroidJUnit4::class)
class AgendaUpgradeTest {
    @Test fun savedAgendaSurvivesAnInPlaceUpdate() {
        val stage = InstrumentationRegistry.getArguments().getString("upgradeStage")
        assumeTrue(stage == "seed" || stage == "verify")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        // A cold AOSP image may still be starting System UI after boot_completed.
        // Dismiss only that system dialog. Application ANRs must remain test failures.
        val systemBusy = By.text(java.util.regex.Pattern.compile("(System UI|Process system) isn't responding"))
        if (device.hasObject(systemBusy)) device.findObject(By.res("android", "aerr_wait"))?.click()
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        instrumentation.startActivitySync(launch)

        fun control(selector: BySelector): UiObject2 {
            val result = device.wait(Until.findObject(selector), 40_000)
            if (result == null) {
                val hierarchy = ByteArrayOutputStream()
                device.dumpWindowHierarchy(hierarchy)
                throw AssertionError("Expected $selector during $stage. Window hierarchy: $hierarchy")
            }
            return result
        }
        fun capture(name: String) {
            val directory = requireNotNull(context.getExternalFilesDir(null))
            assertTrue("Capture $name", device.takeScreenshot(File(directory, "$name.png")))
        }
        val sample = "Morning reset"
        if (stage == "seed") {
            control(By.text("Sample routines"))
            capture("before-empty")
            control(By.text("Sample routines")).click()
            for (attempt in 1..4) {
                if (device.hasObject(By.text(sample))) break
                device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .6f)
            }
            control(By.text(sample)).click()
            control(By.text("Save reminder")).click()
            control(By.desc("Mark $sample done"))
        } else {
            control(By.text("1 reminder left"))
            capture("after-agenda")
            control(By.desc("Mark $sample done")).click()
            control(By.text("All done"))
            capture("after-completion")
            control(By.text("Undo")).click()
            control(By.desc("Mark $sample done"))
            control(By.text("Reminders")).click()
            control(By.text(sample)).click()
            control(By.text("Edit reminder"))
            control(By.desc("Back")).click()
            control(By.text(sample))
        }
    }
}
