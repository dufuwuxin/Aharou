package com.aharou.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.aharou.R
import com.aharou.feature.agent.domain.ocr.TessdataPack
import com.aharou.feature.agent.domain.ocr.TessdataFileStatus
import com.aharou.feature.agent.domain.ocr.TessdataState
import com.aharou.feature.agent.domain.ocr.TessdataStatus
import com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication
import com.aharou.feature.settings.presentation.component.OcrLanguagePackRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectTaskConversationTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OcrDownloadStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val disk = mutableStateOf(status { null })
    private val download = mutableStateOf<TessdataState>(TessdataState.Idle)
    private var downloadClicks = 0

    @Test fun readyHidesDownloadEvenWithAnOldFailure() {
        disk.value = status { it }
        download.value = TessdataState.Failed("previous attempt")
        showRow()

        row().assertHasNoClickAction().performTouchInput { click() }
        compose.onNodeWithText(text(R.string.settings_ocr_pack_ready)).assertExists()
        compose.onNodeWithText(text(R.string.settings_ocr_pack_failed)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_ocr_pack_download)).assertDoesNotExist()
        assertEquals(0, downloadClicks)
    }

    @Test fun downloadingShowsProgressAndIgnoresFurtherTapsUntilReady() {
        showRow()
        compose.onNodeWithText(text(R.string.settings_ocr_pack_not_downloaded)).assertExists()
        compose.onNodeWithText(text(R.string.settings_ocr_pack_download)).assertExists()
        row().assertHasClickAction().performClick()

        val progress = compose.activity.getString(R.string.settings_ocr_pack_downloading, 40)
        compose.onNodeWithText(progress).assertExists()
        compose.onNodeWithText(text(R.string.settings_ocr_pack_download)).assertDoesNotExist()
        row().assertHasNoClickAction().performTouchInput { click() }
        assertEquals(1, downloadClicks)

        compose.runOnIdle {
            disk.value = status { it }
            download.value = TessdataState.Ready
        }
        compose.onNodeWithText(text(R.string.settings_ocr_pack_ready)).assertExists()
        row().assertHasNoClickAction()
    }

    @Test fun damagedAndFailedPacksCanRetry() {
        disk.value = status { it - 1 }
        showRow()
        compose.onNodeWithText(text(R.string.settings_ocr_pack_size_mismatch), substring = true).assertExists()
        row().assertHasClickAction().performClick()

        compose.runOnIdle { download.value = TessdataState.Failed("network") }
        compose.onNodeWithText(text(R.string.settings_ocr_pack_failed)).assertExists()
        compose.onNodeWithText(text(R.string.settings_ocr_pack_download)).assertExists()
        row().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(2, downloadClicks) }
    }

    private fun showRow() = compose.setContent {
        MaterialTheme {
            OcrLanguagePackRow(disk.value, download.value) {
                downloadClicks++
                download.value = TessdataState.Downloading(0.4f)
            }
        }
    }

    private fun row() = compose.onNodeWithText(text(R.string.settings_ocr_pack_title))

    private fun text(id: Int) = compose.activity.getString(id)

    private fun status(actual: (Long) -> Long?) = TessdataStatus(TessdataPack.FILES.map {
        TessdataFileStatus(it.name, it.bytes, actual(it.bytes))
    })
}
