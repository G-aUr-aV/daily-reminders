package dev.gaurav.dailyrhythm

import android.app.Application
import android.content.Context
import android.content.pm.*
import android.media.*
import android.net.Uri
import android.os.Looper
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class DeviceTonePlaybackTest {
    private lateinit var app:Application
    private lateinit var manager:AudioManager
    private var player:MediaPlayer?=null
    private var audio:LocalAudio?=null
    private val health=mutableListOf<String>()
    private var ended=0
    private val uri=Uri.parse("content://media/internal/audio/media/1")
    @Before fun setup(){app=RuntimeEnvironment.getApplication();manager=app.getSystemService(AudioManager::class.java);Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);ShadowMediaPlayer.setMediaInfoProvider {ShadowMediaPlayer.MediaInfo(1000,50)};ShadowMediaPlayer.setCreateListener {p,_->player=p}
        val pkg="com.android.providers.media";val ai=ApplicationInfo().apply {packageName=pkg;uid=10011;flags=ApplicationInfo.FLAG_SYSTEM};val pi=ProviderInfo().apply {name=DeviceToneScopeTest.Media::class.java.name;packageName=pkg;applicationInfo=ai;authority="media";exported=true};val pm=Shadows.shadowOf(app.packageManager);pm.installPackage(PackageInfo().apply {packageName=pkg;applicationInfo=ai;providers=arrayOf(pi)});pm.addOrUpdateProvider(pi);pm.setPackagesForUid(ai.uid,pkg);val provider=DeviceToneScopeTest.Media();provider.attachInfo(app,pi);ShadowContentResolver.registerProviderInternal("media",provider)
    }
    @After fun cleanup(){audio?.close();ShadowMediaPlayer.resetStaticState()}
    @Test fun constructingPreviewDoesNotPlayUntilExplicitStartAndCloseCleansFocus(){val prefs=app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).all.toMap();audio=LocalAudio(app,{health+=it},{ended++});assertNull(player);assertNull(Shadows.shadowOf(manager).lastAudioFocusRequest);audio!!.play("tone:$uri",false);Shadows.shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS);assertTrue(player!!.isPlaying);audio!!.close();audio!!.close();assertEquals(ShadowMediaPlayer.State.END,Shadows.shadowOf(player!!).state);assertNotNull(Shadows.shadowOf(manager).lastAbandonedAudioFocusRequest);assertEquals(prefs,app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).all)}
    @Test fun disappearedDeviceToneReportsAndUsesBundledFallbackOnOpenFailure(){ShadowMediaPlayer.addException(DataSource.toDataSource(app,uri),java.io.IOException("Tone disappeared"));audio=LocalAudio(app,{health+=it},{ended++});audio!!.play("tone:$uri",false);assertTrue(health.toString(),health.any {it.contains("bundled fallback")});Shadows.shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS);assertTrue(player!!.isPlaying);audio!!.close();assertEquals(ShadowMediaPlayer.State.END,Shadows.shadowOf(player!!).state);assertNotNull(Shadows.shadowOf(manager).lastAbandonedAudioFocusRequest)}
    @Test fun existingPrivateAudioAndMetadataSurviveRetainedReadOnlyPreview(){val id=UUID.randomUUID().toString();val root=PrivateSounds(app).directory();val file=File(root,"$id.wav");app.resources.openRawResource(R.raw.alarm_fallback).use {input->file.outputStream().use {input.copyTo(it)}};SoundLibrary(app).rename(id,"Retained experimental sound");val bytes=file.readBytes();val names=File(root,"names.json").readBytes();audio=LocalAudio(app,{health+=it},{ended++});audio!!.play("private:$id",false);Shadows.shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS);assertTrue(health.toString(),health.isEmpty());assertTrue(player!!.isPlaying);audio!!.close();assertArrayEquals(bytes,file.readBytes());assertArrayEquals(names,File(root,"names.json").readBytes());assertEquals(ShadowMediaPlayer.State.END,Shadows.shadowOf(player!!).state)}
}
