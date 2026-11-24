"""Negative regression tests for final-APK policy validation. No network or APK upload."""
import copy
import unittest
import xml.etree.ElementTree as ET
from verify_apk import A, PACKAGE, SELF_PERMISSION, PERMISSIONS, DOMAINS, RESTORE_ACTIONS, validate_manifest, validate_resources


def manifest():
    root = ET.Element('manifest', {'package':PACKAGE, A+'versionCode':'20', A+'versionName':'0.7.1'})
    ET.SubElement(root,'uses-sdk',{A+'minSdkVersion':'26',A+'targetSdkVersion':'35'})
    for permission in sorted(PERMISSIONS):
        ET.SubElement(root,'uses-permission',{A+'name':permission})
    ET.SubElement(root,'permission',{A+'name':SELF_PERMISSION,A+'protectionLevel':'0x2'})
    app = ET.SubElement(root,'application',{A+'allowBackup':'false',A+'usesCleartextTraffic':'false',A+'appComponentFactory':'androidx.core.app.CoreComponentFactory'})
    launcher = ET.SubElement(app,'activity',{A+'name':PACKAGE+'.MainActivity',A+'exported':'true'})
    filt = ET.SubElement(launcher,'intent-filter')
    ET.SubElement(filt,'action',{A+'name':'android.intent.action.MAIN'})
    ET.SubElement(filt,'category',{A+'name':'android.intent.category.LAUNCHER'})
    ET.SubElement(app,'activity',{A+'name':PACKAGE+'.AlarmActivity',A+'exported':'false',
                                  A+'excludeFromRecents':'true',A+'taskAffinity':PACKAGE+'.alarm',A+'theme':'@ref/0x7f010002'})
    ET.SubElement(app,'receiver',{A+'name':PACKAGE+'.ReminderReceiver',A+'exported':'false'})
    restore=ET.SubElement(app,'receiver',{A+'name':PACKAGE+'.RestoreReceiver',A+'exported':'false'})
    filt=ET.SubElement(restore,'intent-filter')
    for action in sorted(RESTORE_ACTIONS): ET.SubElement(filt,'action',{A+'name':action})
    widget = ET.SubElement(app,'receiver',{A+'name':PACKAGE+'.DailyRemindersWidget',A+'exported':'false'})
    ET.SubElement(ET.SubElement(widget,'intent-filter'),'action',{A+'name':'android.appwidget.action.APPWIDGET_UPDATE'})
    ET.SubElement(widget,'meta-data',{A+'name':'android.appwidget.provider',A+'resource':'@ref/0x7f010003'})
    ET.SubElement(app,'service',{A+'name':PACKAGE+'.RingingService',A+'exported':'false',A+'foregroundServiceType':'mediaPlayback',A+'stopWithTask':'true'})
    ET.SubElement(app,'service',{A+'name':PACKAGE+'.CalendarSyncJob',A+'exported':'false',
                                 A+'permission':'android.permission.BIND_JOB_SERVICE'})
    return root


def resources():
    legacy=ET.Element('full-backup-content')
    modern=ET.Element('data-extraction-rules')
    cloud=ET.SubElement(modern,'cloud-backup',{'disableIfNoEncryptionCapabilities':'true'})
    transfer=ET.SubElement(modern,'device-transfer')
    for branch in [legacy,cloud,transfer]:
        for domain in sorted(DOMAINS): ET.SubElement(branch,'exclude',{'domain':domain,'path':'.'})
    network=ET.fromstring('<network-security-config><base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system" /></trust-anchors></base-config></network-security-config>')
    return legacy,modern,network


