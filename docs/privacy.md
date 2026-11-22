# Privacy and permissions

Daily Reminders keeps reminders, completion history, settings, and imported sounds in Android app-private storage. The app has no Internet permission, built-in account, telemetry, or server. Android system backup is disabled and the app declares exclusions for its backup domains.

| Android access | Use |
| --- | --- |
| Notifications | Reminder alerts and controls for a ringing alarm |
| Alarms & reminders | Exact scheduling for alerts configured to use it |
| Full-screen notifications | Present a configured ringing alarm on the lock screen where Android allows it |
| Display over other apps | Optional alarm presentation while the phone is unlocked |
| Calendar read and write | Select a calendar, reconcile app-owned events, and publish planned times and outcomes when enabled |
| Boot broadcasts, foreground audio service, wake lock, vibration | Restore future schedules and deliver and control configured ringing alarms |

The Android file picker grants access to the selected sound, backup, or export file. Imported audio is copied to private app storage. Calendar integration writes to Android's Calendar Provider. If the selected calendar is attached to a synced account, its provider can upload the events to that account. Calendar integration is off on a fresh install.

## Exports and restore

**History → Export** creates an unencrypted iCalendar file at the location you choose. It can contain reminder titles, planned times, and outcomes. Treat it as personal data.

**Settings → Data & privacy** creates a password-protected `.drbackup` using AES-GCM. It can include routines, history, settings, and imported audio. Restoring replaces reminders and history and keeps existing sound files within the library limit. Keep the backup password securely and reconnect Calendar and Android permissions after restoring on a new device.

Uninstalling or clearing storage deletes the app's local data. Files you exported and events already written to a calendar can remain outside app storage.
