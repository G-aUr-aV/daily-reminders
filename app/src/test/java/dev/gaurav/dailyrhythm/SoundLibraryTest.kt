package dev.gaurav.dailyrhythm

import android.app.Application
import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File
import java.time.*
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class SoundLibraryTest {
    private lateinit var app:Application
    private lateinit var root:File
    @Before fun setup(){app=RuntimeEnvironment.getApplication();app.getSharedPreferences("rhythm_v1",Context.MODE_PRIVATE).edit().clear().commit();root=PrivateSounds(app).directory();root.deleteRecursively();root.mkdirs()}
    private fun sound():String=UUID.randomUUID().toString().also {id->app.resources.openRawResource(R.raw.alarm_fallback).use {input->File(root,"$id.wav").outputStream().use {input.copyTo(it)}}}
    private class Names(val original:Map<String,String>,var fail:Boolean=false):SoundNames {
        var value=original;var writes=0
        override fun read()=value
        override fun write(names:Map<String,String>){writes++;if(fail)throw java.io.IOException("Injected metadata failure");value=names}
    }
    @Test fun renameIsAtomicRestartableCosmeticAndNeverAPath(){val id=sound();val kv=Preferences(app);FlexiblePlanner(kv){LocalDate.of(2026,11,9)}.report(FlexiblePlanner.identity("pack",LocalDate.of(2026,11,9)),Entry(Outcome.DONE));val before=app.getSharedPreferences("rhythm_v1",0).all.toMap();SoundLibrary(app).rename(id,"  Rain at night  ");assertEquals("Rain at night",SoundLibrary(app).entries().single().name);assertTrue(File(root,"$id.wav").exists());assertFalse(File(root,"Rain at night").exists());assertEquals(before,app.getSharedPreferences("rhythm_v1",0).all)}
    @Test fun invalidNamesLeaveOldMetadataAndAudioUntouched(){val id=sound();val lib=SoundLibrary(app);lib.rename(id,"Rain");val old=File(root,"names.json").readBytes();for(n in listOf("", "   ","a".repeat(65),"../prefs","a\\b",".","..","Rain\nAlarm","\u0000", "a\u202eb")){assertThrows(IllegalArgumentException::class.java){lib.rename(id,n)};assertArrayEquals(old,File(root,"names.json").readBytes());assertTrue(File(root,"$id.wav").exists())}}
    @Test fun olderUnlabeledImportsAreVisibleWithoutRewritingAudio(){val id=sound();val old=File(root,"$id.wav").readBytes();assertEquals(SoundLabel.default(id),SoundLibrary(app).entries().single().name);assertArrayEquals(old,File(root,"$id.wav").readBytes())}
    @Test fun unreferencedDeleteReclaimsQuotaAndPrunesBoundedNamesOnNextOperation(){val id=sound();val lib=SoundLibrary(app);lib.rename(id,"Old");lib.remove(id);assertFalse(File(root,"$id.wav").exists());assertTrue(lib.entries().isEmpty());val next=sound();lib.rename(next,"New");assertEquals(setOf(next),FileSoundNames(root).read().keys);assertTrue(root.listFiles()!!.none {it.extension=="tmp"})}
    @Test fun configuredOffSoundCannotBeDeletedOrSilentlyDetached(){val id=sound();val kv=Preferences(app);LiveStore(kv).save(LiveState(configs=mapOf("pack" to GentleConfig(false,sound="private:$id"))));val raw=kv.get(LiveStore.KEY);val names=Names(mapOf(id to "Off reference"));assertThrows(IllegalStateException::class.java){SoundLibrary(app,names).remove(id)};assertEquals(0,names.writes);assertEquals(raw,kv.get(LiveStore.KEY));assertTrue(File(root,"$id.wav").exists())}
    @Test fun previewAndDraftLeaseCountsAreReferenceSafeAndIdempotent(){val id=sound();val first=PrivateSounds.pin("private:$id")!!;val second=PrivateSounds.pin("private:$id")!!;val lib=SoundLibrary(app);try {assertThrows(IllegalStateException::class.java){lib.remove(id)};first.close();first.close();assertThrows(IllegalStateException::class.java){lib.remove(id)}}finally{first.close();second.close()};lib.remove(id);assertFalse(File(root,"$id.wav").exists())}
    @Test fun draftExtraReferenceIsCheckedBeforeDeleting(){val id=sound();assertThrows(IllegalStateException::class.java){SoundLibrary(app).remove(id,setOf("private:$id"))};assertTrue(File(root,"$id.wav").exists())}
    @Test fun failedMetadataWriteNeverDeletesBytesOrChangesName(){val id=sound();val names=Names(mapOf(id to "Keep"),true);val lib=SoundLibrary(app,names);assertThrows(java.io.IOException::class.java){lib.rename(id,"Changed")};assertThrows(java.io.IOException::class.java){lib.remove(id)};assertEquals("Keep",lib.entries().single().name);assertTrue(File(root,"$id.wav").exists());assertTrue(root.listFiles()!!.none {it.extension=="tmp"})}
    @Test fun failedEraseRetainsOldNameAndBytes(){val id=sound();val lib=SoundLibrary(app,Names(mapOf(id to "Keep")),{false});val old=File(root,"$id.wav").readBytes();assertThrows(IllegalStateException::class.java){lib.remove(id)};assertEquals("Keep",lib.entries().single().name);assertArrayEquals(old,File(root,"$id.wav").readBytes())}
    @Test fun realAtomicNameWriteFailureCleansTempAndKeepsPreviousFile(){val id=sound();val names=FileSoundNames(root);names.write(mapOf(id to "Original"));val old=File(root,"names.json").readBytes();File(root,"names.json.tmp").mkdir();assertThrows(Exception::class.java){names.write(mapOf(id to "Changed"))};assertArrayEquals(old,File(root,"names.json").readBytes());assertFalse(File(root,"names.json.tmp").exists());assertTrue(File(root,"$id.wav").exists())}
    @Test fun corruptLabelsFailClosedWithoutReset() {
        val id = sound()
        val labels = File(root, "names.json").apply { writeText("broken") }
        assertThrows(Exception::class.java) { SoundLibrary(app).remove(id) }
        assertEquals("broken", labels.readText())
        assertTrue(PrivateSounds(app).file(id).exists())
    }

    @Test fun unreadableLegacyReminderStatePreventsSoundRemoval() {
        val id = sound()
        val previous = app.getSharedPreferences("rhythm_v1", Context.MODE_PRIVATE)
        assertTrue(previous.edit().putString(LiveStore.KEY, "broken reminders").commit())
        assertThrows(IllegalStateException::class.java) { SoundLibrary(app).remove(id) }
        assertEquals("broken reminders", previous.getString(LiveStore.KEY, null))
        assertTrue(PrivateSounds(app).file(id).exists())
    }
    @Test fun deletedAssetCannotAcquireNewConfigurationReference(){val id=sound();SoundLibrary(app).remove(id);val bridge=LiveReminders(app);val reminder=bridge.createPlanned(PlanVersion(LocalDate.now(),"Pack your bag"),GentleConfig(false));val before=Preferences(app).get(LiveStore.KEY);assertThrows(IllegalArgumentException::class.java){bridge.configure(reminder.id,GentleConfig(false,sound="private:$id"))};assertEquals(before,Preferences(app).get(LiveStore.KEY))}
    @Test fun renameDoesNotChangeCurrentPendingTokensOrSavedReports(){val id=sound();val kv=Preferences(app);val day=LocalDate.of(2026,11,9);val occurrence=FlexiblePlanner.identity("pack",day);FlexiblePlanner(kv){day}.report(occurrence,Entry(Outcome.DONE));val a=LiveAlert(occurrence,day,Instant.EPOCH,"nonce","sig",true);LiveStore(kv).save(LiveState(configs=mapOf("pack" to GentleConfig(true,true,true,"private:$id")),pending=mapOf(occurrence to a)));val before=app.getSharedPreferences("rhythm_v1",0).all.toMap();SoundLibrary(app).rename(id,"Changed cosmetic label");assertEquals(before,app.getSharedPreferences("rhythm_v1",0).all);assertEquals(a,LiveStore(kv).read()!!.pending[occurrence])}
}
