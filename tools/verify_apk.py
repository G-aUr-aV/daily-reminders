#!/usr/bin/env python3
"""Offline, fail-closed policy assertions on the FINAL APK. No scanner/API/upload.
Requires Python 3, SDK cmdline-tools/latest, build-tools 35.0.0 and Java 17.
This is manifest/resource enforcement, not malware analysis or a safety proof.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

A = '{http://schemas.android.com/apk/res/android}'
PACKAGE = 'dev.gaurav.dailyrhythm'
SELF_PERMISSION = PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
PERMISSIONS = {'android.permission.POST_NOTIFICATIONS', 'android.permission.RECEIVE_BOOT_COMPLETED', 'android.permission.SCHEDULE_EXACT_ALARM', 'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK', 'android.permission.WAKE_LOCK', 'android.permission.VIBRATE', 'android.permission.USE_FULL_SCREEN_INTENT', 'android.permission.READ_CALENDAR', 'android.permission.WRITE_CALENDAR', SELF_PERMISSION}
DOMAINS = {'root', 'file', 'database', 'sharedpref', 'external', 'device_root', 'device_file', 'device_database', 'device_sharedpref'}
RESTORE_ACTIONS = {'android.intent.action.BOOT_COMPLETED', 'android.intent.action.TIME_SET', 'android.intent.action.TIMEZONE_CHANGED', 'android.intent.action.MY_PACKAGE_REPLACED', 'android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_manifest(root):
    require(root.tag == 'manifest' and root.get('package') == PACKAGE, 'Unexpected package/root')
    require(root.get(A+'versionCode') == '20' and root.get(A+'versionName') == '0.8.0', 'Unexpected candidate version')
    require(root.get(A+'sharedUserId') is None, 'Shared UID forbidden')
    require(all(n.tag in {'uses-sdk', 'uses-permission', 'permission', 'application'} for n in root), 'Unexpected manifest root capability/element')
    uses = root.findall('uses-permission')
    require(all(set(n.attrib) == {A+'name'} for n in uses), 'Permission SDK caps/flags or unknown attributes forbidden')
    actual = [n.get(A+'name') for n in uses]
    require(set(actual) == PERMISSIONS and len(actual) == len(PERMISSIONS), 'Permission allowlist violation: '+repr(actual))
    declarations = root.findall('permission')
    require(len(declarations) == 1 and declarations[0].get(A+'name') == SELF_PERMISSION, 'Unexpected declared permission')
    require(declarations[0].get(A+'protectionLevel') in {'signature', '0x2', '2'}, 'Self-only permission must be signature-level')
    sdk = root.find('uses-sdk')
    require(sdk is not None and sdk.get(A+'minSdkVersion') == '26' and sdk.get(A+'targetSdkVersion') == '36', 'Unexpected SDK versions')
    apps = root.findall('application')
    require(len(apps) == 1, 'Expected one application')
    app = apps[0]
    for flag in ['debuggable', 'testOnly', 'persistent']:
        require(app.get(A+flag, 'false') == 'false', flag+' must be false/absent')
    require(app.get(A+'allowBackup') == 'false', 'Backup must be explicitly disabled')
    require(app.get(A+'backupAgent') is None and app.get(A+'manageSpaceActivity') is None, 'Custom backup/management entry point forbidden')
    require(app.get(A+'restoreAnyVersion', 'false') == 'false', 'Unrestricted restore forbidden')
    require(app.get(A+'usesCleartextTraffic') == 'false', 'Cleartext must be explicitly disabled')
    require(app.find('profileable') is None, 'Profileable element forbidden')
    require(app.get(A+'appComponentFactory') == 'androidx.core.app.CoreComponentFactory', 'Unexpected app component factory')
    require(all(n.tag in {'activity', 'receiver', 'service'} for n in app), 'Provider/service/alias/initializer or unknown application element forbidden')
    activities = app.findall('activity')
    require(len(activities) == 2, 'Only the launcher and private alarm activity are allowed')
    activity = next((node for node in activities if node.get(A+'name') == PACKAGE+'.MainActivity'), None)
    alarm = next((node for node in activities if node.get(A+'name') == PACKAGE+'.AlarmActivity'), None)
    require(activity is not None and alarm is not None, 'Required activities missing')
    require(alarm.get(A+'exported') == 'false' and alarm.get(A+'excludeFromRecents') == 'true'
            and alarm.get(A+'taskAffinity') == PACKAGE+'.alarm' and not list(alarm)
            and set(alarm.attrib) == {A+'name', A+'exported', A+'theme', A+'excludeFromRecents', A+'taskAffinity'},
            'Alarm activity must be private and isolated from the launcher task')
    require(activity.get(A+'name') == PACKAGE+'.MainActivity' and activity.get(A+'exported') == 'true', 'Unexpected launcher')
    require(all(n.tag == 'intent-filter' for n in activity), 'Unexpected activity child')
    filters = activity.findall('intent-filter')
    require(len(filters) == 1 and len(filters[0]) == 2, 'Unexpected launcher intent filters')
    require({(n.tag, n.get(A+'name')) for n in filters[0]} == {('action','android.intent.action.MAIN'), ('category','android.intent.category.LAUNCHER')}, 'Launcher must only handle MAIN/LAUNCHER')
    receivers = app.findall('receiver')
    require(len(receivers) == 3, 'Exactly three internal receivers expected')
    names = {n.get(A+'name') for n in receivers}
    require(names == {PACKAGE+'.ReminderReceiver', PACKAGE+'.RestoreReceiver', PACKAGE+'.DailyRemindersWidget'}, 'Unexpected receiver')
    for receiver in receivers:
        require(receiver.get(A+'exported') == 'false', 'Receiver must be non-exported')
        require(receiver.get(A+'enabled', 'true') == 'true', 'Required receiver disabled')
        require(receiver.get(A+'directBootAware', 'false') == 'false', 'Use credential-protected storage after unlock')
        widget = receiver.get(A+'name') == PACKAGE+'.DailyRemindersWidget'
        metadata = receiver.findall('meta-data')
        require(all(n.tag == 'intent-filter' and all(c.tag == 'action' for c in n) or
                    widget and n.tag == 'meta-data' for n in receiver), 'Unexpected receiver metadata/routing')
        if widget:
            require(len(metadata) == 1 and metadata[0].get(A+'name') == 'android.appwidget.provider'
                    and bool(metadata[0].get(A+'resource')) and set(metadata[0].attrib) == {A+'name', A+'resource'},
                    'Widget must declare only its app-widget metadata')
        actions = {n.get(A+'name') for n in receiver.findall('intent-filter/action')}
        if widget:
            expected = {'android.appwidget.action.APPWIDGET_UPDATE'}
        else:
            expected = RESTORE_ACTIONS if receiver.get(A+'name').endswith('.RestoreReceiver') else set()
        require(actions == expected, 'Unexpected receiver actions')
        require(not receiver.findall('intent-filter/data') and not receiver.findall('intent-filter/category'), 'Receiver data/category routing forbidden')
    services = app.findall('service')
    require(len(services) == 2, 'Private Ringing and calendar-job services required')
    service = next((node for node in services if node.get(A+'name') == PACKAGE+'.RingingService'), None)
    calendar = next((node for node in services if node.get(A+'name') == PACKAGE+'.CalendarSyncJob'), None)
    require(service is not None and calendar is not None, 'Required services missing')
    require(calendar.get(A+'exported') == 'false' and calendar.get(A+'permission') == 'android.permission.BIND_JOB_SERVICE'
            and set(calendar.attrib) == {A+'name', A+'exported', A+'permission'} and not list(calendar),
            'Calendar job must be private and bound by Android JobScheduler')
    require(set(service.attrib) == {A+'name', A+'exported', A+'foregroundServiceType', A+'stopWithTask'} and not list(service), 'Unexpected service attributes/routing')
    require(service.get(A+'name') == PACKAGE+'.RingingService' and service.get(A+'exported') == 'false' and service.get(A+'foregroundServiceType') in {'mediaPlayback', '0x2', '2'} and service.get(A+'stopWithTask') == 'true', 'Private mediaPlayback only')
    return app, {'permissions':sorted(actual), 'declaredSelfPermissionProtection':'signature', 'activities':[{'name':activity.get(A+'name'),'exported':True,'actions':['android.intent.action.MAIN'],'categories':['android.intent.category.LAUNCHER']},{'name':PACKAGE+'.AlarmActivity','exported':False}], 'receivers':[{'name':n.get(A+'name'),'exported':False,'actions':sorted(a.get(A+'name') for a in n.findall('intent-filter/action'))} for n in receivers], 'providers':[], 'services':[{'name':PACKAGE+'.RingingService','exported':False,'foregroundServiceType':'mediaPlayback','stopWithTask':True},{'name':PACKAGE+'.CalendarSyncJob','exported':False,'permission':'android.permission.BIND_JOB_SERVICE'}], 'debuggable':False, 'testOnly':False, 'profileable':False, 'allowBackup':False, 'usesCleartextTraffic':False}


def validate_exclusions(branch):
    require(all(n.tag == 'exclude' for n in branch), 'Backup includes/unknown rules forbidden')
    pairs = [(n.get('domain'), n.get('path')) for n in branch]
    require(len(pairs) == len(DOMAINS) and set(pairs) == {(d,'.') for d in DOMAINS}, 'All data domains must be excluded entirely')


def validate_resources(legacy, modern, network):
    require(legacy.tag == 'full-backup-content', 'Incorrect legacy backup root')
    validate_exclusions(legacy)
    require(modern.tag == 'data-extraction-rules' and len(modern) == 2, 'Incorrect modern backup root')
    cloud, transfer = modern.find('cloud-backup'), modern.find('device-transfer')
    require(cloud is not None and transfer is not None, 'Cloud/transfer branches required')
    require(cloud.get('disableIfNoEncryptionCapabilities') == 'true', 'Require encrypted-cloud capability as additional defense')
    validate_exclusions(cloud)
    validate_exclusions(transfer)
    require(network.tag == 'network-security-config' and len(network) == 1, 'Network overrides/configs forbidden')
    base = network.find('base-config')
    require(base is not None and base.get('cleartextTrafficPermitted') == 'false' and len(base) == 1, 'Cleartext must be disabled')
    anchors = base.find('trust-anchors')
    require(anchors is not None and len(anchors) == 1, 'Unexpected trust configuration')
    cert = anchors.find('certificates')
    require(cert is not None and cert.get('src') == 'system', 'User/custom trust anchors forbidden')


def run(args, output=None):
    result = subprocess.run([str(x) for x in args], capture_output=True, text=True, check=True)
    if output is not None:
        output.write_text(result.stdout + result.stderr)
    return result.stdout


def verify(apk, sdk, out):
    out.mkdir(parents=True, exist_ok=True)
    analyzer = sdk/'cmdline-tools/latest/bin/apkanalyzer'
    tools = sdk/'build-tools/35.0.0'
    xml = run([analyzer,'manifest','print',apk], out/'manifest.xml')
    root = ET.fromstring(xml)
    app, inventory = validate_manifest(root)
    resources = run([tools/'aapt2','dump','resources',apk], out/'resource-table.txt')
    decoded = {}
    for name, attribute in [('backup_rules','fullBackupContent'), ('data_extraction_rules','dataExtractionRules'), ('network_security_config','networkSecurityConfig')]:
        match = re.search(r'resource (0x[0-9a-f]+) xml/'+name+r'\s+\(\) \(file\) (\S+) type=XML', resources)
        require(match is not None, 'Missing/default XML resource: '+name)
        resource_id, path = match.groups()
        require(app.get(A+attribute) in {'@ref/'+resource_id, '@'+resource_id, '@xml/'+name}, 'Application must reference inspected '+name)
        body = run([analyzer,'resources','xml','--file',path,apk], out/(name+'.xml'))
        decoded[name] = ET.fromstring(body)
    validate_resources(decoded['backup_rules'], decoded['data_extraction_rules'], decoded['network_security_config'])
    signing = run([tools/'apksigner','verify','--verbose','--print-certs',apk], out/'signature.txt')
    run([tools/'zipalign','-c','-P','16','4',apk], out/'alignment.txt')
    cert = re.search(r'certificate SHA-256 digest: ([0-9a-f]+)', signing)
    require(cert is not None, 'Missing APK signing certificate')
    run([tools/'aapt','dump','badging',apk], out/'badging.txt')
    run([tools/'aapt','dump','permissions',apk], out/'permissions.txt')
    require(run([analyzer,'manifest','debuggable',apk]).strip() == 'false', 'APK analyzer reports debuggable')
    with zipfile.ZipFile(apk) as archive:
        require(archive.testzip() is None, 'Corrupt ZIP')
        entries = archive.namelist()
        require(not any(n.lower().endswith(('.jks','.keystore','.p12','.pfx')) for n in entries), 'Signing material must not be packaged')
        inventory['dexFiles'] = sorted(n for n in entries if re.fullmatch(r'classes\d*\.dex', n))
        inventory['nativeLibraries'] = sorted(n for n in entries if n.startswith('lib/') and n.endswith('.so'))
        inventory['assets'] = sorted(n for n in entries if n.startswith('assets/') and not n.endswith('/'))
    inventory.update({'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(), 'apkBytes':apk.stat().st_size,'package':PACKAGE,'versionName':root.get(A+'versionName'),'versionCode':int(root.get(A+'versionCode')),'minSdk':26,'targetSdk':36,'signingCertificateSha256':cert.group(1),'signingTrust':'development key; NOT production release key ownership','backupExcludedDomains':sorted(DOMAINS),'verificationScope':'Final manifest/resources/signature/ZIP assertions, NOT malware analysis, binary audit, physical test or absolute safety proof'})
    (out/'inventory.json').write_text(json.dumps(inventory,indent=2)+'\n')
    print(json.dumps(inventory,indent=2))
    return inventory


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT')))
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    parser.error('Set --sdk or ANDROID_HOME') if args.sdk is None else None
    verify(args.apk, args.sdk, args.output_dir)
