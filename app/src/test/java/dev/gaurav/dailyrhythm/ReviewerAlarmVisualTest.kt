package dev.gaurav.dailyrhythm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewerAlarmVisualTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fullAlarmRequiresADeliberateSlideAndKeepsControlsReachableWithLargeText() {
        val operations = mutableListOf<String>()
        fun show(width: String) {
            compose.runOnIdle {
                val alarm = AlarmDisplay("fixture", "Pack your office bag", "7:00 AM", 1,
                    true, ReminderCategory.WORK, listOf("Laptop", "Keys"))
                compose.activity.setContentView(ComposeView(compose.activity).apply {
                    setContent { AlarmScreen(alarm, operations::add, {}) }
                })
            }
            compose.waitForIdle()
            compose.runOnIdle {
                val window = WindowInspector.getGlobalWindowViews().last { it.isShown }
                val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
                window.draw(Canvas(bitmap))
                val file = File("build/reports/quiet-native/070-alarm-$width.png")
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        show("normal")
        val stop = compose.onNodeWithContentDescription("Slide to stop")
        stop.assertIsDisplayed()
        stop.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(emptyList<String>(), operations) }
        stop.performTouchInput { swipe(Offset(8f, center.y), Offset(width * .35f, center.y), 400) }
        compose.runOnIdle { assertEquals(emptyList<String>(), operations) }
        stop.performTouchInput { swipe(Offset(8f, center.y), Offset(width - 8f, center.y), 450) }
        compose.runOnIdle { assertEquals(listOf("stop"), operations) }

        RuntimeEnvironment.setQualifiers("w320dp-h560dp-night-mdpi")
        RuntimeEnvironment.setFontScale(2f)
        compose.activityRule.scenario.recreate()
        show("320-font200")
        compose.onNodeWithContentDescription("Slide to stop").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("More snooze options").performScrollTo().performClick()
        compose.onNodeWithText("30 minutes").performScrollTo().assertIsDisplayed()
    }
}
