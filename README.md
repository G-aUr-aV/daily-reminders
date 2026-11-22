# Daily Reminders

Daily Reminders is an offline Android app for recurring reminders, alarms, checklists, and completion history. Version **0.7.0** supports Android 8.0 and later.

- **Today** shows the day's agenda. Mark a routine done, check off its steps, or skip an occurrence. Stopping an alarm leaves the routine pending.
- **Reminders** supports one-time and recurring schedules, including schedules based on office days, work-from-home days, leave, and weekends. Sample routines are editable drafts with alerts off.
- **Alerts** include notifications, ringing alarms, and optional full-screen presentation. Choose a bundled sound, a device tone, or an audio file.
- **History** includes a seven-day summary, outcome corrections, and iCalendar export. Optional Calendar integration publishes occurrences and outcomes to a calendar you choose.
- **Settings → Data & privacy** exports and restores password-protected backups of reminders, history, and imported sounds.

A fresh install starts with an empty plan and Calendar integration off. To start, open **Reminders**, add a reminder, and choose its alert. Set your usual office days in **Settings** if you use day-type schedules.

## Develop and verify

Install Android Studio or the Android SDK command-line tools, JDK 21, and Python 3. Select JDK 21 for Gradle, set `ANDROID_HOME` to the SDK location, and install Android SDK Platform 35 and Build Tools 35.0.0.

```sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew --no-daemon --console=plain :app:testHardenedUnitTest :app:lintHardened
```

If SDK Manager prompts for licenses, review them with `sdkmanager --licenses`. The checked-in Gradle Wrapper downloads Gradle 8.10.2. Initial build and test setup needs access to the Gradle distribution and dependency repositories. For the Gradle source checks on Windows, use `gradlew.bat`.

Run the two Gradle tasks above for a source-only check. [Development and sideloading](docs/development.md) covers building a signed test APK, inspecting it, and updating an existing installation. [Privacy and permissions](docs/privacy.md) describes local data, Calendar sharing, and Android access.

## Project layout

| Path | Contents |
| --- | --- |
| `app/src/main` | Kotlin, Jetpack Compose UI, Room storage, alarms, Calendar integration, and widget |
| `app/src/test`, `app/src/testHardened` | JVM and Robolectric tests |
| `app/schemas` | Exported Room schemas for migration review |
| `tools` | Packaged APK verifier, policy fixtures, and development signing helper |
| `.github/workflows` | Android CI for pushes to `main` and pull requests |
