package dev.gaurav.dailyrhythm

import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HardenedVariantTest {
    @Test fun hardenedVariantIsNotDebuggableOrTestOnly() {
        val flags = RuntimeEnvironment.getApplication().applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertEquals(0, flags and ApplicationInfo.FLAG_TEST_ONLY)
    }
}
