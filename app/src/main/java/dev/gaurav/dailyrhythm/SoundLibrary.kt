package dev.gaurav.dailyrhythm

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class LibrarySound(val id:String,val name:String,val bytes:Long)
internal interface SoundNames { fun read():Map<String,String>; fun write(names:Map<String,String>) }
internal object SoundLabel {
    private fun validCharacters(raw: String): Boolean = raw.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == raw &&
        raw.none { Character.isISOControl(it) || Character.getType(it) in setOf(
            Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt()
        ) || it == '/' || it == '\\' }

    fun checked(raw:String):String {
        require(raw.length <= 128 && validCharacters(raw)) { "Use a plain display name, not a path or control characters." }
        return raw.trim().also {require(it.isNotBlank() && it.length<=64 && it!="." && it!="..") {"Sound name must be 1–64 characters."}}
    }

    fun imported(raw: String): String {
        require(raw.length <= 1024 && validCharacters(raw))
        val name = raw.trim()
        return checked(if (name.length <= 64) name else name.take(63).dropLastWhile(Char::isHighSurrogate).trimEnd() + "…")
    }
    fun default(id:String)="Imported WAV · ${id.takeLast(6)}"
}
/** Names only, never file paths. Atomic replacement or fail closed; no non-atomic fallback. */
internal class FileSoundNames(private val root:File):SoundNames {
    private fun member(name:String)=File(root,name).also {require(it.canonicalFile.parentFile==root.canonicalFile && !Files.isSymbolicLink(it.toPath()))}
    override fun read():Map<String,String> {
        val f=member("names.json");if(!f.exists())return emptyMap()
        require(f.length() in 1..8192) {"Sound names unavailable; no automatic reset."}
        val j=JSONObject(f.readText());require(j.getInt("schema")==1)
        val a=j.getJSONObject("names");require(a.length()<=AudioPolicy.MAX_COUNT)
        return a.keys().asSequence().associateWith {id->require(AudioPolicy.opaque(id));SoundLabel.checked(a.getString(id))}
    }
    override fun write(names:Map<String,String>) {
        require(names.size<=AudioPolicy.MAX_COUNT)
        val j=JSONObject();names.forEach {(id,n)->require(AudioPolicy.opaque(id));j.put(id,SoundLabel.checked(n))}
        val bytes=JSONObject().put("schema",1).put("names",j).toString().toByteArray();require(bytes.size<=8192)
        val tmp=member("names.json.tmp");val dest=member("names.json")
        try {
            FileOutputStream(tmp).use {it.write(bytes);it.fd.sync()}
            Files.move(tmp.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {tmp.delete()}
    }
}

internal class SoundLibrary(private val context:Context,
    private val names:SoundNames=FileSoundNames(PrivateSounds(context).directory()),
    private val erase:(File)->Boolean={it.delete()}) {
    private val sounds=PrivateSounds(context)
    fun entries():List<LibrarySound> = PrivateSounds.locked {
        val labels=names.read();sounds.ids().map {id->LibrarySound(id,labels[id] ?: SoundLabel.default(id),sounds.file(id).length())}
    }
    fun label(sound:String):String = when {
        sound=="default"->"Bundled soft pulse"
        sound.startsWith("private:")->runCatching {entries().firstOrNull {it.id==sound.removePrefix("private:") }?.name}.getOrNull() ?: "Imported sound unavailable"
        else->"Device alarm tone"
    }
    private fun currentNames()=names.read().filterKeys {it in sounds.rawIds()}
    fun register(id:String, displayName: String? = null) = PrivateSounds.locked {
        sounds.file(id)
        val label = displayName?.let { runCatching { SoundLabel.imported(it) }.getOrNull() }
        names.write(currentNames() + (id to (label ?: "Imported sound")))
    }
    fun rename(id:String,raw:String) = PrivateSounds.locked {
        val label=SoundLabel.checked(raw);sounds.file(id);check(!PrivateSounds.publishing(id)) {"Import is still being published."}
        names.write(currentNames()+(id to label))
    }
    fun remove(id:String,extraSounds:Set<String> = emptySet()) = PrivateSounds.locked {
        check(!PrivateSounds.publishing(id)) {"Import is still being published."}
        val file=sounds.file(id)
        val configs=LiveStore(Preferences(context)).read()?.configs.orEmpty().values.map {it.sound}
        check("private:$id" !in configs && "private:$id" !in extraSounds && !PrivateSounds.pinned(id)) {"Sound is referenced by reminder settings (including Off), a draft, preview or active/queued alert. Choose another sound first."}
        // Commit labels BEFORE irreversible deletion. If either step fails, old references are untouched.
        // Retain this bounded label until the next metadata operation; a deleted ID is never listed.
        names.write(currentNames()+(id to (names.read()[id] ?: SoundLabel.default(id))))
        check(erase(file)) {"Could not remove sound; bytes and name were retained. Retry after checking storage."}
    }
    fun ensureSelectable(sound:String) = PrivateSounds.locked {
        if(sound.startsWith("private:")) {val id=sound.removePrefix("private:");check(!PrivateSounds.publishing(id));sounds.file(id)}
    }
}
