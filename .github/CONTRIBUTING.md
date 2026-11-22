# Contributing to Daily Reminders

Use the [issue tracker](https://github.com/G-aUr-aV/daily-reminders/issues) to report a bug or discuss a substantial change. Report vulnerabilities through the [security policy](SECURITY.md).

1. Follow [Develop and verify](../README.md#develop-and-verify) for the JDK and Android SDK setup.
2. Make the change on a branch and open a pull request against `main`. Describe the behavior affected and how you verified it.
3. Run the checks below. For changes to alarms, permissions, Calendar sync, backups, or migrations, include the Android version and device or test environment you used.
4. Update the relevant docs and add user-facing changes under **Unreleased** in the [changelog](../docs/Changelog.md). When changing Room models, include the exported schema and migration verification.

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew --no-daemon --console=plain :app:testHardenedUnitTest :app:lintHardened
```

GitHub Actions runs the Android checks and verifies an APK signed with a disposable key. A maintainer approves workflow runs from outside contributors. Use fictional reminders and calendar entries in fixtures and screenshots. Keep backup files, device logs with personal data, and signing material out of Git.
