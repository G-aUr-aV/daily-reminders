package dev.gaurav.dailyrhythm

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Handler
import android.os.Looper

internal interface AudioOutput { fun play(sound:String, loop:Boolean); fun close() }

/** One owner, no deferred-focus playback. Call only from main thread while foreground/top activity. */
internal class LocalAudio(private val context: Context, private val problem: (String) -> Unit, private val ended: () -> Unit, private val canPlay: () -> Boolean = {true}) : AudioOutput {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var granted = false
    private var closed = false
    private var finished = false
    private val handler=Handler(Looper.getMainLooper())
    private var rampStarted = 0L
    private val rampVolume = object : Runnable {
        override fun run() {
            val playing = player ?: return
            if (closed) return
            val elapsed = android.os.SystemClock.elapsedRealtime() - rampStarted
            val volume = (.1f + .9f * (elapsed / 15_000f)).coerceIn(.1f, 1f)
            try {
                playing.setVolume(volume, volume)
                if (volume < 1f) handler.postDelayed(this, 150)
            } catch (error: Exception) { fail("Audio volume update failed: ${error.message}") }
        }
    }
    private val preparationTimeout=Runnable {fail("Audio preparation timed out; no delayed playback.")}
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
        .setAcceptsDelayedFocusGain(false).setOnAudioFocusChangeListener({ change ->
            if(!closed && change != AudioManager.AUDIOFOCUS_GAIN) { close(); notice("Audio focus lost; stopped without delayed restart."); finish() }
        }, Handler(Looper.getMainLooper())).build()
    override fun play(sound: String, loop: Boolean) {
        check(!closed)
        granted = try { manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED } catch(e:Exception) {fail("Audio focus request failed: ${e.message}");return}
        if(!granted) { close(); notice("Audio focus not granted; no delayed playback queued."); finish(); return }
        fun setBuiltIn(p: MediaPlayer, alarm: BuiltInAlarm = BuiltInAlarm.SOFT_PULSE) {
            context.resources.openRawResourceFd(alarm.resource).use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        }
        try {
            val p = MediaPlayer(); player = p
            p.setAudioAttributes(attrs)
            try {
                val builtin = BuiltInAlarm.find(sound)
                when {
                    builtin != null -> setBuiltIn(p, builtin)
                    sound.startsWith("private:") -> {
                        val f = PrivateSounds(context).file(sound.removePrefix("private:"))
                        if (f.extension == "wav") AudioPolicy.wav(f)
                        java.io.FileInputStream(f).use { p.setDataSource(it.fd) }
                    }
                    sound.startsWith("tone:") -> {
                        val uri = Uri.parse(sound.removePrefix("tone:")); require(PrivateSounds.deviceTone(context, uri)) { "Device tone identity is not trusted." }
                        p.setDataSource(context, uri)
                    }
                    else -> error("Unknown sound selection.")
                }
            } catch(e: Exception) {
                check(notice("Selected sound unreadable. Using bundled fallback: ${e.message}")) {"Could not record fallback health."}
                p.reset(); p.setAudioAttributes(attrs); setBuiltIn(p)
            }
            p.isLooping = loop
            p.setOnPreparedListener { ready ->
                try {
                    handler.removeCallbacks(preparationTimeout)
                    if(!closed && granted && player === ready) {
                        if(canPlay()) {
                            // Authorization may itself close/invalidate this owner. Never start it afterward.
                            if(!closed && granted && player === ready) {
                                if (loop) ready.setVolume(.1f, .1f)
                                ready.start()
                                if (loop) {
                                    rampStarted = android.os.SystemClock.elapsedRealtime()
                                    handler.postDelayed(rampVolume, 150)
                                }
                            }
                        } else {close();finish()}
                    }
                } catch(e:Exception) {fail("Audio preparation/start failed: ${e.message}")}
            }
            p.setOnErrorListener { _,_,_ -> fail("Audio decode/playback failed; stopped."); true }
            p.setOnCompletionListener { if(!closed) {close(); finish()} }
            handler.postDelayed(preparationTimeout,5000)
            p.prepareAsync()
        } catch(e: Exception) { fail("Audio unavailable: ${e.message}") }
    }
    private fun notice(message:String)=runCatching {problem(message)}.isSuccess
    private fun finish() {if(finished)return;finished=true;runCatching {ended()}}
    private fun fail(message: String) { if(closed)return;close(); notice(message); finish() }
    override fun close() {
        if(closed) return
        closed = true; granted = false
        runCatching {handler.removeCallbacks(preparationTimeout)}
        handler.removeCallbacks(rampVolume)
        player?.let { p ->
            player = null
            // A listener-detachment failure must not prevent release or focus abandonment.
            runCatching {p.setOnPreparedListener(null)}
            runCatching {p.setOnErrorListener(null)}
            runCatching {p.setOnCompletionListener(null)}
            runCatching {p.release()}
        }
        runCatching { manager.abandonAudioFocusRequest(focus) }
    }
}
