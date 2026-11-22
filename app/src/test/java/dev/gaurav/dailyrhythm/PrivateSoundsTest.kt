package dev.gaurav.dailyrhythm

import android.app.Application
import android.content.*
import android.content.pm.*
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.OpenableColumns
import android.os.ParcelFileDescriptor
import org.robolectric.shadows.ShadowContentResolver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class PrivateSoundsTest {
    private lateinit var app:Application
    @Before fun setup(){app=RuntimeEnvironment.getApplication();File(app.noBackupFilesDir,"sounds").deleteRecursively()}
    @Test fun sourceSchemesCloudAndUnknownAuthoritiesRejectedBeforeCopy(){
        for(uri in listOf("file:///sdcard/sound.wav","https://example.invalid/a.wav","content://cloud/document/1","content://com.android.providers.downloads.documents/document/1","content://com.android.externalstorage.documents/document/primary%3Asound.wav")) {
            assertThrows(IllegalArgumentException::class.java){PrivateSounds.trustedProvider(withReadGrant(null),Uri.parse(uri))}
            assertTrue(PrivateSounds(app).ids().isEmpty())
        }
    }
    @Test fun uriSpellingWithoutInstalledVerifiedOwnerDoesNotPass(){assertThrows(IllegalArgumentException::class.java){PrivateSounds.trustedProvider(withReadGrant(null),Uri.parse("content://com.android.externalstorage.documents/document/primary%3Asound.wav"))}}
    @Test fun arbitraryRemoteAndUnownedDeviceTonesRejected(){for(uri in listOf("https://example.invalid/tone","file:///tmp/tone","content://evil/audio/media/1","content://media/external/audio/media/1","content://settings/system/alarm_alert"))assertFalse(PrivateSounds.deviceTone(app,Uri.parse(uri)))}
    @Test fun opaqueCanonicalStorageAndMissingIdsFailClosed(){val s=PrivateSounds(app);for(id in listOf("../rhythm_v1","prefs.xml","bad"))assertThrows(IllegalArgumentException::class.java){s.file(id)};assertThrows(IllegalArgumentException::class.java){s.file(java.util.UUID.randomUUID().toString())}}
    @Test fun idleRecoveryRemovesAllUnownedTempsButKeepsPublishedAudio(){
        val s=PrivateSounds(app);val root=File(app.noBackupFilesDir,"sounds")
        val old=File(root,"orphan.tmp").apply {writeText("partial");setLastModified(System.currentTimeMillis()-7_200_000)}
        val current=File(root,"current.tmp").apply {writeText("incomplete")}
        val id=java.util.UUID.randomUUID().toString();val published=File(root,"$id.wav").apply {writeText("retained")}
        s.cleanTemps();assertFalse(old.exists());assertFalse(current.exists());assertTrue(published.exists());assertEquals(listOf(id),s.ids())
    }
    @Test fun failedImportNeverChangesReports(){
        val kv=Preferences(app);val p=FlexiblePlanner(kv){java.time.LocalDate.of(2026,11,9)};val id=FlexiblePlanner.identity("pack",java.time.LocalDate.of(2026,11,9));p.report(id,Entry(Outcome.DONE));val raw=kv.get(FlexiblePlanner.KEY)
        assertThrows(IllegalArgumentException::class.java){PrivateSounds(app).import(Uri.parse("https://example.invalid/a"))};assertEquals(raw,kv.get(FlexiblePlanner.KEY))
    }
    class FixtureProvider:ContentProvider() {
        companion object {lateinit var source:File;var opens=0;var stall=false;var interrupted=CountDownLatch(1)}
        override fun onCreate()=true
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            opens++
            if(stall)try {Thread.sleep(30_000)}catch(e:InterruptedException){interrupted.countDown();throw java.io.IOException("Cancelled")}
            return ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(u:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("Morning song.wav")) }
        override fun getType(u:Uri)="audio/wav"
        override fun insert(u:Uri,v:ContentValues?):Uri?=null
        override fun delete(u:Uri,s:String?,a:Array<out String>?)=0
        override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
    }
    private fun provider(
        system: Boolean = true,
        pkg: String = "com.android.externalstorage",
        authority: String = "com.android.externalstorage.documents",
        document: Boolean = true
    ): Uri {
        val ai=ApplicationInfo().apply {packageName=pkg;uid=10011;flags=if(system)ApplicationInfo.FLAG_SYSTEM else 0}
        val info=ProviderInfo().apply {name=FixtureProvider::class.java.name;packageName=pkg;applicationInfo=ai;this.authority=authority;exported=true;grantUriPermissions=true}
        val pm=Shadows.shadowOf(app.packageManager)
        pm.installPackage(PackageInfo().apply {packageName=pkg;applicationInfo=ai;providers=arrayOf(info)})
        pm.addOrUpdateProvider(info)
        pm.setPackagesForUid(ai.uid,pkg)
        if (document) pm.addResolveInfoForIntent(Intent("android.content.action.DOCUMENTS_PROVIDER"),ResolveInfo().apply {providerInfo=info})
        val provider=FixtureProvider();provider.attachInfo(app,info);ShadowContentResolver.registerProviderInternal(authority,provider)
        FixtureProvider.source=File(app.cacheDir,"selected.wav");FixtureProvider.opens=0;FixtureProvider.stall=false;FixtureProvider.interrupted=CountDownLatch(1)
        val path = if (document) "document/primary%3AMusic%2Fselected.wav" else "picks/19"
        return Uri.parse("content://$authority/$path")
    }

    private fun withReadGrant(uri: Uri?): Context = object : ContextWrapper(app) {
        override fun checkUriPermission(requested: Uri, pid: Int, uid: Int, mode: Int): Int =
            if (requested == uri && mode == Intent.FLAG_GRANT_READ_URI_PERMISSION) PackageManager.PERMISSION_GRANTED
            else PackageManager.PERMISSION_DENIED
    }
    private fun bytes():ByteArray=ByteBuffer.allocate(16044).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray());putInt(16036);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16);put("data".toByteArray());putInt(16000)
    }.array()
    @Test fun verifiedOwnerCopiesValidBytesPrivatelyAndOriginalCanDisappear(){
        val uri=provider();FixtureProvider.source.writeBytes(bytes());val sounds=PrivateSounds(withReadGrant(uri));val id=sounds.import(uri)
        assertTrue(AudioPolicy.opaque(id));assertArrayEquals(bytes(),sounds.file(id).readBytes());FixtureProvider.source.delete();assertEquals(1000,AudioPolicy.wav(sounds.file(id)))
        assertTrue(File(app.noBackupFilesDir,"sounds").listFiles()!!.none {it.extension=="tmp"})
    }
    @Test fun systemProviderWithoutReadGrantCannotBeOpened() {
        val uri = provider()
        assertThrows(IllegalArgumentException::class.java) { PrivateSounds(withReadGrant(null)).import(uri) }
        assertEquals(0, FixtureProvider.opens)
    }

    @Test fun grantedVendorDocumentWorksWithoutAnAuthorityAllowlist() {
        val uri = provider(false, "vendor.files", "vendor.files.documents")
        FixtureProvider.source.writeBytes(bytes())
        val sounds = PrivateSounds(withReadGrant(uri))
        assertArrayEquals(bytes(), sounds.file(sounds.import(uri)).readBytes())
    }

    @Test fun grantedVendorSongFromPhonePickerBecomesSavedPrivateSound() {
        val uri = provider(true, "vendor.tones", "vendor.tones.audio", document = false)
        FixtureProvider.source.writeBytes(bytes())
        val context = withReadGrant(uri)
        val sounds = PrivateSounds(context)
        val asset = sounds.import(uri, fromTonePicker = true)
        assertTrue(FixtureProvider.source.delete())
        val config = GentleConfig(enabled = false, ringing = true, precise = true,
            sound = "private:$asset", toneTitle = SoundLibrary(context).label("private:$asset"))
        val reminder = LiveReminders(context).createPlanned(
            PlanVersion(java.time.LocalDate.now(), "Wake up", time = java.time.LocalTime.NOON), config)
        val reopened = LiveReminders(context).config(reminder.id)
        assertEquals("Morning song.wav", reopened.toneTitle)
        assertEquals(config.sound, reopened.sound)
        assertArrayEquals(bytes(), sounds.file(asset).readBytes())
    }

    @Test fun grantAllowsOpaqueVendorUriAndDenialStopsBeforeOpen() {
        val uri = provider(false, "vendor.tones", "vendor.tones.audio", document = false)
        FixtureProvider.source.writeBytes(bytes())
        assertThrows(SoundAccessException::class.java) {
            PrivateSounds(withReadGrant(null)).import(uri, fromTonePicker = true)
        }
        assertEquals(0, FixtureProvider.opens)
        val sounds = PrivateSounds(withReadGrant(uri))
        assertArrayEquals(bytes(), sounds.file(sounds.import(uri, fromTonePicker = true)).readBytes())
    }

    @Test fun apkContentsCannotBeImportedAsAudioEvenWithGrant() {
        val uri = provider()
        java.util.zip.ZipOutputStream(FixtureProvider.source.outputStream()).use {
            it.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            it.write("manifest".toByteArray())
            it.closeEntry()
        }
        val sounds = PrivateSounds(withReadGrant(uri))
        assertThrows(IllegalArgumentException::class.java) { sounds.import(uri) }
        assertTrue(sounds.ids().isEmpty())
    }

    @Test fun malformedOrOversizedCopyNeverPublishesAndCleansTemp(){
        val uri=provider();val sounds=PrivateSounds(withReadGrant(uri))
        for(b in listOf("#EXTM3U https://example.invalid/remote".toByteArray(),ByteArray(AudioPolicy.MAX_FILE.toInt()+1))){
            FixtureProvider.source.writeBytes(b);assertThrows(IllegalArgumentException::class.java){sounds.import(uri)};assertTrue(sounds.ids().isEmpty());assertTrue(File(app.noBackupFilesDir,"sounds").listFiles()!!.none {it.extension=="tmp"})
        }
    }
    @Test fun countQuotaRejectsBeforeOpeningProviderAndRetainsFiles(){val uri=provider();FixtureProvider.source.writeBytes(bytes());val s=PrivateSounds(withReadGrant(uri));repeat(16){File(app.noBackupFilesDir,"sounds/${java.util.UUID.randomUUID()}.wav").writeText("retained")};assertThrows(IllegalArgumentException::class.java){s.import(uri)};assertEquals(0,FixtureProvider.opens);assertEquals(16,s.ids().size)}
    @Test fun providerStallIsBoundedAndCannotPublishAfterTimeout(){
        val uri=provider();FixtureProvider.stall=true;val s=PrivateSounds(withReadGrant(uri))
        assertThrows(IllegalArgumentException::class.java){s.import(uri)}
        assertTrue(FixtureProvider.interrupted.await(5,TimeUnit.SECONDS));assertTrue(s.ids().isEmpty())
    }

}
