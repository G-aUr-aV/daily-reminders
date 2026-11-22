package dev.gaurav.dailyrhythm

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.FileInputStream
import java.io.File
import java.io.RandomAccessFile

/** Fixed storage limits and validation of the bytes copied from Android’s picker. */
internal object AudioPolicy {
    const val MAX_FILE = 10L * 1024 * 1024
    const val MAX_TOTAL = 64L * 1024 * 1024
    const val MAX_COUNT = 16
    const val FREE_FLOOR = 32L * 1024 * 1024
    const val IO_MS = 15_000L
    const val PROBE_MS = 2_000L
    fun budget(count: Int, total: Long, free: Long, incoming: Long = MAX_FILE) {
        require(count in 0 until MAX_COUNT) { "Sound library full (16). Existing sounds were kept." }
        require(total in 0..MAX_TOTAL && incoming in 1..MAX_FILE && total + incoming <= MAX_TOTAL) { "Sound storage budget exceeded (64 MiB total, 10 MiB each)." }
        require(free >= FREE_FLOOR + incoming) { "Keep at least 32 MiB free after importing." }
    }
    fun opaque(id: String): Boolean = runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false)
    /** Probe the completed private copy. Reject containers with non-audio tracks. */
    fun song(file: File) {
        require(file.length() in 1..MAX_FILE) { "Choose an audio file up to 10 MiB." }
        val header = ByteArray(12)
        val isWav = FileInputStream(file).use { it.read(header) == header.size } &&
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
        if (isWav && runCatching { wav(file) }.isSuccess) return
        val extractor = MediaExtractor()
        try {
            FileInputStream(file).use { extractor.setDataSource(it.fd) }
            require(extractor.trackCount == 1)
            val track = extractor.getTrackFormat(0)
            val format = track.getString(MediaFormat.KEY_MIME)
            require(format in setOf("audio/mpeg", "audio/aac", "audio/mp4a-latm", "audio/opus", "audio/vorbis", "audio/flac", "audio/raw"))
            extractor.selectTrack(0)
            require(extractor.sampleTime >= 0 && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0)
        } catch (error: Exception) {
            throw IllegalArgumentException("This file couldn’t be read as audio. Try an unprotected MP3 or M4A, up to 10 MiB.", error)
        } finally {
            extractor.release()
        }
    }
    /** Fixed-size header, bounded random access. No parser follows embedded URLs or external chunks. */
    fun wav(file: File, clock: () -> Long = { System.nanoTime() / 1_000_000 }): Long {
        val deadline = clock() + PROBE_MS
        require(file.length() in 44..MAX_FILE) { "Choose a PCM16 WAV up to 10 MiB." }
        RandomAccessFile(file, "r").use { r ->
            fun text(n: Int) = ByteArray(n).also(r::readFully).toString(Charsets.US_ASCII)
            fun u16() = r.readUnsignedByte() or (r.readUnsignedByte() shl 8)
            fun u32(): Long = (0..3).fold(0L) { a, i -> a or (r.readUnsignedByte().toLong() shl (i*8)) }
            require(text(4) == "RIFF" && u32() == file.length()-8 && text(4) == "WAVE") { "Not a complete WAV file." }
            require(text(4) == "fmt " && u32() == 16L && u16() == 1) { "Only plain PCM WAV is supported; no compressed/video/playlist formats." }
            val channels = u16(); val rate = u32(); val bytesPerSecond = u32(); val align = u16(); val bits = u16()
            require(channels in 1..2 && rate in 8000..48000 && bits == 16 && align == channels*2 && bytesPerSecond == rate*align) { "Use mono/stereo PCM16, 8–48 kHz." }
            require(text(4) == "data") { "WAV metadata/extra tracks are not supported in this bounded importer." }
            val bytes = u32()
            require(bytes > 0 && bytes % align == 0L && bytes == file.length()-44) { "Truncated or extra WAV payload." }
            val durationMs = bytes * 1000 / bytesPerSecond
            require(durationMs >= 1 && bytes <= bytesPerSecond * 300) { "Sound must be at most five minutes." }
            require(clock() <= deadline) { "Audio probe timed out." }
            return durationMs
        }
    }
}

/** All concurrent arrivals share the FIRST session's monotonic deadline, never a rolling cap. */
internal class RingSession(val token: String, val deadline: Long) {
    val members = linkedMapOf<String, LiveAlert>()
    fun add(alert: LiveAlert, now: Long): Boolean {
        if (now >= deadline || members.size >= 8 || members.values.any { it.token == alert.token }) return false
        members[alert.id] = alert
        return true
    }
    fun accepts(id: String, occurrenceToken: String, sessionToken: String, now: Long) =
        sessionToken == token && now < deadline && members[id]?.token == occurrenceToken
}
