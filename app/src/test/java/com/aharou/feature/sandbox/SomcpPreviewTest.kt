// SPDX-License-Identifier: AGPL-3.0-only
package com.aharou.feature.sandbox

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SomcpPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fileDetectionUsesMagicAndDoesNotTreatTextWithSoSuffixAsElf() {
        val text = temporary.newFile("fake.so").apply { writeText("plain text") }
        val binary = temporary.newFile("actual").apply { writeBytes(byteArrayOf(0x7f, 69, 76, 70, 2, 1)) }
        assertFalse(hasElfMagic(text)); assertTrue(hasElfMagic(binary))
        assertFalse(hasElfMagic(temporary.root)); assertFalse(hasElfMagic(File(temporary.root, "missing.so")))
        assertTrue(FileItem(binary, binary.name, false, false, binary.length()).isElfCandidate)
        assertFalse(FileItem(text, text.name, true, false, 0).isElfCandidate)
    }
    @Test fun nativePreviewShowsReadOnlyReportAndInvokesLocalActions() {
        var selected = ""
        val report = buildJsonObject { put("architecture", "arm64"); put("bits", 64); put("dependencies", buildJsonArray { add("libc.so") }) }
        compose.setContent { MaterialTheme { SomcpPreviewContent(SomcpPreviewState(report = report),
            onLoad = { action, _, _ -> selected = action }) } }
        compose.onNodeWithTag("somcp_preview").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.somcp_title)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.somcp_read_only)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.somcp_symbols)).performClick()
        assertEquals("symbols", selected)
    }
    @Test fun malformedInputShowsAnErrorAndAnExplicitRetryAction() {
        var called = false
        compose.setContent { MaterialTheme { SomcpPreviewContent(SomcpPreviewState(error = "ELF_SIZE_LIMIT"),
            onLoad = { _, _, _ -> called = true }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.somcp_too_large)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.somcp_retry)).performClick()
        assertTrue(called)
    }
}
