package dev.gaurav.dailyrhythm

import android.app.*
import android.content.*
import android.os.*
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.time.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Simulated service + real Compose windows/input dispatch. No audible output or phone testing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class QuietActiveWindowTest {
    companion object {lateinit var supplied:LiveReminders}
    class ProbeService:RingingService() {
        override fun reminders()=supplied
        override fun output(problem:(String)->Unit,ended:()->Unit)=object:AudioOutput {
            override fun play(sound:String,loop:Boolean) {}
            override fun close() {}
        }
    }
    class Platform:LivePlatform {
        override fun exactAllowed()=true
        override fun blocked(item:String):String?=null
        override fun exists(alert:LiveAlert)=true
        override fun arm(alert:LiveAlert){}
        override fun cancel(alert:LiveAlert){}
        override fun show(alert:LiveAlert,title:String,late:Boolean){}
        override fun ring(alert:LiveAlert,title:String){}
        override fun dismiss(id:String){RingingService.cancel(id)}
    }
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun seedPreviousInstallation() {
        compose.runOnIdle {
            val storage = Preferences(compose.activity)
            storage.putAll(mapOf(FlexiblePlanner.KEY to null, LiveStore.KEY to null))
            RhythmStore(storage).remindersEnabled = false
            LiveReminders(compose.activity).initialize()
        }
        compose.activityRule.scenario.recreate()
    }
    private var controller:org.robolectric.android.controller.ServiceController<ProbeService>?=null
    private lateinit var alert:LiveAlert
    private fun startSession() {
        compose.runOnIdle {
            val app=compose.activity.application
            Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
            val kv=Preferences(app);RhythmStore(kv).remindersEnabled=true
            var now=Instant.now()
            supplied=LiveReminders(app,{now},{ZoneId.systemDefault()},Platform())
            val planner=FlexiblePlanner(kv){LocalDate.now()}
            val current=planner.state.items.getValue("wake").at(LocalDate.now())!!
            planner.edit("wake",current.copy(from=LocalDate.now(),time=now.atZone(ZoneId.systemDefault()).toLocalTime().plusSeconds(5).withNano(0)))
            supplied.configure("wake",GentleConfig(true,true,true))
            alert=supplied.state().pending.values.first {FlexiblePlanner.parts(it.id).first=="wake"}
            now=alert.trigger.plusSeconds(1)
            supplied.handle(AndroidLivePlatform.intent(app,alert,"fire"))
            controller=Robolectric.buildService(ProbeService::class.java).create()
            controller!!.get().onStartCommand(Intent(app,ProbeService::class.java).putExtra("occurrence",alert.id).putExtra("token",alert.token),0,1)
            assertNotNull(RingingService.snapshot())
        }
        compose.mainClock.advanceTimeBy(1200)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200,TimeUnit.MILLISECONDS)
        compose.waitForIdle()
        compose.runOnIdle {assertNotNull("Fixture session must remain active: ${supplied.state().events}",RingingService.snapshot())}
    }
    @After fun cleanup() {controller?.destroy();Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private fun shot(name:String) {
        compose.waitForIdle();compose.runOnIdle {
            val view=WindowInspector.getGlobalWindowViews().last {it.isShown}
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap));val file=File("build/reports/quiet-native/$name.png");file.parentFile?.mkdirs()
            file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    /** Assert top-window ownership/focusable flags and dispatch touch through that window.
     * Robolectric reports hasWindowFocus=false even in the main-screen control case; record it,
     * never force focus or present that OS-focus limitation as a passed phone/window-focus check.
     */
    private fun touchTop(node:SemanticsNodeInteraction) {
        compose.waitForIdle();val semantics=node.fetchSemanticsNode()
        val owner=(semantics.root as ViewRootForTest).view.rootView
        val rect=semantics.boundsInWindow
        compose.runOnIdle {
            val top=WindowInspector.getGlobalWindowViews().last {it.isShown}
            assertSame("Control must belong to top visible window",top,owner)
            val flags=(top.layoutParams as android.view.WindowManager.LayoutParams).flags
            assertEquals("Controls must not be configured non-focusable",0,flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            val evidence=File("build/reports/quiet-native/window-input-evidence.txt");evidence.parentFile?.mkdirs()
            evidence.appendText("topOwner=true; focusableFlags=true; reportedWindowFocus=${top.hasWindowFocus()}; actionBounds=$rect\n")
            val at=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(at,at+if(action==MotionEvent.ACTION_UP)16 else 0,action,rect.center.x,rect.center.y,0)
                assertTrue("Top window must consume touch",top.dispatchTouchEvent(event));event.recycle()
            }
        };compose.waitForIdle()
    }
    private fun openControls() {compose.onAllNodesWithText("Active alarm · Open Stop / Snooze").onLast().performClick();compose.onNodeWithText("Alarm session & health").assertIsDisplayed()}
    @Test fun mainActiveBannerAndStopWindow() {
        startSession();compose.onNodeWithText("Active alarm · Open Stop / Snooze").assertIsDisplayed();shot("active-main")
        openControls();val stop=compose.onNodeWithText("Stop alarm");stop.performScrollTo();shot("active-controls-main");touchTop(stop)
        compose.runOnIdle {assertFalse(alert.id in supplied.state().active);assertEquals(Outcome.UNLOGGED,FlexiblePlanner(Preferences(compose.activity)){LocalDate.now()}.entry(alert.id).outcome)}
        touchTop(compose.onNodeWithText("Close"))
        compose.onNodeWithText("History",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onAllNodesWithText("Wake up").assertCountEquals(1)
        compose.onNodeWithText("Alarm triggered").assertDoesNotExist()
        shot("060-history-after-stop")
        compose.onNodeWithText("Wake up").performClick()
        compose.onNodeWithText("Stopped").assertIsDisplayed()
    }
    @Test fun editorMountedBeforeSessionKeepsDraftThroughSnoozeAndRecreation() {
        compose.onNodeWithContentDescription("Add reminder").performClick()
        compose.onNodeWithText("Title").performTextInput("Draft retained during alarm")
        startSession();shot("active-editor")
        openControls();touchTop(compose.onNodeWithText("Close"))
        compose.activityRule.scenario.recreate();compose.onNodeWithText("Draft retained during alarm").assertIsDisplayed()
        openControls();val snooze=compose.onNodeWithText("Snooze 5 min");snooze.performScrollTo();shot("active-controls-editor");touchTop(snooze)
        touchTop(compose.onNodeWithText("Close"));compose.onNodeWithText("Draft retained during alarm").assertIsDisplayed()
        compose.runOnIdle {assertTrue(supplied.state().pending[alert.id]?.snooze==true);assertEquals(Outcome.UNLOGGED,FlexiblePlanner(Preferences(compose.activity)){LocalDate.now()}.entry(alert.id).outcome)}
    }
    @Test fun readingMountedBeforeSessionKeepsRoundsThroughStopAndRecreation() {
        compose.onNodeWithText("Morning ritual").performScrollTo().performClick();compose.onNodeWithText("Open ritual").performClick()
        compose.onNodeWithText("I read this round · 1 of 3").performClick()
        startSession();shot("active-ritual")
        openControls();touchTop(compose.onNodeWithText("Close"));compose.activityRule.scenario.recreate()
        compose.onNodeWithText("I read this round · 2 of 3").assertIsDisplayed()
        openControls();val stop=compose.onNodeWithText("Stop alarm");stop.performScrollTo();shot("active-controls-ritual");touchTop(stop)
        touchTop(compose.onNodeWithText("Close"));compose.onNodeWithText("I read this round · 2 of 3").assertIsDisplayed()
        compose.runOnIdle {val p=FlexiblePlanner(Preferences(compose.activity)){LocalDate.now()};assertEquals(1,p.state.rituals.getValue(FlexiblePlanner.identity(FlexiblePlanner.AFFIRMATIONS,LocalDate.now())).rounds);assertEquals(Outcome.UNLOGGED,p.entry(alert.id).outcome)}
    }
}
