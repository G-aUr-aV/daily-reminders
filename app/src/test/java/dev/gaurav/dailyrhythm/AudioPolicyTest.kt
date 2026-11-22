package dev.gaurav.dailyrhythm

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.*

class AudioPolicyTest {
    private fun wav(seconds:Int=1, rate:Int=8000, channels:Int=1):ByteArray {
        val count=seconds*rate*channels*2
        return ByteBuffer.allocate(44+count).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+count);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(channels.toShort());putInt(rate);putInt(rate*channels*2);putShort((channels*2).toShort());putShort(16);put("data".toByteArray());putInt(count)
        }.array()
    }
    private fun probe(bytes:ByteArray, clock:()->Long={0L}):Long {
        val f=File.createTempFile("sound", ".wav")
        return try {f.writeBytes(bytes);AudioPolicy.wav(f,clock)} finally {f.delete()}
    }
    @Test fun actualPcmAndFiveMinuteBoundaryAccepted() {assertEquals(1000,probe(wav()));assertEquals(300_000,probe(wav(300)))}
    @Test fun overDurationRejected() {assertThrows(IllegalArgumentException::class.java){probe(wav(301))}}
    @Test fun videoPlaylistAndExtensionSpoofRejected() {for(x in listOf("#EXTM3U\nhttps://example.invalid/sound", "video/mp4", "not a WAV"))assertThrows(IllegalArgumentException::class.java){probe(x.toByteArray())}}
    @Test fun metadataOrExternalReferenceChunkRejected() {val x=wav();"LIST".toByteArray().copyInto(x,36);assertThrows(IllegalArgumentException::class.java){probe(x)}}
    @Test fun truncatedAndTrailingBytesRejected() {val x=wav();for(b in listOf(x.copyOf(x.size-1),x+byteArrayOf(0)))assertThrows(IllegalArgumentException::class.java){probe(b)}}
    @Test fun unsupportedRateChannelsAndCompressedRejected() {for(i in listOf(20,22,24,32,34)){val x=wav();x[i]=7;assertThrows(IllegalArgumentException::class.java){probe(x)}}}
    @Test fun zeroDurationRejected() {assertThrows(IllegalArgumentException::class.java){probe(wav(0))}}
    @Test fun oversizedAndProbeDeadlineRejected() {assertThrows(IllegalArgumentException::class.java){probe(ByteArray(AudioPolicy.MAX_FILE.toInt()+1))};var t=0L;assertThrows(IllegalArgumentException::class.java){probe(wav()) {t+=2001;t}}}
    @Test fun countTotalAndStorageBudgetsFailClosed() {
        AudioPolicy.budget(0,0,AudioPolicy.FREE_FLOOR+AudioPolicy.MAX_FILE)
        for(action in listOf<()->Unit>({AudioPolicy.budget(16,0,Long.MAX_VALUE)},{AudioPolicy.budget(1,AudioPolicy.MAX_TOTAL,Long.MAX_VALUE)},{AudioPolicy.budget(0,0,AudioPolicy.FREE_FLOOR)},{AudioPolicy.budget(0,0,Long.MAX_VALUE,AudioPolicy.MAX_FILE+1)}))assertThrows(IllegalArgumentException::class.java){action()}
    }
    @Test fun privateIdsAreOpaqueNoTraversal() {assertTrue(AudioPolicy.opaque(java.util.UUID.randomUUID().toString()));for(id in listOf("../prefs","x.wav","https://example.invalid","","0000"))assertFalse(AudioPolicy.opaque(id))}
    private fun alert(id:String="pack")=LiveAlert("$id@2026-11-09#base",LocalDate.of(2026,11,9),Instant.EPOCH,id,"sig",true)
    @Test fun concurrentAlertsCannotExtendDeadlineAndHaveBoundedQueue() {val s=RingSession("session",300_000);repeat(8){assertTrue(s.add(alert("$it"),it*1000L))};assertEquals(300_000,s.deadline);assertFalse(s.add(alert("full"),200_000));assertFalse(s.add(alert("expired"),300_000))}
    @Test fun actionsRequireBothTokensAndUnexpiredSession() {val s=RingSession("session",300_000);val a=alert();s.add(a,0);assertTrue(s.accepts(a.id,a.token,"session",2));assertFalse(s.accepts(a.id,"stale","session",2));assertFalse(s.accepts(a.id,a.token,"old-session",2));assertFalse(s.accepts(a.id,a.token,"session",300_000));s.members.remove(a.id);assertFalse(s.accepts(a.id,a.token,"session",3))}
}
