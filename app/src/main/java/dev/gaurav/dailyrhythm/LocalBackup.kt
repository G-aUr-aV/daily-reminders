package dev.gaurav.dailyrhythm

import android.content.Context
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object LocalBackup {
    private const val maxValuesBytes = 20_000_000L
    private const val maxStringBytes = 8_000_000
    private const val maxKeys = 100_000
    private val portableKeys = setOf(FlexiblePlanner.KEY, LiveStore.KEY, RhythmStore.REMINDERS_KEY,
        SNOOZE_PREFERENCE, "theme.amoled", "reminder.order.v1")

    fun portableKey(key: String): Boolean = key in portableKeys ||
        listOf("entry.", "rounds.", "schedule.").any { key.startsWith(it) && key.length <= 256 }

    private data class Sound(val id: String, val name: String, val file: File)

    fun export(context: Context, target: OutputStream, password: CharArray) {
        val (values, entries, leases) = PrivateSounds.locked {
            val prefs = Preferences(context)
            val values = prefs.portableValues().toMutableMap()
            val portable = MapStorage(values)
            // Action tokens and pending platform deliveries belong to the original installation.
            val live = requireNotNull(LiveStore(portable).read())
            LiveStore(portable).save(live.copy(active = emptyMap(), pending = emptyMap()))
            requireNotNull(values[FlexiblePlanner.KEY]).let(FlexCodec::decode)
            val sounds = PrivateSounds(context)
            val entries = SoundLibrary(context).entries().map { Sound(it.id, it.name, sounds.file(it.id)) }
            Triple(values, entries, entries.mapNotNull { PrivateSounds.pin("private:${it.id}") })
        }
        try {
            write(target, password, values, entries)
        } finally {
            leases.forEach(Closeable::close)
        }
    }

    private fun write(target: OutputStream, password: CharArray, values: Map<String, String>, entries: List<Sound>) {
        require(values.size <= maxKeys && values.values.sumOf { it.toByteArray().size.toLong() } <= maxValuesBytes)
        require(entries.size <= AudioPolicy.MAX_COUNT && entries.sumOf { it.file.length() } <= AudioPolicy.MAX_TOTAL)
        DataOutputStream(GZIPOutputStream(BackupEncryption.writer(target, password))).use { out ->
            out.writeInt(1)
            out.writeInt(values.size)
            values.toSortedMap().forEach { (key, value) ->
                val bytes = value.toByteArray()
                require(bytes.size <= maxStringBytes)
                out.writeUTF(key)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            out.writeInt(entries.size)
            entries.forEach { sound ->
                val size = sound.file.length()
                require(size in 1..AudioPolicy.MAX_FILE)
                out.writeUTF(sound.id)
                out.writeUTF(sound.name)
                out.writeUTF(sound.file.extension)
                out.writeLong(size)
                sound.file.inputStream().use { it.copyTo(out) }
            }
        }
    }

    class Staged internal constructor(private val directory: File, private val values: Map<String, String>,
                                     private val names: Map<String, String>) : Closeable {
        /** Publish new files before their references. Old files are kept until the state commit. */
        fun restore(context: Context) = PrivateSounds.locked {
            val prefs = Preferences(context)
            val sounds = PrivateSounds(context)
            val existing = sounds.rawIds().associateWith(sounds::file)
            val imported = directory.listFiles().orEmpty().associateBy { it.nameWithoutExtension }
            val namesFile = FileSoundNames(sounds.directory())
            val oldNames = namesFile.read()
            val added = mutableListOf<File>()
            existing.keys.intersect(imported.keys).forEach { id ->
                require(existing.getValue(id).sameBytes(imported.getValue(id))) {
                    "An imported sound conflicts with an existing sound. Restore on a fresh installation."
                }
            }
            val files = existing + imported
            require(files.size <= AudioPolicy.MAX_COUNT && files.values.sumOf(File::length) <= AudioPolicy.MAX_TOTAL) {
                "This backup and the sounds already on this phone exceed the sound library limit."
            }
            val bridge = LiveReminders(context)
            val previous = LiveStore(prefs).read()
            try {
                (imported - existing.keys).values.forEach { file ->
                    val destination = File(sounds.directory(), file.name)
                    check(!destination.exists())
                    Files.move(file.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    added += destination
                }
                namesFile.write(oldNames.filterKeys { it in existing } + names)
                prefs.putAll(prefs.portableValues().keys.associateWith { null } + values)
            } catch (error: Exception) {
                added.forEach(File::delete)
                runCatching { namesFile.write(oldNames) }
                throw error
            }
            // Old PendingIntents can't act on the replacement state even if Android cancellation fails.
            val platform = AndroidLivePlatform(context)
            previous?.pending?.values?.forEach { runCatching { platform.cancel(it) } }
            previous?.active?.keys?.forEach { runCatching { platform.dismiss(it) } }
            runCatching { bridge.sync(AlarmSync.RESTORE_PENDING) }.isSuccess
        }

        override fun close() { directory.deleteRecursively() }
    }

    fun read(context: Context, source: InputStream, password: CharArray): Staged {
        val root = File(context.cacheDir, "backup-restore").apply { check(mkdirs() || isDirectory) }
        val directory = Files.createTempDirectory(root.toPath(), "staged-").toFile()
        try {
            BackupEncryption.Reader(source, password).use { authenticated ->
                DataInputStream(GZIPInputStream(authenticated)).use { input ->
                    require(input.readInt() == 1)
                    val values = mutableMapOf<String, String>()
                    var valueBytes = 0L
                    repeat(input.readInt().also { require(it in 1..maxKeys) }) {
                        val key = input.readUTF()
                        require(portableKey(key) && key !in values)
                        val size = input.readInt()
                        valueBytes += size
                        require(size in 0..maxStringBytes && valueBytes <= maxValuesBytes)
                        values[key] = ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
                    }
                    val plan = FlexCodec.decode(requireNotNull(values[FlexiblePlanner.KEY]))
                    val live = requireNotNull(LiveStore(MapStorage(values)).read())
                    require(live.active.isEmpty() && live.pending.isEmpty())
                    require(live.configs.keys.all { it in plan.items })
                    val names = mutableMapOf<String, String>()
                    var soundBytes = 0L
                    repeat(input.readInt().also { require(it in 0..AudioPolicy.MAX_COUNT) }) {
                        val id = input.readUTF()
                        require(AudioPolicy.opaque(id) && id !in names)
                        names[id] = SoundLabel.checked(input.readUTF())
                        val extension = input.readUTF()
                        require(extension in setOf("wav", "audio"))
                        val size = input.readLong()
                        require(size in 1..AudioPolicy.MAX_FILE)
                        soundBytes += size
                        require(soundBytes <= AudioPolicy.MAX_TOTAL)
                        val file = File(directory, "$id.$extension")
                        FileOutputStream(file).use { out ->
                            val buffer = ByteArray(16_384)
                            var remaining = size
                            while (remaining > 0) {
                                val count = minOf(remaining, buffer.size.toLong()).toInt()
                                input.readFully(buffer, 0, count)
                                out.write(buffer, 0, count)
                                remaining -= count
                            }
                            out.fd.sync()
                        }
                        if (extension == "wav") AudioPolicy.wav(file) else AudioPolicy.song(file)
                    }
                    require(input.read() == -1 && authenticated.read() == -1)
                    require(live.configs.values.all { !it.sound.startsWith("private:") || it.sound.removePrefix("private:") in names }) {
                        "Backup is missing a referenced sound."
                    }
                    return Staged(directory, values, names)
                }
            }
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    private fun File.sameBytes(other: File): Boolean {
        if (length() != other.length()) return false
        fun digest(file: File): ByteArray = file.inputStream().use { stream ->
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16_384)
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                sha.update(buffer, 0, count)
            }
            sha.digest()
        }
        return digest(this).contentEquals(digest(other))
    }

    private class MapStorage(private val values: MutableMap<String, String>) : KeyValue {
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }
}
