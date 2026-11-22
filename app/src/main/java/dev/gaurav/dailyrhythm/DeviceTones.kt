package dev.gaurav.dailyrhythm

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore

internal data class DeviceTone(val uri:Uri,val title:String)
internal data class ToneCatalog(val tones:List<DeviceTone>,val issue:String?)
internal interface AlarmToneSource {fun alarms():List<DeviceTone>;fun title(uri:Uri):String?}
/** Android provides the list and names. No packaged/fabricated phone-tone catalog. No playback here. */
internal class AndroidAlarmTones(private val context:Context):AlarmToneSource {
    override fun alarms():List<DeviceTone> {
        val manager=RingtoneManager(context);manager.setType(RingtoneManager.TYPE_ALARM)
        return manager.cursor.use {cursor->buildList {
            var position=0
            while(position<256 && cursor.moveToPosition(position)) {
                manager.getRingtoneUri(position)?.let {add(DeviceTone(it,cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX).orEmpty().take(512)))}
                position++
            }
        }}
    }
    override fun title(uri:Uri):String? {
        val resolved=if(uri.authority=="settings") {
            val type=when(uri.path){"/system/alarm_alert"->RingtoneManager.TYPE_ALARM;"/system/notification_sound"->RingtoneManager.TYPE_NOTIFICATION;"/system/ringtone"->RingtoneManager.TYPE_RINGTONE;else->return null}
            RingtoneManager.getActualDefaultRingtoneUri(context,type) ?: return null
        }else uri
        require(PrivateSounds.deviceTone(context,resolved)) {"Default tone does not resolve to a verified device provider."}
        return context.contentResolver.query(resolved,arrayOf(MediaStore.Audio.Media.TITLE),null,null,null)?.use {if(it.moveToFirst())it.getString(0)?.take(512)else null}
    }
}
internal class DeviceTones(private val context:Context,private val source:AlarmToneSource=AndroidAlarmTones(context)) {
    companion object {
        fun cleanTitle(raw:String?):String?=raw?.take(512)?.map {if(Character.isISOControl(it)||Character.getType(it)==Character.FORMAT.toInt())' 'else it}?.joinToString("")?.trim()?.take(128)?.takeIf {it.isNotBlank()}
        fun legacy(sound:String)=sound.startsWith("private:")
    }
    fun alarms():ToneCatalog = try {
        require(PrivateSounds.deviceTone(context,Uri.parse("content://media/internal/audio/media/0"))) {"Device media provider ownership could not be verified."}
        val rows=source.alarms()
        val accepted=rows.take(256).mapNotNull {row->cleanTitle(row.title)?.takeIf {PrivateSounds.deviceTone(context,row.uri)}?.let {DeviceTone(row.uri,it)}}.distinctBy {it.uri.toString()}
        ToneCatalog(accepted,when {rows.size>=256->"Showing up to 256 device entries; the trusted system picker is also available.";accepted.size!=rows.size->"Some entries had unavailable names or unverified URIs and were omitted.";accepted.isEmpty()->"No accessible device alarm tones returned. Try Android's tone picker or the disclosed fallback.";else->null})
    }catch(e:Exception){ToneCatalog(emptyList(),"Device tone list unavailable: ${e.message}. No storage/media permission was requested; try Android's tone picker.")}
    fun selected(uri:Uri):DeviceTone {
        require(PrivateSounds.deviceTone(context,uri)) {"Selected tone is not from a verified device provider."}
        val name=cleanTitle(source.title(uri)) ?: throw IllegalArgumentException("Selected tone title is unavailable; previous choice retained. Try the device list or system picker again.")
        return DeviceTone(uri,name)
    }
    fun currentTitle(uri:Uri):String? = runCatching {if(PrivateSounds.deviceTone(context,uri))cleanTitle(source.title(uri))else null}.getOrNull()
    fun savedLabel(config:GentleConfig):String=when {
        config.sound=="default"->"Bundled safety fallback — no phone tone chosen"
        legacy(config.sound)->SoundLibrary(context).label(config.sound)
        config.sound.startsWith("tone:")->currentTitle(Uri.parse(config.sound.removePrefix("tone:")))?.let {"$it · device tone"} ?: cleanTitle(config.toneTitle)?.let {"$it · saved title; current name/readability unavailable"} ?: "Device tone name unavailable; reselect in Android's picker"
        else->"Unknown saved choice; select a device tone explicitly"
    }
}
