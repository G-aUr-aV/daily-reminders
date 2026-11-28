package dev.gaurav.dailyrhythm

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.ViewRootForTest
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Robolectric native-graphics captures, NOT emulator/device screenshots or alarm-delivery proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class QuietNativeScreenTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun shot(name:String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val root=android.view.inspector.WindowInspector.getGlobalWindowViews().last {it.isShown}
            val bitmap=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            val output=File("build/reports/quiet-native/$name.png");output.parentFile?.mkdirs()
            output.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun appearanceOverridesTheDeviceAndSurvivesActivityRecreation() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-night-mdpi")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Settings").performClick()
        fun select(label: String) {
            compose.onNodeWithText(label).performScrollTo().performClick().assertIsSelected()
        }
        select("Light")
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            assertEquals(Appearance.LIGHT, Appearance.read(Preferences(compose.activity)))
            assertTrue(androidx.core.view.WindowCompat.getInsetsController(
                compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars)
        }
        compose.onNodeWithText("Dark").performScrollTo().performClick().assertIsSelected()
        select("System")
        compose.runOnIdle { assertEquals(Appearance.SYSTEM, Appearance.read(Preferences(compose.activity))) }
    }

    private fun add() = compose.onNodeWithContentDescription("Add reminder").performClick()
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction()).performClick()
    private fun pickSuggestedTime() {
        compose.onNodeWithText("Time").performClick()
        compose.onNode(hasText("Set time") and hasClickAction()).performClick()
    }

    @Test fun reviewSearchMatchesCategoriesAndChecklistStepsAndOpensTheRightEditor() {
        compose.runOnIdle {
            val day = LocalDate.now()
            val bridge = LiveReminders(compose.activity)
            bridge.createPlanned(PlanVersion(day, "Pack bag", category = ReminderCategory.WORK,
                checklist = listOf("Laptop charger")), GentleConfig(false))
            bridge.createPlanned(PlanVersion(day, "Water the plants", category = ReminderCategory.PERSONAL), GentleConfig(false))
        }
        compose.onNodeWithText("Pack bag").assertIsDisplayed()
        shot("070-today-week-strip")
        tab("Reminders")
        compose.onNodeWithText("Search reminders").performTextInput("work charger")
        compose.onNodeWithText("Water the plants").assertDoesNotExist()
        compose.onNodeWithText("Pack bag").assertIsDisplayed()
        shot("070-reminder-search")
        compose.onNodeWithText("Pack bag").performClick()
        compose.onNodeWithText("Edit reminder").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Pack bag").assertIsDisplayed()
    }

    @Test fun cleanInstallCreatesThenEditsInOneSaveEach() {
        compose.onNodeWithText("Nothing scheduled").assertIsDisplayed()
        shot("032-today-empty-light")
        add()
        compose.onNodeWithText("New reminder").assertIsDisplayed()
        compose.onNodeWithText("Notes").assertDoesNotExist()
        compose.onNodeWithText("Title").performTextInput("Water the plants")
        compose.onNodeWithText("Time").performClick()
        shot("032-time-picker-light")
        compose.onNode(hasText("Set time") and hasClickAction()).performClick()
        shot("032-editor-light")
        compose.onNodeWithText("Save reminder").performClick()
        compose.runOnIdle {
            val kv = Preferences(compose.activity)
            assertTrue(RhythmStore(kv).remindersEnabled)
            assertEquals("Water the plants", FlexiblePlanner(kv) { LocalDate.now() }.state.items.values.single().versions.single().title)
        }
        tab("Reminders")
        compose.onNodeWithText("Water the plants").performClick()
        compose.onNodeWithText("Edit reminder").assertIsDisplayed()
        compose.onNodeWithText("Title").performTextReplacement("Water the plants tonight")
        compose.onNodeWithText("Save reminder").performClick()
        compose.runOnIdle {
            val state = FlexiblePlanner(Preferences(compose.activity)) { LocalDate.now() }.state
            assertEquals(1, state.items.size)
            assertEquals("Water the plants tonight", state.items.values.single().versions.last().title)
        }
    }

    @Test fun relativeTimeCreatesAnOccurrenceAndProgressRespondsToDoneAndUndo() {
        val before = java.time.ZonedDateTime.now().plusMinutes(30).withSecond(0).withNano(0)
        add()
        compose.onNodeWithText("Title").performTextInput("Call the studio")
        compose.onNodeWithText("In 30 min").performScrollTo().performClick()
        compose.onNodeWithText("Save reminder").performClick()
        compose.runOnIdle {
            val plan = FlexiblePlanner(Preferences(compose.activity)) { LocalDate.now() }
            val saved = plan.state.items.values.single().versions.single()
            val expected = saved.rule.oneDate!!.atTime(saved.time!!).atZone(before.zone)
            assertTrue(expected in before..java.time.ZonedDateTime.now().plusMinutes(30))
        }
        // The new reminder may roll over to tomorrow if this test runs just before midnight.
        if (before.toLocalDate() == LocalDate.now()) {
            compose.onNodeWithText("1 reminder left").assertIsDisplayed()
            compose.onNodeWithContentDescription("Mark Call the studio done").performClick()
            compose.onNodeWithText("All done").assertIsDisplayed()
            compose.onNodeWithText("1 of 1 completed").assertIsDisplayed()
            compose.onNodeWithText("Undo").performClick()
            compose.onNodeWithText("1 reminder left").assertIsDisplayed()
        }
    }

    @Test fun narrowDarkLargeTextKeepsEditorActionsReachable() {
        RuntimeEnvironment.setQualifiers("w320dp-h780dp-night-mdpi")
        RuntimeEnvironment.setFontScale(2f)
        compose.activityRule.scenario.recreate()
        add()
        compose.onNodeWithText("Title").performTextInput("Evening reminder with a deliberately long title")
        val save = compose.onNodeWithText("Save reminder").assertIsDisplayed()
        val root = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val before = save.fetchSemanticsNode().boundsInRoot.bottom
        fun applyInsets(keyboardHeight: Int) = compose.runOnIdle {
            val bars = WindowInsetsCompat.Type.navigationBars()
            val keyboard = WindowInsetsCompat.Type.ime()
            ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                .setInsets(bars, Insets.of(0, 0, 0, 36)).setVisible(bars, true)
                .setInsets(keyboard, Insets.of(0, 0, 0, keyboardHeight)).setVisible(keyboard, keyboardHeight > 0).build())
        }
        applyInsets(0)
        val after = save.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Bottom inset must lift the full button: before=$before, after=$after", before - after >= 36f)
        shot("032-editor-dark-320-font200")
        applyInsets(240)
        val afterKeyboard = save.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Save must move above the keyboard: before=$after, after=$afterKeyboard", after - afterKeyboard >= 204f)
        save.performTouchInput { click(center) }
        compose.onNodeWithText("Choose a time for a reminder, or select Off for Anytime.").assertIsDisplayed()
        applyInsets(0)
        compose.onNodeWithText("Time").performScrollTo().performClick()
        compose.onNode(hasText("Set time") and hasClickAction()).assertIsDisplayed()
        if (!android.text.format.DateFormat.is24HourFormat(compose.activity)) {
            compose.onNodeWithText("AM").assertIsDisplayed()
            compose.onNodeWithText("PM").assertIsDisplayed()
        }
        shot("060-time-dark-320-font200")
        compose.onNodeWithText("Minute").performTextReplacement("99")
        compose.onNodeWithText("Set time").assertIsNotEnabled()
        compose.onNodeWithText("Minute").performTextReplacement("10")
        compose.onNodeWithText("Set time").assertIsEnabled()
    }

    @Test fun narrowRitualKeepsRoundAndUndoAboveTheSystemGestureArea() {
        RuntimeEnvironment.setQualifiers("w320dp-h560dp-night-mdpi")
        RuntimeEnvironment.setFontScale(2f)
        val day = LocalDate.now()
        compose.runOnIdle {
            FlexiblePlanner(Preferences(compose.activity)) { day }.create(PlanVersion(
                from = day,
                title = "Morning reading",
                kind = ItemKind.RITUAL,
                content = List(12) { "Taking time to think before I start helps me focus on the day ahead." },
                target = 2
            ))
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Morning reading").performScrollTo().performClick()
        compose.onNodeWithText("Open ritual").performClick()

        val rootNode = compose.onRoot().fetchSemanticsNode()
        val root = (rootNode.root as ViewRootForTest).view
        val record = compose.onNodeWithText("I read this round · 1 of 2").assertIsDisplayed()
        val before = record.fetchSemanticsNode().boundsInRoot.bottom
        val gestureHeight = 40
        compose.runOnIdle {
            val navigation = WindowInsetsCompat.Type.navigationBars()
            val gestures = WindowInsetsCompat.Type.mandatorySystemGestures()
            ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                .setInsets(navigation, Insets.NONE).setVisible(navigation, false)
                .setInsets(gestures, Insets.of(0, 0, 0, gestureHeight)).build())
        }
        val after = record.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Gesture insets must lift the round button: before=$before, after=$after", before - after >= gestureHeight)
        assertTrue(after <= rootNode.boundsInRoot.bottom - gestureHeight)
        shot("052-ritual-dark-320-font200")

        record.performTouchInput { click(center) }
        compose.onNodeWithText("Round 2 of 2").assertIsDisplayed()
        val undo = compose.onNodeWithText("Undo one round").assertIsDisplayed()
        assertTrue(undo.fetchSemanticsNode().boundsInRoot.bottom <= rootNode.boundsInRoot.bottom - gestureHeight)
        undo.performTouchInput { click(center) }
        compose.onNodeWithText("Round 1 of 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Morning reading").assertIsDisplayed()
    }

    @Test fun samplePrefillsEditorWithoutChangingThePlan() {
        tab("Reminders")
        compose.onNodeWithText("Sample routines").performClick()
        compose.onNodeWithText("Morning reset").performClick()
        compose.onNodeWithText("New reminder").assertIsDisplayed()
        compose.onNodeWithText("Morning reset").assertIsDisplayed()
        compose.onNodeWithText("Every day").assertIsDisplayed()
        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.runOnIdle { assertTrue(FlexiblePlanner(Preferences(compose.activity)) { LocalDate.now() }.state.items.isEmpty()) }
        compose.onNodeWithText("Save reminder").performClick()
        compose.runOnIdle {
            val today = LocalDate.now()
            val plan = FlexiblePlanner(Preferences(compose.activity)) { today }
            val reminder = plan.state.items.values.single()
            assertEquals(ReminderSample.MORNING.steps, reminder.at(today)?.content)
            assertFalse(LiveReminders(compose.activity).config(reminder.id).enabled)
        }
    }

    @Test fun monthlyControlsAreDisclosedAndSaveStaysReachable() {
        add()
        compose.onNodeWithText("Title").performTextInput("Monthly reset")
        pickSuggestedTime()
        compose.onNodeWithText("Repeat").performClick()
        shot("050-repeat-sheet")
        compose.onNodeWithText("Monthly").performClick()
        compose.onNodeWithText("31").performScrollTo().performClick()
        compose.onNodeWithText("Use last day").performScrollTo().performClick()
        compose.onNodeWithText("Save reminder").assertIsDisplayed()
        compose.onNodeWithText("More options").performScrollTo().performClick()
        compose.onNodeWithText("Notes").performScrollTo().assertIsDisplayed()
    }

    @Test fun savedAlarmShowsOnlyItsCurrentPermissionBlocker() {
        org.robolectric.Shadows.shadowOf(compose.activity.application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        compose.activityRule.scenario.recreate()
        add()
        compose.onNodeWithText("Title").performTextInput("Morning alarm")
        pickSuggestedTime()
        compose.onNodeWithText("How it alerts").performScrollTo().performClick()
        compose.onNodeWithText("Full-screen alarm").performClick()
        compose.onNodeWithText("Save reminder").performClick()
        compose.onNodeWithText("Allow exact alarms").assertIsDisplayed()
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, FlexiblePlanner(Preferences(compose.activity)) { LocalDate.now() }.state.items.size) }
        shot("032-exact-access")
    }

    @Test fun todayHasOneRowPerReminderAndHistoryOmitsUnrecordedRows() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-night-mdpi")
        compose.runOnIdle {
            val today = LocalDate.now()
            val planner = FlexiblePlanner(Preferences(compose.activity)) { today }
            for ((title, time) in listOf("Wake up" to LocalTime.of(6,30), "Water the plants" to LocalTime.of(8,15),
                "Stretch" to LocalTime.of(18,0), "Bedtime" to LocalTime.of(22,30))) {
                val id = planner.create(PlanVersion(today, title, time = time))
                if (title == "Wake up") planner.report(FlexiblePlanner.identity(id, today), Entry(Outcome.DONE, time))
            }
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Mark Water the plants done").assertIsDisplayed()
        compose.onNodeWithText("Not reported").assertDoesNotExist()
        shot("060-today-dark")
        compose.onNodeWithText("Logged (1)").performClick()
        compose.onAllNodesWithText("Wake up").assertCountEquals(1)
        tab("History")
        compose.onNodeWithText("Wake up").assertIsDisplayed()
        compose.onNodeWithText("Water the plants").assertDoesNotExist()
        shot("032-outcomes-dark")
    }
    @Test fun todayCompletionCanBeUndoneAndHistoryShowsTheLastSevenDays() {
        lateinit var id: String
        compose.runOnIdle {
            val today = LocalDate.now()
            val plan = FlexiblePlanner(Preferences(compose.activity)) { today }
            id = FlexiblePlanner.identity(plan.create(PlanVersion(today, "Take a walk")), today)
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Mark Take a walk done").performClick()
        compose.onNodeWithText("Logged (1)").assertIsDisplayed()
        compose.runOnIdle {
            val entry = FlexiblePlanner(Preferences(compose.activity)) { LocalDate.now() }.entry(id)
            assertNotNull(entry.loggedAt)
            assertNull(entry.actual)
        }
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithContentDescription("Mark Take a walk done").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mark Take a walk done").performClick()
        tab("History")
        compose.onNodeWithText("7-day summary").performClick()
        compose.onNodeWithText("1 of 1 done").assertIsDisplayed()
        shot("060-seven-day-history")
    }
    @Test fun reminderSheetExpandsAndEditingCanDeleteWithoutLosingHistory() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-night-mdpi")
        lateinit var itemId: String
        compose.runOnIdle {
            val today = LocalDate.now()
            itemId = FlexiblePlanner(Preferences(compose.activity)) { today }
                .create(PlanVersion(today, "Wake up", time = LocalTime.of(6, 0)))
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Wake up").performClick()
        compose.onNodeWithText("Mark done").assertIsDisplayed()
        compose.onNodeWithText("Edit reminder").assertIsDisplayed()
        shot("040-reminder-sheet")
        compose.onNodeWithContentDescription("Expand reminder details").performClick()
        compose.onNodeWithContentDescription("Collapse reminder details").assertIsDisplayed()
        compose.onNodeWithText("Move this occurrence").assertIsDisplayed()
        shot("040-reminder-sheet-expanded")
        compose.onNodeWithText("Mark done").performClick()
        compose.onNodeWithText("Marked done").assertIsDisplayed()
        compose.onNodeWithText("Logged (1)").performClick()
        compose.onNodeWithText("Wake up").performClick()
        compose.onNodeWithText("Undo completion").assertIsDisplayed()
        compose.onNodeWithText("Edit reminder").performClick()
        compose.onNodeWithText("Save reminder").assertIsDisplayed()
        compose.onNodeWithText("Delete reminder").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Save reminder").assertIsDisplayed()
        compose.onNodeWithText("Delete reminder").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Wake up").assertDoesNotExist()
        compose.runOnIdle {
            val today = LocalDate.now()
            val state = FlexiblePlanner(Preferences(compose.activity)) { today }.state
            assertEquals(today, state.deleted[itemId])
            assertEquals(Outcome.DONE, state.reports.getValue(FlexiblePlanner.identity(itemId, today)).entry.outcome)
        }
        tab("History")
        compose.onNodeWithText("Wake up").assertIsDisplayed()
    }

    @Test fun alarmSoundHasBuiltInsAndKeepsDoneReachable() {
        add()
        compose.onNodeWithText("Title").performTextInput("Morning sound")
        pickSuggestedTime()
        compose.onNodeWithText("How it alerts").performScrollTo().performClick()
        compose.onNodeWithText("Ringing notification").performClick()
        compose.onNodeWithText("Sound & vibration").performScrollTo().performClick()
        compose.onNodeWithText("Chime").performClick()
        compose.onNodeWithText("Phone tones").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Import audio file").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        shot("051-alarm-sounds")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Save reminder").performClick()
        compose.runOnIdle {
            val planner = FlexiblePlanner(Preferences(compose.activity)) { LocalDate.now() }
            assertEquals("builtin:chime", LiveReminders(compose.activity).config(planner.state.items.keys.single()).sound)
        }
    }

    @Test fun editorDurationAndDateUseThemedControls() {
        add()
        compose.onNodeWithText("Title").performTextInput("Read")
        pickSuggestedTime()
        compose.onNodeWithText("More options").performScrollTo().performClick()
        compose.onNodeWithText("Calendar duration").performScrollTo().performClick()
        compose.onNodeWithText("30").performClick()
        compose.onNodeWithText("Set duration").performClick()
        compose.onNodeWithText("30 min").assertIsDisplayed()
        compose.onNodeWithText("Date").performScrollTo().performClick()
        compose.onNodeWithText("Choose date").assertIsDisplayed()
        shot("051-calendar-picker")
        compose.onNodeWithText("Cancel").performClick()
        shot("050-editor")
        compose.onNodeWithText("Save reminder").performClick()
        compose.runOnIdle {
            val planner = FlexiblePlanner(Preferences(compose.activity)) { LocalDate.now() }
            assertEquals(30, planner.state.items.values.single().versions.last().durationMinutes)
        }
    }
    @Test fun boardOrderSurvivesReopenAndSwipeKeepsDeleteReversible() {
        compose.runOnIdle {
            val day = LocalDate.now()
            val planner = FlexiblePlanner(Preferences(compose.activity)) { day }
            planner.create(PlanVersion(day, "Alpha", time = LocalTime.of(8, 0)))
            planner.create(PlanVersion(day, "Bravo", time = LocalTime.of(9, 0)))
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Reorder").performClick()
        val alpha = compose.onNodeWithText("Alpha")
        val bravo = compose.onNodeWithText("Bravo")
        fun ordered() = bravo.fetchSemanticsNode().positionInRoot.y < alpha.fetchSemanticsNode().positionInRoot.y
        assertFalse(ordered())
        val moveDown = alpha.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Move down" }
        compose.runOnIdle { assertTrue(moveDown.action()) }
        assertTrue(ordered())
        val moveUp = alpha.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Move up" }
        compose.runOnIdle { assertTrue(moveUp.action()) }
        assertFalse(ordered())
        alpha.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        alpha.performTouchInput {
            moveBy(androidx.compose.ui.geometry.Offset(0f, 112f))
            advanceEventTime(48)
            up()
        }
        assertTrue("Holding and dragging moves Alpha below Bravo", ordered())
        compose.activityRule.scenario.recreate()
        assertTrue(ordered())
        tab("Reminders")
        val tile = compose.onNodeWithText("Bravo")
        tile.performTouchInput { swipeRight() }
        compose.onNodeWithText("Archive reminder?").assertDoesNotExist()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        tile.assertIsDisplayed()
        compose.onNodeWithText("Archive").performClick()
        compose.onNodeWithText("Archive reminder?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        tile.assertIsDisplayed()
        tile.performTouchInput { swipeLeft() }
        compose.onNodeWithText("Delete reminder?").assertDoesNotExist()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete reminder?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        tile.assertIsDisplayed()
        tile.performTouchInput { swipeRight() }
        compose.onNodeWithText("Archive").performClick()
        compose.onNodeWithText("Archive").performClick()
        tile.assertDoesNotExist()
        compose.onNodeWithText("Archived").performScrollTo().performClick()
        tile.assertIsDisplayed()
        tile.performClick()
        compose.onNodeWithText("Restore reminder").performScrollTo().performClick()
        tile.assertIsDisplayed()
    }

    @Test fun compactLargeTextShowsCalendarGridAndConfirmButton() {
        RuntimeEnvironment.setQualifiers("w320dp-h560dp-night-mdpi")
        RuntimeEnvironment.setFontScale(2f)
        compose.activityRule.scenario.recreate()
        add()
        compose.onNodeWithText("Date").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Next month").assertIsDisplayed()
        compose.onNodeWithText("Choose date").assertIsDisplayed()
        val nextMonthDay = LocalDate.now().plusMonths(1).withDayOfMonth(28)
        compose.onNodeWithContentDescription("Next month").performClick()
        compose.onNodeWithContentDescription(dayLabel(nextMonthDay)).performScrollTo().performClick()
        compose.onNodeWithText("Choose date").assertIsDisplayed()
        shot("051-calendar-320-font200")
        compose.onNodeWithText("Choose date").performClick()
        compose.onNodeWithText(shortDate(nextMonthDay)).assertIsDisplayed()
    }

    @Test fun filePickerImportsAndSavesASongWithoutOpeningPhoneTones() {
        add()
        compose.onNodeWithText("Title").performTextInput("Choose tone")
        pickSuggestedTime()
        compose.onNodeWithText("How it alerts").performScrollTo().performClick()
        compose.onNodeWithText("Ringing notification").performClick()
        compose.onNodeWithText("Sound & vibration").performScrollTo().performClick()
        compose.onNodeWithText("Import audio file").performScrollTo().performClick()
        compose.runOnIdle {
            val activity = Shadows.shadowOf(compose.activity)
            val launch = requireNotNull(activity.nextStartedActivityForResult).intent
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.action)
            assertEquals("audio/*", launch.type)
            assertTrue(launch.hasCategory(Intent.CATEGORY_OPENABLE))
            assertTrue(launch.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
            val context = compose.activity
            val provider = PrivateSoundsTest.FixtureProvider()
            val authority = "downloads.fixture"
            provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
            ShadowContentResolver.registerProviderInternal(authority, provider)
            val source = File(context.cacheDir, "selected-audio.wav")
            context.resources.openRawResource(R.raw.alarm_fallback).use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            PrivateSoundsTest.FixtureProvider.source = source
            val uri = Uri.parse("content://$authority/document/downloaded")
            activity.receiveResult(launch, Activity.RESULT_OK, Intent().apply {
                clipData = ClipData.newRawUri("Audio", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
        compose.waitUntil(timeoutMillis = 8_000) {
            compose.onAllNodesWithText("Morning song.wav").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Save reminder").performClick()
        compose.runOnIdle {
            val context = compose.activity
            val reminder = FlexiblePlanner(Preferences(context)) { LocalDate.now() }.state.items.values.single()
            val config = LiveReminders(context).config(reminder.id)
            assertEquals("Morning song.wav", config.toneTitle)
            assertTrue(config.sound.startsWith("private:"))
            val privateFile = PrivateSounds(context).file(config.sound.removePrefix("private:"))
            assertArrayEquals(File(context.cacheDir, "selected-audio.wav").readBytes(), privateFile.readBytes())
        }
    }

}
