package dev.gaurav.dailyrhythm

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.content.res.AssetFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class SoundAccessException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** No provider, persisted grants, original-URI playback, network or broad storage access. */
internal class PrivateSounds(private val context: Context) {
    companion object {
        private val lock=Any()
        private val leases=mutableMapOf<String,Int>()
        private val pending=ConcurrentHashMap.newKeySet<String>()
        fun <T> locked(block:()->T):T = synchronized(lock,block)
        fun publishing(id:String)=id in pending
        fun pinned(id:String)=locked {(leases[id] ?: 0)>0}
        fun pin(sound:String):java.io.Closeable? = locked {
            if(!sound.startsWith("private:"))return@locked null
            val id=sound.removePrefix("private:");require(AudioPolicy.opaque(id))
            leases[id]=(leases[id] ?: 0)+1
            val done=AtomicBoolean(false)
            java.io.Closeable {if(done.compareAndSet(false,true))locked {val count=(leases[id] ?: 1)-1;if(count==0)leases.remove(id)else leases[id]=count}}
        }
        private val busy = AtomicBoolean(false)
        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "private-sound-copy").apply { isDaemon = true } }
        private val canceller = ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),
            {r->Thread(r,"private-sound-cancel").apply {isDaemon=true}},ThreadPoolExecutor.DiscardPolicy())
        fun trustedProvider(context: Context, uri: Uri, fromTonePicker: Boolean = false) {
            val authority = uri.authority
            require(!authority.isNullOrEmpty() && '@' !in authority && uri.fragment == null &&
                uri.pathSegments.isNotEmpty()) { "Choose a single audio file." }
            val pm = context.packageManager
            if (uri.scheme == "android.resource") {
                require(fromTonePicker && authority != context.packageName &&
                    (uri.pathSegments.size == 1 && uri.lastPathSegment?.toIntOrNull() != null ||
                        uri.pathSegments.size == 2 && uri.pathSegments.first() == "raw")) {
                    "Choose a sound from the phone’s picker."
                }
                val owner = runCatching { pm.getApplicationInfo(authority, 0) }.getOrNull()
                if (owner == null || owner.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                    throw SoundAccessException("Android couldn’t share this tone. Open the song in Files.")
                }
                return
            }
            require(uri.scheme == "content" && !DocumentsContract.isTreeUri(uri)) { "Choose a single audio file." }
            val owner = pm.resolveContentProvider(authority, 0)?.applicationInfo
            require(owner?.uid != Process.myUid()) { "Choose audio from your phone." }
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            val granted = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flags) == PackageManager.PERMISSION_GRANTED
            val systemSource = owner != null &&
                owner.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (!granted && (!fromTonePicker || !systemSource))
                throw SoundAccessException("Android’s picker didn’t share this sound. Open the song in Files.")
        }

        fun deviceTone(context: Context, uri: Uri): Boolean = runCatching {
            require(uri.scheme == "content" && uri.authority in setOf("media", "settings") && uri.query == null && uri.fragment == null)
            val pm = context.packageManager
            val p = requireNotNull(pm.resolveContentProvider(uri.authority!!, 0)); val a = p.applicationInfo
            val expected = if(uri.authority == "settings") setOf("com.android.providers.settings") else setOf("com.android.providers.media", "com.google.android.providers.media.module", "com.android.providers.media.module")
            require(p.packageName in expected && a.packageName == p.packageName && a.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 && pm.getPackageUid(p.packageName, 0) == a.uid && pm.getPackagesForUid(a.uid)?.contains(p.packageName) == true)
            require(if(uri.authority == "settings") uri.path in setOf("/system/alarm_alert", "/system/notification_sound", "/system/ringtone") else uri.path?.matches(Regex("/(internal|external|external_primary)/audio/media/[0-9]+")) == true)
            true
        }.getOrDefault(false)
    }
    private val root = File(context.noBackupFilesDir, "sounds").apply { check(mkdirs() || isDirectory); require(!java.nio.file.Files.isSymbolicLink(toPath()) && canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile) }
    private fun contained(name: String): File = File(root, name).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    fun file(id: String): File {
        require(AudioPolicy.opaque(id))
        val asset = contained("$id.audio").takeIf(File::isFile) ?: contained("$id.wav")
        require(asset.isFile && !java.nio.file.Files.isSymbolicLink(asset.toPath())) { "Private sound missing." }
        return asset
    }
    internal fun directory()=root
    internal fun rawIds():List<String> = locked {root.listFiles().orEmpty().filter {it.extension in setOf("wav", "audio") && AudioPolicy.opaque(it.nameWithoutExtension)}.map {it.nameWithoutExtension}.distinct().sorted()}
    fun ids(): List<String> = locked {rawIds().filter {it !in pending}}
    /** Interrupted copies and atomic-name writes are never selectable temporary assets. */
    fun cleanTemps() = locked {if(!busy.get())root.listFiles().orEmpty().filter {it.name.endsWith(".tmp")}.forEach {it.delete()}}
    fun import(uri: Uri, fromTonePicker: Boolean = false): String {
        trustedProvider(context, uri, fromTonePicker)
        check(busy.compareAndSet(false, true)) { "An import is still running. No second worker started." }
        val cancelled = AtomicBoolean(false); val signal = CancellationSignal(); val descriptor = AtomicReference<AssetFileDescriptor?>()
        val id = UUID.randomUUID().toString(); val temp = contained("$id.tmp"); val sound = contained("$id.audio")
        pending+=id
        val job = io.submit<String> {
            try {
                val files = rawIds().map(::file)
                AudioPolicy.budget(files.size, files.sumOf { it.length() }, root.usableSpace)
                val pfd = try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                        ?: throw SoundAccessException("Android couldn’t open this sound. Open the song in Files.")
                } catch (error: SecurityException) {
                    throw SoundAccessException("Android denied access to this sound. Open the song in Files.", error)
                } catch (error: java.io.FileNotFoundException) {
                    throw SoundAccessException("Android couldn’t open this sound. Open the song in Files.", error)
                }
                descriptor.set(pfd)
                check(!cancelled.get())
                // Copy first. Probe only app-private bytes, never the source provider URI.
                pfd.createInputStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(16*1024); var bytes = 0L; val deadline = System.nanoTime() + AudioPolicy.IO_MS*1_000_000
                        while(true) {
                            check(!cancelled.get() && System.nanoTime() < deadline) { "Import timed out." }
                            val n = input.read(buffer); if(n<0) break
                            bytes += n; require(bytes <= AudioPolicy.MAX_FILE) { "Sound exceeds 10 MiB." }
                            output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    }
                }
                AudioPolicy.song(temp)
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull()
                locked {
                    check(!cancelled.get()) { "Import cancelled." }
                    AudioPolicy.budget(files.size, files.sumOf { it.length() }, root.usableSpace + temp.length(), temp.length())
                    check(!sound.exists() && temp.renameTo(sound)) { "Could not publish private sound." }
                    try {SoundLibrary(context).register(id, name);check(!cancelled.get()) {"Import cancelled during publication."}}
                    catch(e:Exception) {sound.delete();throw e}
                }
                id
            } finally { descriptor.getAndSet(null)?.let { runCatching { it.close() } }; temp.delete(); if(cancelled.get())locked {if(id in pending)sound.delete()}; busy.set(false) }
        }
        return try { job.get(AudioPolicy.IO_MS + AudioPolicy.PROBE_MS, TimeUnit.MILLISECONDS).also {pending-=id} }
        catch(e: Exception) {
            cancelled.set(true); job.cancel(true); temp.delete(); sound.delete();pending-=id
            // A broken provider's cancellation callback must not block the caller either.
            canceller.execute {runCatching {signal.cancel()}; descriptor.get()?.let {runCatching {it.close()}}}
            val cause = e.cause
            if (cause is SoundAccessException) throw cause
            throw IllegalArgumentException(cause?.message ?: "Import timed out or failed; existing sounds were kept.", e)
        }
    }
}
