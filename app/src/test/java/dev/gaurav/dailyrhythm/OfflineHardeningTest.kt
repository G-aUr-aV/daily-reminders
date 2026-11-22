package dev.gaurav.dailyrhythm

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Simulated Android behavior; the separate Python verifier checks the real packaged APK. */
@android.annotation.TargetApi(33)
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineHardeningTest {
    private lateinit var app: Application
    private val domains = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("rhythm_v1", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @Test fun finalMergedPermissionSetIsOnlyTheAllowlist() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertEquals(setOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECEIVE_BOOT_COMPLETED, Manifest.permission.SCHEDULE_EXACT_ALARM, Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK, Manifest.permission.WAKE_LOCK, Manifest.permission.VIBRATE, Manifest.permission.USE_FULL_SCREEN_INTENT, Manifest.permission.SYSTEM_ALERT_WINDOW, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR, app.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"), info.requestedPermissions.orEmpty().toSet())
        val declared = info.permissions.orEmpty().single()
        assertEquals(app.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", declared.name)
        assertEquals(android.content.pm.PermissionInfo.PROTECTION_SIGNATURE, declared.protectionLevel)
    }
    @Test fun addedAlarmActivityAndCalendarJobArePrivate() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES)
        val activities = info.activities.orEmpty().associateBy { it.name }
        assertEquals(setOf(MainActivity::class.java.name, AlarmActivity::class.java.name), activities.keys)
        assertTrue(activities.getValue(MainActivity::class.java.name).exported)
        assertFalse(activities.getValue(AlarmActivity::class.java.name).exported)
        assertTrue(info.providers.orEmpty().isEmpty())
        val services = info.services.orEmpty().associateBy { it.name }
        assertEquals(setOf(RingingService::class.java.name, CalendarSyncJob::class.java.name), services.keys)
        val job = services.getValue(CalendarSyncJob::class.java.name)
        assertFalse(job.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", job.permission)
        val service = services.getValue(RingingService::class.java.name)
        assertEquals(RingingService::class.java.name,service.name);assertFalse(service.exported)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,service.foregroundServiceType)
        assertEquals(setOf(ReminderReceiver::class.java.name, RestoreReceiver::class.java.name, DailyRemindersWidget::class.java.name), info.receivers.orEmpty().map { it.name }.toSet())
        assertTrue(info.receivers.orEmpty().all { !it.exported })
    }
    @Test fun backupAndCleartextFlagsAreDisabled() {
        val flags = app.applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(0, flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
    }
    private fun exclusions(resource: Int, branch: String?): Set<String> {
        val xml = app.resources.getXml(resource)
        val found = mutableSetOf<String>()
        var currentBranch: String? = null
        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG) {
                if (xml.name in setOf("cloud-backup","device-transfer")) currentBranch = xml.name
                if (xml.name == "exclude" && (branch == null || branch == currentBranch)) {
                    assertEquals(".", xml.getAttributeValue(null,"path"))
                    found += xml.getAttributeValue(null,"domain")
                }
                assertNotEquals("include", xml.name)
            }
            if (xml.eventType == XmlPullParser.END_TAG && xml.name == currentBranch) currentBranch = null
            xml.next()
        }
        xml.close(); return found
    }
    @Test fun legacyBackupExcludesEveryCredentialAndDeviceProtectedDomain() {
        assertEquals(domains, exclusions(R.xml.backup_rules, null))
    }
    @Test fun modernCloudBackupAndDeviceTransferBothExcludeEveryDomain() {
        assertEquals(domains, exclusions(R.xml.data_extraction_rules, "cloud-backup"))
        assertEquals(domains, exclusions(R.xml.data_extraction_rules, "device-transfer"))
    }
    @Test fun notificationsRequestSecretVisibilityAndHaveOnlyAnInternalLaunchIntent() {
        val scheduler = ReminderScheduler(app)
        app.rhythmStore().remindersEnabled = true
        scheduler.ensureChannel(); scheduler.post(ReminderScheduler.TEST)
        val manager = app.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
        assertEquals(Notification.VISIBILITY_SECRET, manager.getNotificationChannel(ReminderScheduler.CHANNEL).lockscreenVisibility)
        val launch = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, launch.component?.className)
        assertNull(launch.data); assertTrue(launch.extras == null || launch.extras!!.isEmpty)
        assertTrue(notification.contentIntent.isImmutable)
        assertFalse(notification.extras.toString().contains("rounds."))
    }
    @Test fun unknownRestoreBroadcastCannotRebuildAlarmsOrCancelPendingTest() {
        val scheduler = ReminderScheduler(app)
        app.rhythmStore().remindersEnabled = true; scheduler.sync(); scheduler.test()
        val before = Preferences(app).get("alarm_v2.pack")
        RestoreReceiver().onReceive(app, Intent("external.UPLOAD").putExtra("destination", "https://invalid.example"))
        assertEquals(before, Preferences(app).get("alarm_v2.pack"))
        assertEquals(5, shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.size)
    }
    @Test fun externalActionAndUnknownRoutineAreIgnoredByReminderReceiver() {
        val scheduler = ReminderScheduler(app)
        app.rhythmStore().remindersEnabled = true; scheduler.sync()
        val before = Preferences(app).get("alarm_v2.pack")
        ReminderReceiver().onReceive(app, Intent("external.SEND").putExtra("routine_id", "pack"))
        ReminderReceiver().onReceive(app, Intent(ReminderScheduler.ACTION).putExtra("routine_id", "unknown"))
        assertEquals(before, Preferences(app).get("alarm_v2.pack"))
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
    private fun settingsHandler(system: Boolean) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                name = "dev.fixture.NotificationSettings"
                packageName = "dev.fixture"
                exported = true
                applicationInfo = ApplicationInfo().apply { flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0; packageName = "dev.fixture" }
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS), info)
    }
    @Test fun notificationSettingsCannotDelegateToAnOrdinaryApp() {
        settingsHandler(false)
        assertNull(notificationSettingsIntent(app, true))
    }
    @Test fun systemNotificationSettingsIntentIsExplicitAndContainsNoRoutineData() {
        settingsHandler(true)
        val intent = requireNotNull(notificationSettingsIntent(app, true))
        assertEquals("dev.fixture.NotificationSettings", intent.component?.className)
        assertNull(intent.data)
        assertEquals(setOf(Settings.EXTRA_APP_PACKAGE, Settings.EXTRA_CHANNEL_ID), intent.extras!!.keySet())
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(ReminderScheduler.CHANNEL, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }
}
