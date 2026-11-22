package dev.gaurav.dailyrhythm

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BackupOperation { EXPORT, RESTORE }

@Composable
internal fun BackupSettings(onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var operation by remember { mutableStateOf<BackupOperation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var repeatPassphrase by remember { mutableStateOf("") }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        uri = it
        operation = it?.let { BackupOperation.EXPORT }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri = it
        operation = it?.let { BackupOperation.RESTORE }
    }
    fun closePrompt() {
        operation = null
        uri = null
        passphrase = ""
        repeatPassphrase = ""
    }

    EditorGroup("Data & privacy") {
        LabeledRow("Export encrypted backup", onClick = if (busy) null else ({
            saveDocument.launch("DailyReminders_Backup_${LocalDate.now()}.drbackup")
        }))
        LabeledRow("Restore from backup", onClick = if (busy) null else ({
            openDocument.launch(arrayOf("application/octet-stream", "application/gzip", "*/*"))
        }))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    val selected = operation
    val document = uri
    if (selected != null && document != null) {
        val exporting = selected == BackupOperation.EXPORT
        ReminderDialog(if (exporting) "Encrypt backup" else "Replace reminders from backup",
            onDismiss = ::closePrompt, footer = {
                TextButton(onClick = ::closePrompt) { Text("Cancel") }
                Button(enabled = passphrase.length >= 8 && (!exporting || passphrase == repeatPassphrase), onClick = {
                    val password = passphrase.toCharArray()
                    closePrompt()
                    busy = true
                    status = if (exporting) "Writing encrypted backup…" else "Checking backup and restoring…"
                    scope.launch {
                        try {
                            val alarmsReady = withContext(Dispatchers.IO) {
                                val resolver = context.contentResolver
                                if (exporting) {
                                    requireNotNull(resolver.openOutputStream(document, "wt")).use { stream ->
                                        LocalBackup.export(context, stream, password)
                                    }
                                    true
                                } else {
                                    requireNotNull(resolver.openInputStream(document)).use { stream ->
                                        LocalBackup.read(context, stream, password)
                                    }.use { staged -> withContext(Dispatchers.Main.immediate) { staged.restore(context) } }
                                }
                            }
                            if (!exporting) onRestored()
                            status = when {
                                exporting -> "Encrypted backup saved."
                                alarmsReady -> "Backup restored."
                                else -> "Backup restored. Reopen the app to refresh alarm scheduling."
                            }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            status = if (exporting) "Couldn’t write the backup. Try a different location."
                                else "Couldn’t restore. Check the password, backup file, and available storage."
                        } finally {
                            password.fill('\u0000')
                            busy = false
                        }
                    }
                }) { Text(if (exporting) "Save backup" else "Replace and restore") }
            }) {
            Text(if (exporting) "Use at least 8 characters. You’ll need this password to restore reminders, history and imported sounds."
                else "This replaces reminders and history on this device. Existing sound files are kept. Android permissions and Calendar connections stay with this device.")
            OutlinedTextField(passphrase, { passphrase = it.take(128) }, label = { Text("Backup password") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            if (exporting) OutlinedTextField(repeatPassphrase, { repeatPassphrase = it.take(128) },
                label = { Text("Confirm password") }, visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}
