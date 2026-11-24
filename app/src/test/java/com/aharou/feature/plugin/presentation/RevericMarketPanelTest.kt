package com.aharou.feature.plugin.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.aharou.R
import com.aharou.feature.plugin.domain.market.RevericCatalog
import com.aharou.feature.plugin.domain.market.RevericMarketRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.ON)
class RevericMarketPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun clickingCatalogCardOpensDisplayOnlyVersionDetailsAndCloseReturnsToTheList() {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val request = chain.request()
            check(request.url.toString() == RevericCatalog.URL)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(catalog.toResponseBody()).build()
        }.build()
        val owner = RevericMarketViewModel(RevericMarketRepository(compose.activity, client), SavedStateHandle())
        fun label(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
        compose.setContent { MaterialTheme { RevericMarketPanel(owner) } }
        compose.waitUntil(timeoutMillis = 5_000) { owner.state.value.loaded }
        val card = compose.onNode(hasText("Fixture plugin") and hasClickAction())
        val id = compose.onNodeWithText(label(R.string.reveric_plugin_id, "fixture"))
        compose.onNodeWithText(label(R.string.reveric_catalog_count, 1, 1)).assertExists()
        id.assertDoesNotExist()
        card.performClick()
        id.assertExists()
        compose.onNode(hasText(description) and !hasClickAction()).assertExists()
        compose.onNode(hasText(label(R.string.reveric_author, "Fixture author")) and !hasClickAction()).assertExists()
        compose.onNodeWithText(label(R.string.reveric_detail_scope)).assertExists()
        compose.onNodeWithText(label(R.string.reveric_version, "1.0.0", "7", "android, linux", 12L)).assertExists()
        compose.onNodeWithText(label(R.string.reveric_sha256, hash)).assertExists()
        compose.onNodeWithText(label(R.string.reveric_source_commit, "001122aabb")).assertExists()
        compose.onNodeWithText(label(R.string.reveric_repository, "https://example.invalid/repo")).assertHasNoClickAction()
        compose.onNodeWithText(label(R.string.reveric_release_url, "https://example.invalid/releases/1.0.0"))
            .assertHasNoClickAction()
        compose.onNode(hasText(label(R.string.reveric_download)) and hasClickAction()).assertIsEnabled()
        compose.onNodeWithText(label(R.string.common_close)).performClick()
        id.assertDoesNotExist()
        card.assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("absent")
        compose.onNodeWithText(label(R.string.reveric_catalog_count, 1, 0)).assertExists()
        card.assertDoesNotExist()
        assertEquals(1, calls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun rendersCatalogEntriesBeyondThePreviousFourItemObservation() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val request = chain.request()
            check(request.url.toString() == RevericCatalog.URL)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(catalogWithPlugins(5).toResponseBody()).build()
        }.build()
        val repository = RevericMarketRepository(compose.activity, client, catalogDispatcher = dispatcher)
        val owner = RevericMarketViewModel(repository, SavedStateHandle())
        fun label(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
        compose.setContent { MaterialTheme { RevericMarketPanel(owner) } }
        compose.waitUntil(timeoutMillis = 5_000) {
            // Compose advances its own clock, not the catalog dispatcher.
            scheduler.runCurrent()
            owner.state.value.let { it.loadAttempted && !it.loading }
        }
        assertTrue("Reveric ZIP load failed: requests=${calls.get()}, notice=${owner.state.value.notice}", owner.state.value.loaded)
        assertEquals(1, calls.get())
        compose.onNodeWithText(label(R.string.reveric_catalog_count, 5, 5)).assertExists()
        compose.onNodeWithTag("reveric_catalog_list").performScrollToNode(hasText("Fixture plugin 5"))
        compose.onNodeWithText("Fixture plugin 5").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("fixture.5")
        compose.onNodeWithText(label(R.string.reveric_catalog_count, 5, 1)).assertExists()
        compose.onNodeWithText("Fixture plugin 5").assertExists()
        compose.onNodeWithText("Fixture plugin 1").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText(label(R.string.reveric_catalog_count, 5, 5)).assertExists()
    }

    @Test fun showsBundledMagpieCatalogAndPreservesTheRevericZipSource() {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.toString() == RevericCatalog.URL)
            calls.incrementAndGet()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(catalog.toResponseBody()).build()
        }.build()
        val owner = RevericMarketViewModel(RevericMarketRepository(compose.activity, client), SavedStateHandle())
        fun label(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
        compose.setContent { MaterialTheme { RevericSourcePanel(owner) } }
        val snapshotCount = label(R.string.reveric_catalog_count, 127, 127)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText(snapshotCount)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, calls.get())
        compose.onNode(hasSetTextAction()).performTextInput("@magpie-community/agent-pine")
        compose.onNodeWithText(label(R.string.reveric_catalog_count, 127, 1)).assertExists()
        compose.onNode(hasText("@magpie-community/agent-pine") and !hasSetTextAction()).assertHasNoClickAction()
        compose.onNode(hasText(label(R.string.reveric_zip_catalog_title)) and hasClickAction())
            .performClick().assertIsSelected()
        compose.onNodeWithText(label(R.string.reveric_source)).assertExists()
        val zipCount = label(R.string.reveric_catalog_count, 1, 1)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText(zipCount)).fetchSemanticsNodes().isNotEmpty() ||
                owner.state.value.let { it.loadAttempted && !it.loading && !it.loaded }
        }
        assertTrue("Reveric ZIP load failed: requests=${calls.get()}, notice=${owner.state.value.notice}", owner.state.value.loaded)
        compose.onNodeWithText(zipCount).assertExists()
        assertEquals(1, calls.get())
    }

    private fun catalogWithPlugins(count: Int): String = """{"schemaVersion":1,"plugins":[${(1..count).joinToString(",") { index ->
        """{"id":"fixture.$index","name":{"en":"Fixture plugin $index"},"description":{"en":"Fixture $index"},"author":"Fixture author","repository":"https://example.invalid/repository","versions":[{"version":"1.0.0","pluginApiVersion":"^1","platforms":["android"],"url":"https://downloads.reveric.app/plugins/fixture-$index.zip","fallbackUrl":"https://example.invalid/fallback.zip","size":12,"sha256":"$hash","releaseUrl":"https://example.invalid/release","sourceCommit":"fixture-commit"}]}"""
    }}]}"""

    private val description = "The full English description should survive the card summary. " +
        "This final sentence is only fully visible in the detail view."
    private val hash = "a".repeat(64)
    private val catalog = """
        {"schemaVersion":1,"plugins":[{
          "id":"fixture","name":{"en":"Fixture plugin"},"description":{"en":"$description"},
          "author":"Fixture author","repository":"https://example.invalid/repo",
          "versions":[{"version":"1.0.0","pluginApiVersion":"7","platforms":["android","linux"],"size":12,
            "sha256":"$hash","url":"https://downloads.reveric.app/plugins/fixture.zip",
            "fallbackUrl":"https://example.invalid/fixture.zip","sourceCommit":"001122aabb",
            "releaseUrl":"https://example.invalid/releases/1.0.0"}]
        }]}
    """.trimIndent()
}
