package dev.gaurav.dailyrhythm

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SoundControls(config: GentleConfig, bridge: LiveReminders, onChange: (GentleConfig) -> Unit,
                           onImporting: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(config)
    val handler = remember { Handler(Looper.getMainLooper()) }
    var library by remember { mutableStateOf<List<LibrarySound>>(emptyList()) }
    var preview by remember { mutableStateOf<LocalAudio?>(null) }
    var pin by remember { mutableStateOf<java.io.Closeable?>(null) }
    var importing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var showSaved by rememberSaveable { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }

    val busy = importing || removing

    fun stopPreview() {
        handler.removeCallbacksAndMessages(null)
        preview?.close()
        preview = null
        pin?.close()
        pin = null
    }
    fun problem(message: String) {
        runCatching { bridge.audioHealth("device-tones", "DEVICE_TONE_HEALTH", message) }
        notice = message
    }
    fun choose(key: String, title: String) {
        stopPreview()
        onChange(current.copy(sound = key, toneTitle = title))
        notice = null
    }
    fun importSelection(uri: Uri, fromTonePicker: Boolean) {
        if (busy) return
        stopPreview()
        notice = null
        importing = true
        onImporting(true)
        scope.launch {
            try {
                val (id, entries) = withContext(Dispatchers.IO) {
                    val selected = PrivateSounds(context).import(uri, fromTonePicker)
                    selected to SoundLibrary(context).entries()
                }
                library = entries
                choose("private:$id", entries.firstOrNull { it.id == id }?.name ?: "Imported sound")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                problem(if (error is SoundAccessException) {
                    if (fromTonePicker) "Android couldn’t share this tone. For a downloaded song, use Import audio file."
                    else "Android couldn’t read this file. Try selecting it from Downloads or Music."
                } else error.message ?: "Couldn’t open this sound.")
            } finally {
                importing = false
                onImporting(false)
            }
        }
    }

    LaunchedEffect(Unit) {
        library = withContext(Dispatchers.IO) { runCatching { SoundLibrary(context).entries() }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(config) { stopPreview() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val listener = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) stopPreview() }
        lifecycle.addObserver(listener)
        onDispose {
            lifecycle.removeObserver(listener)
            stopPreview()
            onImporting(false)
        }
    }
    val tones = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = selectedAlarmUri(result.data)
            if (uri == null) {
                problem("The phone picker returned no sound. For a downloaded song, use Import audio file.")
            } else importSelection(uri, fromTonePicker = true)
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = selectedAudioFileUri(result.data)
            if (uri == null) problem("The file picker returned no audio file. Choose one song.")
            else importSelection(uri, fromTonePicker = false)
        }
    }

    fun browseTones() {
        stopPreview()
        val intent = systemActivity(context, Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false))
        if (intent == null) {
            problem("The phone’s tone picker is unavailable. You can import an audio file.")
        } else runCatching { tones.launch(intent) }.onFailure {
            problem("The phone’s tone picker couldn’t open. You can import an audio file.")
        }
    }

    val selectedTitle = BuiltInAlarm.find(config.sound)?.title ?: config.toneTitle.ifBlank { "Phone tone" }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Selected sound", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(selectedTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    SoundSection("System tones", showSystem) { showSystem = !showSystem }
    if (showSystem) {
        BuiltInAlarm.entries.forEach { sound ->
            SoundOption(sound.title, config.sound == sound.key, enabled = !busy) { choose(sound.key, "") }
        }
        LabeledRow("Phone tones", onClick = if (busy) null else ::browseTones)
    }

    if (library.isNotEmpty()) {
        SoundSection("Saved sounds", showSaved, count = library.size) { showSaved = !showSaved }
        if (showSaved) library.forEach { sound ->
            val key = "private:${sound.id}"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SoundOption(sound.name, config.sound == key, enabled = !busy, modifier = Modifier.weight(1f)) {
                    choose(key, sound.name)
                }
                IconButton(enabled = !busy, onClick = { removeId = sound.id }, modifier = Modifier.semantics {
                    contentDescription = "Delete ${sound.name}"
                }) { ReminderIcon(ReminderGlyph.DELETE) }
            }
        }
    }
    val target = library.firstOrNull { it.id == removeId }
    if (target != null) AlertDialog(
        onDismissRequest = { removeId = null },
        title = { Text("Delete saved sound?") },
        text = { Text(target.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        dismissButton = { TextButton(onClick = { removeId = null }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = {
            removeId = null
            stopPreview()
            notice = null
            removing = true
            val selected = current.sound
            scope.launch {
                try {
                    library = withContext(Dispatchers.IO) {
                        val sounds = SoundLibrary(context)
                        sounds.remove(target.id, extraSounds = setOf(selected))
                        sounds.entries()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    problem(error.message ?: "Couldn’t delete this sound.")
                } finally {
                    removing = false
                }
            }
        }) { Text("Delete") } }
    )

    fun browseAudio() {
        stopPreview()
        notice = null
        val request = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*").putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        runCatching {
            try {
                files.launch(request)
            } catch (_: ActivityNotFoundException) {
                files.launch(Intent(request).setAction(Intent.ACTION_GET_CONTENT))
            }
        }.onFailure { problem("Android couldn’t open a file picker.") }
    }
    LabeledRow("Import audio file", if (importing) "Importing…" else null, onClick = if (busy) null else ::browseAudio)

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Vibrate", Modifier.weight(1f))
        Switch(config.vibrate, onCheckedChange = { onChange(current.copy(vibrate = it)) },
            modifier = Modifier.semantics { contentDescription = "Vibrate" })
    }
    TextButton(enabled = !busy, onClick = {
        if (preview != null) stopPreview()
        else try {
            pin = PrivateSounds.pin(config.sound)
            preview = LocalAudio(context, ::problem, ::stopPreview)
            preview?.play(config.sound,false)
            handler.postDelayed({ stopPreview() }, 30_000)
        } catch (_: Exception) {
            stopPreview()
            problem("Can’t play this sound.")
        }
    }) { Text(if (preview == null) "Preview" else "Stop preview") }
}

internal fun selectedAlarmUri(intent: Intent?): Uri? {
    if (intent == null) return null
    @Suppress("DEPRECATION")
    val tone = runCatching { intent.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) }.getOrNull()
    val clip = intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
    return listOfNotNull(tone, intent.data, clip).firstOrNull { it.scheme in setOf("content", "android.resource") }
}

internal fun selectedAudioFileUri(intent: Intent?): Uri? {
    val clip = intent?.clipData
    if (clip != null && clip.itemCount != 1) return null
    return (intent?.data ?: clip?.getItemAt(0)?.uri)?.takeIf { it.scheme == "content" }
}

@Composable
private fun SoundOption(
    title: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        RadioButton(selected, onClick = null)
    }
}


@Composable
private fun SoundSection(title: String, expanded: Boolean, count: Int? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        count?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Box(Modifier.rotate(if (expanded) 180f else 0f)) { ReminderIcon(ReminderGlyph.EXPAND) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}