class ApkPolicyTest(unittest.TestCase):
    def test_minimal_final_manifest_and_resources_pass(self):
        validate_manifest(manifest()); validate_resources(*resources())
        # apkanalyzer renders the compiled mediaPlayback enum as 0x2.
        for value in ['mediaPlayback', '0x2', '2']:
            root=manifest(); root.find('application/service').set(A+'foregroundServiceType',value)
            validate_manifest(root)
        for value in ['0x1', '0x3', '3', 'mediaPlayback|dataSync', 'microphone']:
            root=manifest(); root.find('application/service').set(A+'foregroundServiceType',value)
            with self.assertRaises(ValueError): validate_manifest(root)

    def test_network_sensor_file_and_unknown_dependency_permissions_fail_closed(self):
        injected=['INTERNET','ACCESS_NETWORK_STATE','ACCESS_WIFI_STATE','CHANGE_WIFI_STATE','BLUETOOTH','BLUETOOTH_CONNECT','BLUETOOTH_SCAN','NEARBY_WIFI_DEVICES','NFC','ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION','ACCESS_BACKGROUND_LOCATION','READ_CONTACTS','WRITE_CONTACTS','CAMERA','RECORD_AUDIO','READ_EXTERNAL_STORAGE','WRITE_EXTERNAL_STORAGE','MANAGE_EXTERNAL_STORAGE','READ_MEDIA_IMAGES','READ_MEDIA_VIDEO','READ_MEDIA_AUDIO','READ_PHONE_STATE','SEND_SMS','FOREGROUND_SERVICE','FOREGROUND_SERVICE_MEDIA_PLAYBACK','WAKE_LOCK','VIBRATE','A_FUTURE_DEPENDENCY_PERMISSION']
        for name in injected:
            with self.subTest(permission=name):
                root=manifest(); ET.SubElement(root,'uses-permission',{A+'name':'android.permission.'+name})
                with self.assertRaises(ValueError): validate_manifest(root)

    def test_sdk23_permission_tag_cannot_bypass_allowlist(self):
        root=manifest(); ET.SubElement(root,'uses-permission-sdk-23',{A+'name':'android.permission.INTERNET'})
        with self.assertRaises(ValueError): validate_manifest(root)

    def test_missing_required_permission_and_permission_sdk_caps_fail(self):
        root=manifest(); root.remove(root.find('uses-permission'))
        with self.assertRaises(ValueError): validate_manifest(root)
        root=manifest(); root.find('uses-permission').set(A+'maxSdkVersion','25')
        with self.assertRaises(ValueError): validate_manifest(root)

    def test_normal_self_permission_rejected(self):
        root=manifest(); root.find('permission').set(A+'protectionLevel','normal')
        with self.assertRaises(ValueError): validate_manifest(root)

    def test_debug_test_profileable_flags_rejected(self):
        for flag in ['debuggable','testOnly','persistent']:
            with self.subTest(flag=flag):
                root=manifest(); root.find('application').set(A+flag,'true')
                with self.assertRaises(ValueError): validate_manifest(root)
        root=manifest(); ET.SubElement(root.find('application'),'profileable',{A+'shell':'true'})
        with self.assertRaises(ValueError): validate_manifest(root)

    def test_provider_service_preview_activity_and_unknown_receiver_rejected(self):
        for tag,name in [('provider','androidx.startup.InitializationProvider'),('service','InjectedService'),('activity','androidx.compose.ui.tooling.PreviewActivity'),('receiver','androidx.profileinstaller.ProfileInstallReceiver'),('activity-alias','InjectedAlias')]:
            with self.subTest(component=name):
                root=manifest(); ET.SubElement(root.find('application'),tag,{A+'name':name,A+'exported':'true'})
                with self.assertRaises(ValueError): validate_manifest(root)

    def test_exported_receiver_or_extra_broadcast_action_rejected(self):
        root=manifest(); root.find('application/receiver').set(A+'exported','true')
        with self.assertRaises(ValueError): validate_manifest(root)
        root=manifest(); receiver=root.findall('application/receiver')[1]
        ET.SubElement(receiver.find('intent-filter'),'action',{A+'name':'external.upload.ACTION'})
        with self.assertRaises(ValueError): validate_manifest(root)

    def test_alarm_activity_and_calendar_job_are_private(self):
        root = manifest()
        root.findall('application/activity')[1].set(A+'exported', 'true')
        with self.assertRaises(ValueError): validate_manifest(root)
        root = manifest()
        root.findall('application/service')[1].attrib.pop(A+'permission')
        with self.assertRaises(ValueError): validate_manifest(root)

    def test_deep_links_in_launcher_rejected(self):
        root=manifest(); ET.SubElement(root.find('application/activity/intent-filter'),'data',{A+'scheme':'https'})
        with self.assertRaises(ValueError): validate_manifest(root)

    def test_backup_enabled_or_cleartext_enabled_rejected(self):
        for attr in ['allowBackup','usesCleartextTraffic']:
            root=manifest(); root.find('application').set(A+attr,'true')
            with self.assertRaises(ValueError): validate_manifest(root)

    def test_custom_backup_management_and_unrestricted_restore_rejected(self):
        for name,value in [('backupAgent','InjectedBackupAgent'),('manageSpaceActivity','InjectedActivity'),('restoreAnyVersion','true')]:
            root=manifest(); root.find('application').set(A+name,value)
            with self.assertRaises(ValueError): validate_manifest(root)

    def test_missing_cloud_transfer_and_legacy_domain_exclusions_rejected(self):
        for branch_index in range(3):
            legacy,modern,network=resources()
            branch=[legacy,modern.find('cloud-backup'),modern.find('device-transfer')][branch_index]
            branch.remove(branch[0])
            with self.assertRaises(ValueError): validate_resources(legacy,modern,network)

    def test_backup_include_and_nonroot_path_rejected(self):
        legacy,modern,network=resources(); ET.SubElement(modern.find('device-transfer'),'include',{'domain':'sharedpref','path':'.'})
        with self.assertRaises(ValueError): validate_resources(legacy,modern,network)
        legacy,modern,network=resources(); legacy[0].set('path','partial')
        with self.assertRaises(ValueError): validate_resources(legacy,modern,network)

    def test_custom_ca_or_cleartext_domain_override_rejected(self):
        legacy,modern,network=resources(); network.find('base-config/trust-anchors/certificates').set('src','user')
        with self.assertRaises(ValueError): validate_resources(legacy,modern,network)
        legacy,modern,network=resources(); ET.SubElement(network,'domain-config',{'cleartextTrafficPermitted':'true'})
        with self.assertRaises(ValueError): validate_resources(legacy,modern,network)


class RingingPolicyTest(unittest.TestCase):
    def test_service_boundary_and_required_permissions(self):
        for field,value in [('exported','true'),('foregroundServiceType','mediaPlayback|dataSync'),('stopWithTask','false'),('permission','android.permission.INTERNET')]:
            root=manifest(); root.find('application/service').set(A+field,value)
            with self.assertRaises(ValueError): validate_manifest(root)
        root=manifest(); ET.SubElement(root.find('application/service'),'intent-filter')
        with self.assertRaises(ValueError): validate_manifest(root)
        root=manifest(); root.find('application').remove(root.find('application/service'))
        with self.assertRaises(ValueError): validate_manifest(root)


if __name__=='__main__': unittest.main(verbosity=2)
