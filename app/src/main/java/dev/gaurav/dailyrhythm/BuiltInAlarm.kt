package dev.gaurav.dailyrhythm

import androidx.annotation.RawRes

internal enum class BuiltInAlarm(val key: String, val title: String, @RawRes val resource: Int) {
    SOFT_PULSE("default", "Soft pulse", R.raw.alarm_fallback),
    CHIME("builtin:chime", "Chime", R.raw.alarm_chime),
    MORNING("builtin:morning", "Morning bells", R.raw.alarm_morning),
    DIGITAL("builtin:digital", "Digital alarm", R.raw.alarm_digital);

    companion object {
        fun find(key: String) = entries.firstOrNull { it.key == key }
    }
}
