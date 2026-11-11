package com.aharou.feature.plugin.presentation

import android.content.Context
import androidx.lifecycle.ViewModelStore
import com.aharou.feature.extension.domain.InstalledExtensionAggregator
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.market.PluginMarketEntry
import com.aharou.feature.plugin.domain.market.PluginMarketRepository
import com.aharou.feature.plugin.presentation.PluginEvent
import com.aharou.feature.settings.presentation.ExtensionsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginMarketViewModelTest {
    private val market = mockk<PluginMarketRepository>()
    private val aggregator = mockk<InstalledExtensionAggregator>()
    private val owner = ViewModelStore()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { aggregator.installed() } returns emptyList()
    }

    @After fun cleanup() {
        owner.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ExtensionsViewModel(aggregator, market, mockk<PluginManager>(), mockk<Context>())
        .also { owner.put("market", it) }

    private suspend fun failed(model: ExtensionsViewModel) = withTimeout(5000) {
        model.event.first { it is PluginEvent.Failed }
        model.busyId.first { it == null }
    }

    @Test fun failedInstalledVersionReadIsNotProofThatNothingIsInstalled() = runBlocking {
        coEvery { market.installedVersions() } throws IOException("fixture read failed")
        val model = viewModel()
        withTimeout(5000) { model.loading.first { !it } }
        assertFalse(model.installedVersionsReady.value)
        model.installMarketPlugin(PluginMarketEntry(id = "pkg", version = "1.0", downloadUrl = "https://example.invalid/pkg"))
        failed(model)
        coVerify(exactly = 0) { market.install(any()) }
        assertFalse(model.installedVersionsReady.value)
    }

    @Test fun versionReadFailureCanRecoverThroughTheMarketRefreshAction() = runBlocking {
        coEvery { market.installedVersions() } throws IOException("first read failed")
        val model = viewModel()
        withTimeout(5000) { model.loading.first { !it } }
        assertFalse(model.installedVersionsReady.value)
        coEvery { market.installedVersions() } returns emptyMap()
        model.refresh()
        withTimeout(5000) { model.installedVersionsReady.first { it } }
        assertTrue(model.installedVersions.value.isEmpty())
    }

    @Test fun installRechecksAndCannotDowngradeAnAlreadyNewerLocalPackage() = runBlocking {
        coEvery { market.installedVersions() } returns mapOf("pkg" to "2.0")
        val model = viewModel()
        withTimeout(5000) { model.installedVersionsReady.first { it } }
        model.installMarketPlugin(PluginMarketEntry(id = "pkg", version = "1.0", downloadUrl = "https://example.invalid/pkg"),
            allowUnknownVersion = true)
        failed(model)
        coVerify(exactly = 0) { market.install(any()) }
    }

    @Test fun unknownVersionRequiresAnExplicitUnknownVersionConfirmation() = runBlocking {
        coEvery { market.installedVersions() } returns mapOf("pkg" to "development")
        val model = viewModel()
        withTimeout(5000) { model.installedVersionsReady.first { it } }
        model.installMarketPlugin(PluginMarketEntry(id = "pkg", version = "1.0", downloadUrl = "https://example.invalid/pkg"))
        failed(model)
        coVerify(exactly = 0) { market.install(any()) }
    }
}
