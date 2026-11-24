# Development and sideloading

The project uses Gradle 8.10.2, Android Gradle Plugin 8.7.3, Kotlin, Compose, and Room. The application ID is `dev.gaurav.dailyrhythm` and version 0.7.1 has version code 20. Follow the [README](../README.md#develop-and-verify) to set up the SDK and run the source checks.

## Build an APK

Use a Linux or macOS shell for the APK verification commands. Install **Android SDK Command-line Tools (latest)** using SDK Manager. The verifier uses `cmdline-tools/latest/bin/apkanalyzer` and Build Tools 35.0.0 under `ANDROID_HOME`. The packaging task builds the non-debuggable `hardened` variant and requires an explicit opt-in and signing credentials.

For a clean install on a test device, create a development key in a new private directory outside the checkout. In a Bash shell:

```sh
python3 tools/new_development_key.py "$HOME/.daily-rhythm-test-signing"
source "$HOME/.daily-rhythm-test-signing/signing.env"
./gradlew --no-daemon --console=plain -PallowFreshDevelopmentApk=true :app:verifyHardenedApk
```

If you already have the development key for an installed copy, use its existing private environment file. The build reads four variables:

| Variable | Purpose |
| --- | --- |
| `DAILY_RHYTHM_KEYSTORE` | Absolute path to the existing keystore |
| `DAILY_RHYTHM_STORE_PASSWORD` | Keystore password |
| `DAILY_RHYTHM_KEY_ALIAS` | Key alias |
| `DAILY_RHYTHM_KEY_PASSWORD` | Key password |

Keep these values and the keystore in private storage outside the checkout. Back up the signing identity securely if you need to issue future updates. GitHub Actions uses a temporary key per build and runs the package checks without uploading an APK. See the [Android signing guide](https://developer.android.com/studio/publish/app-signing).

The APK is at `app/build/outputs/apk/hardened/app-hardened.apk`. The verifier checks the compiled manifest, exact permission allowlist, exposed components, backup and network rules, signature, ZIP integrity, and alignment. It writes reports to `app/build/reports/offline-verification/`. For signer comparison, use the SDK tools:

```sh
apk=app/build/outputs/apk/hardened/app-hardened.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$apk"
```

## Update and recover

Android requires the same signing identity and a compatible version code for an in-place update. Compare signing certificate SHA-256 fingerprints with the APK already installed. With the correct signer and Android Platform-Tools available, install the update with `adb install -r "$apk"`.

Before upgrading, create an encrypted backup in **Settings → Data & privacy** if the installed version offers it. Version 0.7.0 imports older reminder data into Room on first use. Clearing app storage or uninstalling erases private reminders, history, and imported sounds. Use a separate test device for an APK signed with a new key.

## Release checks

Use an emulator or spare phone for fresh-install testing, and use a physical phone for behavior that depends on device policy:

1. On a fresh install, create a reminder and confirm that alarms and Calendar access are requested when needed.
2. Upgrade a same-signed test installation containing reminders, history, and imported audio. Check that the records survive and that future alarms are scheduled.
3. Test ringing on a locked phone, Doze, reboot, and notification or exact-alarm access changes. Check that Stop, Snooze, and Done have distinct effects.
4. Verify audio from the phone's picker, backup round-trip with imported audio, and Calendar updates in a dedicated test calendar, including propagation to the linked provider if enabled.
5. Check dark and light themes, large text, gesture navigation, and the widget on representative Android versions.

The repository's APK workflow is for private sideload testing. A Play submission needs a separate release workflow, production signing, platform and permission-policy review, and an API 36 or higher target under the [current Play target requirements](https://support.google.com/googleplay/android-developer/answer/11926878). Review [Google's release preparation guide](https://developer.android.com/studio/publish/preparing) before distributing.

Commit source, tests, and Room schema changes together. When preparing an update, increment the version code and update the expected version in the APK verifier and its fixtures. Use pull requests and check the Android workflow before merging future changes.
