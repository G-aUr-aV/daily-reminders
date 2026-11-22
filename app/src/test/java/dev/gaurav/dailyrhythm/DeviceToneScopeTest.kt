package dev.gaurav.dailyrhythm

import android.app.*
import android.content.*
import android.content.pm.*
import android.database.*
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class DeviceToneScopeTest {
    private lateinit var app:Application
    private val uri=Uri.parse("content://media/internal/audio/media/1")
    class Media:ContentProvider() {
        companion object {var queries=0;val selections=mutableListOf<String>();val names=mapOf(1L to "Fixture calm",2L to "Fixture bright",3L to "Fixture notification")}
        override fun onCreate()=true
        override fun query(u:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor {
            queries++;if(s!=null)selections+=s
            val columns=p?.map {if(it.contains("content://"))"uri"else it}?.toTypedArray() ?: arrayOf("_id","title","uri")
            val cursor=MatrixCursor(columns)
            if(u.path?.startsWith("/external")==true)return cursor
            val id=u.lastPathSegment?.toLongOrNull()
            val rows=if(id!=null)listOf(id)else listOf(1L,2L)
            rows.filter {it in names}.forEach {n->cursor.addRow(columns.map {column->when(column){"_id"->n;"title","title_key"->names[n];"uri"->"content://media/internal/audio/media";else->null}}.toTypedArray())}
            return cursor
        }
        override fun getType(u:Uri)="audio/ogg"
        override fun insert(u:Uri,v:ContentValues?):Uri?=null
        override fun delete(u:Uri,s:String?,a:Array<out String>?)=0
        override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
    }
    private fun owner(authority:String="media",pkg:String="com.android.providers.media",system:Boolean=true,uid:Int=10011) {
        val ai=ApplicationInfo().apply {packageName=pkg;this.uid=uid;flags=if(system)ApplicationInfo.FLAG_SYSTEM else 0}
        val pi=ProviderInfo().apply {name=Media::class.java.name;packageName=pkg;applicationInfo=ai;this.authority=authority;exported=true}
        val pm=Shadows.shadowOf(app.packageManager);pm.installPackage(PackageInfo().apply {packageName=pkg;applicationInfo=ai;providers=arrayOf(pi)});pm.addOrUpdateProvider(pi);pm.setPackagesForUid(uid,pkg)
        val provider=Media();provider.attachInfo(app,pi);ShadowContentResolver.registerProviderInternal(authority,provider)
    }
    @Before fun setup(){app=RuntimeEnvironment.getApplication();app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).edit().clear().commit();Media.queries=0;Media.selections.clear()}
    private class Source(val rows:List<DeviceTone> = emptyList()):AlarmToneSource {var reads=0;var titles=0;var fail=false;override fun alarms():List<DeviceTone>{reads++;if(fail)throw SecurityException("Denied device query");return rows};override fun title(uri:Uri):String?{titles++;return rows.firstOrNull {it.uri==uri}?.title}}
    @Test fun actualRingtoneManagerQueriesAlarmRowsAndReturnsDeviceProvidedNames(){owner();val catalog=DeviceTones(app).alarms();assertTrue(catalog.issue.orEmpty(),catalog.tones.isNotEmpty());assertTrue(catalog.tones.any {it.title=="Fixture calm" && it.uri==uri});assertTrue(Media.queries>0);assertTrue(Media.selections.any {it.contains("is_alarm")})}
    @Test fun selectedTitleComesFromVerifiedProviderQuery(){owner();assertEquals(DeviceTone(uri,"Fixture calm"),DeviceTones(app).selected(uri));assertTrue(Media.queries>0)}
    @Test fun spoofedAuthorityOwnerNeverGetsQueried(){owner(system=false);val source=Source(listOf(DeviceTone(uri,"Not trusted")));assertTrue(DeviceTones(app,source).alarms().tones.isEmpty());assertEquals(0,source.reads);assertThrows(IllegalArgumentException::class.java){DeviceTones(app,source).selected(uri)};assertEquals(0,source.titles)}
    @Test fun installedUidPackageMembershipMismatchFailsClosedBeforeQuery(){owner();val pm=Shadows.shadowOf(app.packageManager);val installedUid=app.packageManager.getPackageUid("com.android.providers.media",0);pm.setPackagesForUid(installedUid,"unrelated.fixture.package");val source=Source(listOf(DeviceTone(uri,"No")));assertTrue(DeviceTones(app,source).alarms().tones.isEmpty());assertEquals(0,source.reads)}
    @Test fun queryDeniedOrNoTonesReportsIssueWithoutPermissionFallback(){owner();val denied=Source().apply {fail=true};val catalog=DeviceTones(app,denied).alarms();assertTrue(catalog.tones.isEmpty());assertTrue(catalog.issue!!.contains("No storage/media permission"));assertNotNull(DeviceTones(app,Source()).alarms().issue)}
    @Test fun unsafeUrisBlankNamesAndDuplicateRowsAreNotInventedIntoCatalog(){owner();val source=Source(listOf(DeviceTone(uri,"  Fixture calm  "),DeviceTone(uri,"Duplicate"),DeviceTone(Uri.parse("https://example.invalid/audio"),"Remote"),DeviceTone(Uri.parse("content://media/internal/audio/media/2"),"   ")));val catalog=DeviceTones(app,source).alarms();assertEquals(listOf(DeviceTone(uri,"Fixture calm")),catalog.tones);assertNotNull(catalog.issue)}
    @Test fun catalogAndTitlesAreBounded(){owner();val rows=(1..300).map {DeviceTone(Uri.parse("content://media/internal/audio/media/$it"),"Fixture $it")};assertEquals(256,DeviceTones(app,Source(rows)).alarms().tones.size);assertEquals(128,DeviceTones.cleanTitle("x".repeat(1000))!!.length);assertFalse(DeviceTones.cleanTitle("A\n\u202eB")!!.contains('\n'))}
    @Test fun externalPrimaryDeviceUrisAllowedButUnknownSchemesNotForwarded(){owner();assertTrue(PrivateSounds.deviceTone(app,Uri.parse("content://media/external_primary/audio/media/2")));val source=Source();assertThrows(IllegalArgumentException::class.java){DeviceTones(app,source).selected(Uri.parse("content://cloud/alarm/1"))};assertEquals(0,source.titles)}
    @Test fun unavailableSelectedTitleDoesNotChangePreferences(){owner();val before=app.getSharedPreferences("rhythm_v1",0).all.toMap();assertThrows(IllegalArgumentException::class.java){DeviceTones(app,Source()).selected(uri)};assertEquals(before,app.getSharedPreferences("rhythm_v1",0).all)}
    private class Platform:LivePlatform {override fun exactAllowed()=true;override fun blocked(item:String):String?=null;override fun exists(alert:LiveAlert)=true;override fun arm(alert:LiveAlert){};override fun cancel(alert:LiveAlert){};override fun show(alert:LiveAlert,title:String,late:Boolean){};override fun dismiss(id:String){}}
    @Test fun perRoutineAndRitualUriAndActualTitlePersistIndependentlyAfterRestart(){owner();val day=LocalDate.of(2026,11,9);val now=day.atTime(6,0).toInstant(ZoneOffset.UTC);val bridge=LiveReminders(app,{now},{ZoneOffset.UTC},Platform());val first=DeviceTones(app).selected(uri);val second=DeviceTones(app).selected(Uri.parse("content://media/internal/audio/media/2"));val routine=bridge.createPlanned(PlanVersion(day,"Routine",time=LocalTime.NOON),GentleConfig(true,true,true,"tone:${first.uri}",toneTitle=first.title));val ritual=bridge.createPlanned(PlanVersion(day,"Ritual",kind=ItemKind.RITUAL,time=LocalTime.of(13,0),content=listOf("Read"),target=3),GentleConfig(true,true,true,"tone:${second.uri}",toneTitle=second.title));val restarted=LiveReminders(app,{now},{ZoneOffset.UTC},Platform());assertEquals("Fixture calm",restarted.config(routine.id).toneTitle);assertEquals("Fixture bright",restarted.config(ritual.id).toneTitle);assertNotEquals(restarted.config(routine.id).sound,restarted.config(ritual.id).sound);assertTrue(FlexiblePlanner(Preferences(app)){day}.state.rituals.isEmpty())}
    @Test fun titleOnlyUpdateKeepsUnchangedOverdueAlarmNonceAndSignature(){owner();val day=LocalDate.of(2026,11,9);var now=day.atTime(6,0).toInstant(ZoneOffset.UTC);val bridge=LiveReminders(app,{now},{ZoneOffset.UTC},Platform());RhythmStore(Preferences(app)).remindersEnabled=true;val c=GentleConfig(true,true,true,"tone:$uri",toneTitle="Fixture calm");bridge.configure("pack",c);val id=FlexiblePlanner.identity("pack",day);val before=bridge.state().pending.getValue(id);now=before.trigger.plusSeconds(10);bridge.configure("pack",c.copy(toneTitle="Updated device metadata"));assertEquals(before,bridge.state().pending[id]);assertEquals("Updated device metadata",LiveReminders(app,{now},{ZoneOffset.UTC},Platform()).config("pack").toneTitle)}
    @Test fun legacyImportsMetadataAndLogsStayReadOnlyDuringRevisedToneFlow(){owner();val day=LocalDate.of(2026,11,9);val kv=Preferences(app);FlexiblePlanner(kv){day}.report(FlexiblePlanner.identity("pack",day),Entry(Outcome.DONE));val id=java.util.UUID.randomUUID().toString();val root=PrivateSounds(app).directory();app.resources.openRawResource(R.raw.alarm_fallback).use {input->File(root,"$id.wav").outputStream().use {input.copyTo(it)}};SoundLibrary(app).rename(id,"Existing private name");val bridge=LiveReminders(app);bridge.configure("pack",GentleConfig(false,sound="private:$id"));val data=File(root,"$id.wav").readBytes();val names=File(root,"names.json").readBytes();val plan=kv.get(FlexiblePlanner.KEY);assertEquals("Existing private name",DeviceTones(app).savedLabel(bridge.config("pack")));DeviceTones(app).alarms();assertArrayEquals(data,File(root,"$id.wav").readBytes());assertArrayEquals(names,File(root,"names.json").readBytes());assertEquals(plan,kv.get(FlexiblePlanner.KEY));bridge.configure("pack",GentleConfig(false,sound="tone:$uri",toneTitle="Fixture calm"));assertArrayEquals(data,File(root,"$id.wav").readBytes());assertArrayEquals(names,File(root,"names.json").readBytes());assertEquals(plan,kv.get(FlexiblePlanner.KEY))}
    @Test fun gentleReadbackShowsActualDeviceTitleMuteAndStableChannel(){owner();owner("settings","com.android.providers.settings",uid=10012);Settings.System.putString(app.contentResolver,Settings.System.NOTIFICATION_SOUND,"content://media/internal/audio/media/3");val manager=app.getSystemService(NotificationManager::class.java);val channel=NotificationChannel(AndroidLivePlatform.channel("pack"),"User channel",NotificationManager.IMPORTANCE_NONE).apply {setSound(Uri.parse("content://media/internal/audio/media/3"),android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build());enableVibration(true)};manager.createNotificationChannel(channel);val before=manager.getNotificationChannel(channel.id);val text=AndroidLivePlatform(app).channelDescription("pack");assertTrue(text,text.contains("Fixture notification"));assertTrue(text.contains("BLOCKED"));assertTrue(text.contains("vibration On"));val after=manager.getNotificationChannel(channel.id);assertEquals(before.sound,after.sound);assertEquals(NotificationManager.IMPORTANCE_NONE,after.importance);assertEquals(1,manager.notificationChannels.size)}
    @Test fun gentleSilentChannelReadbackDoesNotRecreateOrForceSound(){val manager=app.getSystemService(NotificationManager::class.java);val c=NotificationChannel(AndroidLivePlatform.channel("pack"),"Silent",NotificationManager.IMPORTANCE_DEFAULT).apply {setSound(null,null)};manager.createNotificationChannel(c);assertTrue(AndroidLivePlatform(app).channelDescription("pack").contains("Silent"));assertNull(manager.getNotificationChannel(c.id).sound);assertEquals(1,manager.notificationChannels.size)}
    @Test fun optionalTitleFieldPreservesOldRingingSignatureAndOverdueRestart(){
        owner();val day=LocalDate.of(2026,11,9);var now=day.atTime(6,0).toInstant(ZoneOffset.UTC);val kv=Preferences(app);RhythmStore(kv).remindersEnabled=true
        val bridge=LiveReminders(app,{now},{ZoneOffset.UTC},Platform());bridge.configure("pack",GentleConfig(true,true,true,"tone:$uri"));val id=FlexiblePlanner.identity("pack",day);val a=bridge.state().pending.getValue(id)
        val row=FlexiblePlanner(kv){day}.occurrences(day).first {it.id==id};val text="${row.id}|${row.date}|${row.time}|${row.version.toString().substringBeforeLast(", durationMinutes=")})|GentleConfig(enabled=true, precise=true, ringing=true, sound=tone:$uri, vibrate=false)"
        val oldSignature=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)};assertEquals(oldSignature,a.signature)
        val json=org.json.JSONObject(kv.get(LiveStore.KEY)!!);val configs=json.getJSONObject("configs");configs.keys().forEach {configs.getJSONObject(it).remove("toneTitle")};kv.put(LiveStore.KEY,json.toString());now=a.trigger.plusSeconds(10)
        val restarted=LiveReminders(app,{now},{ZoneOffset.UTC},Platform());assertEquals("",restarted.config("pack").toneTitle);restarted.sync();assertEquals(a,restarted.state().pending[id])
    }

}
