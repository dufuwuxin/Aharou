package com.aharou.core.content

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aharou.feature.settings.data.ExperimentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ContentProcessPolicyTest {
    @Test fun defaultsAndInvalidValuesAreDisabled() {
        listOf(null, "follow", "off", "invalid").forEach {
            assertEquals(ContentProcessPolicy.Snapshot(false, false), ContentProcessPolicy.resolve(it, it, 35))
        }
    }

    @Test fun androidEightAndNineSupportOrdinaryIsolation() {
        for (sdk in listOf(26, 28)) {
            assertEquals(ContentProcessPolicy.Snapshot(true, false), ContentProcessPolicy.resolve("on", "off", sdk))
            assertEquals(ContentProcessPolicy.Snapshot(true, false), ContentProcessPolicy.resolve("off", "on", sdk))
        }
    }

    @Test fun zygoteRequires29AndImpliesIsolation() {
        assertEquals(ContentProcessPolicy.Snapshot(true, true), ContentProcessPolicy.resolve("off", "on", 29))
    }

    @Test fun capturedPolicyDoesNotChangeUntilProcessRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = ExperimentSettings(context)
        settings.setValue(ContentProcessPolicy.ISOLATED_PROCESS, ExperimentSettings.OFF)
        settings.setValue(ContentProcessPolicy.APP_ZYGOTE, ExperimentSettings.OFF)
        val first = ContentProcessPolicy.capture(context)
        settings.setValue(ContentProcessPolicy.ISOLATED_PROCESS, ExperimentSettings.ON)
        assertSame(first, ContentProcessPolicy.capture(context))
        assertFalse(first.isolatedProcess)
        assertTrue(ContentProcessPolicy.resolve("on", "off", 28).isolatedProcess)
    }
}
