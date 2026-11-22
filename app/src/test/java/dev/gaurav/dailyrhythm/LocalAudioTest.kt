package dev.gaurav.dailyrhythm

import android.app.Application
import android.media.*
import android.os.Looper
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class LocalAudioTest {
    private lateinit var app:Application
    private lateinit var manager:AudioManager
    private var player:MediaPlayer?=null
    private var output:LocalAudio?=null
    private val problems=mutableListOf<String>()
    private var ends=0
    @Before fun setup(){app=RuntimeEnvironment.getApplication();manager=app.getSystemService(AudioManager::class.java);ShadowMediaPlayer.setMediaInfoProvider {ShadowMediaPlayer.MediaInfo(2000,50)};ShadowMediaPlayer.setCreateListener {p,_->player=p}}
    @After fun cleanup(){output?.close();ShadowMediaPlayer.resetStaticState()}
    private fun play(canPlay:()->Boolean={true}):LocalAudio=LocalAudio(app,{problems+=it},{ends++},canPlay).also {output=it;it.play("default",true)}
    @Test fun actualGrantThenPrepareIsRequiredWithAlarmTransientFocus(){
        Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        play();assertFalse(player!!.isPlaying)
        val request=Shadows.shadowOf(manager).lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioAttributes.USAGE_ALARM,request.audioAttributes.usage);assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,request.focusGain);assertFalse(request.acceptsDelayedFocusGain())
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS);assertTrue(player!!.isPlaying)
    }
    @Test fun rejectedAndDelayedFocusNeverCreatePlayerOrStartLater(){
        for(response in listOf(AudioManager.AUDIOFOCUS_REQUEST_FAILED,AudioManager.AUDIOFOCUS_REQUEST_DELAYED)){
            Shadows.shadowOf(manager).setNextFocusRequestResponse(response);play();assertNull(player)
            Shadows.shadowOf(manager).lastAudioFocusRequest.listener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertNull(player);assertNotNull(Shadows.shadowOf(manager).lastAbandonedAudioFocusRequest)
        }
        assertEquals(2,ends)
    }
    @Test fun focusLossIncludingDuckStopsReleasesAndCannotRestart(){
        for(loss in listOf(AudioManager.AUDIOFOCUS_LOSS,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)){
            Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);play()
            val request=Shadows.shadowOf(manager).lastAudioFocusRequest
            request.listener!!.onAudioFocusChange(loss)
            assertEquals(ShadowMediaPlayer.State.END,Shadows.shadowOf(player!!).state)
            request.listener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.SECONDS)
            assertEquals(ShadowMediaPlayer.State.END,Shadows.shadowOf(player!!).state)
        }
    }
    @Test fun deadlineOrTokenCheckBeforePreparedFailsClosed(){Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);play {false};Shadows.shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS);assertEquals(1,ends);assertEquals(ShadowMediaPlayer.State.END,Shadows.shadowOf(player!!).state)}
    @Test fun explicitCloseDuringPrepareIsIdempotent(){Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);play();output!!.close();output!!.close();Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.SECONDS);assertEquals(ShadowMediaPlayer.State.END,Shadows.shadowOf(player!!).state)}
    @Test fun mediaErrorReleasesFocusAndReportsHealth(){Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);play();Shadows.shadowOf(player!!).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN,0);assertTrue(problems.any {it.contains("failed")});assertEquals(1,ends);assertNotNull(Shadows.shadowOf(manager).lastAbandonedAudioFocusRequest)}
    @Test fun unreadablePrivateSoundUsesDisclosedBundledFallback(){Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);output=LocalAudio(app,{problems+=it},{ends++});output!!.play("private:"+java.util.UUID.randomUUID(),false);assertTrue(problems.any {it.contains("bundled fallback")});Shadows.shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS);assertTrue(player!!.isPlaying)}
    @Test fun slowPreparationCannotStartSurprisePlaybackAfterTimeout(){
        ShadowMediaPlayer.setMediaInfoProvider {ShadowMediaPlayer.MediaInfo(2000,6000)}
        Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);play()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5,TimeUnit.SECONDS);assertEquals(ShadowMediaPlayer.State.END,Shadows.shadowOf(player!!).state);assertEquals(1,ends)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.SECONDS);assertEquals(ShadowMediaPlayer.State.END,Shadows.shadowOf(player!!).state)
    }

    @Test fun closedOutputIgnoresStaleFocusCallbacks(){
        Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);play()
        val old=Shadows.shadowOf(manager).lastAudioFocusRequest.listener!!;output!!.close()
        old.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);old.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(6,TimeUnit.SECONDS);assertEquals(0,ends);assertTrue(problems.isEmpty())
    }

    @Test fun playerConstructionFailureIsContainedAndAbandonsFocusOnce(){
        Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowMediaPlayer.setCreateListener {_,_->throw IllegalStateException("Injected player factory failure")}
        output=LocalAudio(app,{problems+=it},{ends++})
        output!!.play("default",true)
        assertNull(player) // Constructor never returned an owned player; none may start.
        val request=Shadows.shadowOf(manager).lastAudioFocusRequest
        assertSame(request.audioFocusRequest,Shadows.shadowOf(manager).lastAbandonedAudioFocusRequest)
        assertEquals(1,problems.size);assertTrue(problems.single().contains("Injected player factory failure"));assertEquals(1,ends)
        output!!.close();output!!.close()
        request.listener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(6,TimeUnit.SECONDS)
        assertNull(player);assertEquals(1,problems.size);assertEquals(1,ends)
    }
    @Test fun preparedAuthorizationExceptionIsContainedReleasedAndCannotResurrect(){
        Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        play {throw SecurityException("Injected authorization storage failure")}
        val created=player!!;val prepared=Shadows.shadowOf(created).onPreparedListener!!
        val request=Shadows.shadowOf(manager).lastAudioFocusRequest
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS)
        assertEquals(ShadowMediaPlayer.State.END,Shadows.shadowOf(created).state)
        assertSame(request.audioFocusRequest,Shadows.shadowOf(manager).lastAbandonedAudioFocusRequest)
        assertEquals(1,problems.size);assertTrue(problems.single().contains("Injected authorization storage failure"));assertEquals(1,ends)
        prepared.onPrepared(created);output!!.close();output!!.close()
        request.listener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(6,TimeUnit.SECONDS)
        assertEquals(ShadowMediaPlayer.State.END,Shadows.shadowOf(created).state);assertEquals(1,problems.size);assertEquals(1,ends)
    }
    @Test fun throwingHealthAndEndedCallbacksDoNotEscapeOrPreventCleanup(){
        Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        output=LocalAudio(app,{problems+=it;throw IllegalStateException("Health callback failed")},{ends++;throw IllegalStateException("Ended callback failed")},{throw SecurityException("Authorization failed")})
        output!!.play("default",true)
        val created=player!!;val prepared=Shadows.shadowOf(created).onPreparedListener!!
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS)
        assertEquals(ShadowMediaPlayer.State.END,Shadows.shadowOf(created).state)
        assertNotNull(Shadows.shadowOf(manager).lastAbandonedAudioFocusRequest)
        assertEquals(1,problems.size);assertEquals(1,ends)
        prepared.onPrepared(created);output!!.close();Shadows.shadowOf(Looper.getMainLooper()).idleFor(6,TimeUnit.SECONDS)
        assertEquals(1,problems.size);assertEquals(1,ends)
    }

}
