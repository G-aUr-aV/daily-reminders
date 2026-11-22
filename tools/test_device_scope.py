"""Source entry-point contracts, not APK or interaction proof."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/dev/gaurav/dailyrhythm'

class DeviceScopeContractTest(unittest.TestCase):
    def test_sound_ui_uses_android_pickers_and_private_import(self):
        source = (SRC/'SoundControls.kt').read_text()
        self.assertIn('ACTION_RINGTONE_PICKER', source)
        self.assertIn('TYPE_ALARM', source)
        self.assertIn('PrivateSounds(context).import(uri, fromTonePicker)', source)
        self.assertIn('LabeledRow("Import audio file"', source)
        self.assertIn('Intent.ACTION_OPEN_DOCUMENT', source)
        self.assertIn('Intent.ACTION_GET_CONTENT', source)
        self.assertIn('Intent.EXTRA_LOCAL_ONLY', source)
        files = (SRC/'PrivateSounds.kt').read_text()
        self.assertIn('checkUriPermission', files)
        self.assertIn('AudioPolicy.song(temp)', files)
        self.assertNotIn('takePersistableUriPermission', files)

    def test_preview_is_explicit_bounded_and_lifecycle_closed(self):
        source = (SRC/'SoundControls.kt').read_text()
        self.assertIn('30_000', source)
        self.assertIn('Lifecycle.Event.ON_PAUSE', source)
        self.assertIn('onDispose', source)
        self.assertIn('stopPreview()', source)
        self.assertIn('preview?.play(config.sound,false)', source)

    def test_catalog_uses_actual_framework_rows_and_no_fixed_phone_names(self):
        source = (SRC/'DeviceTones.kt').read_text()
        self.assertIn('RingtoneManager(context)', source)
        self.assertIn('manager.setType(RingtoneManager.TYPE_ALARM)', source)
        self.assertIn('manager.getRingtoneUri(position)', source)
        self.assertIn('TITLE_COLUMN_INDEX', source)
        self.assertIn('cursor.use', source)

if __name__ == '__main__':
    unittest.main(verbosity=2)
