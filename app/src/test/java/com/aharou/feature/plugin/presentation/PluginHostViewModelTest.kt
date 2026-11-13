package com.aharou.feature.plugin.presentation

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import androidx.lifecycle.ViewModelStore
import com.aharou.feature.plugin.domain.PluginManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginHostViewModelTest {
    private val manager = mockk<PluginManager>()
    private val owner = ViewModelStore()
    private val generations = MutableStateFlow<Map<String, Long>>(emptyMap())
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { manager.listInstalled() } returns emptyList()
        every { manager.hostGenerations } returns generations
    }
    @After fun cleanup() { owner.clear(); Dispatchers.resetMain() }
    @Test fun hostActionsConsumeRealManagerAndExposeFailureOutput() = runTest {
        coEvery { manager.setEnabled("fixture", true) } returns Result.success(Unit)
        coEvery { manager.compile("fixture") } returns Result.failure(IllegalStateException("bad compiler"))
        every { manager.compileOutput("fixture") } returns "fixture compiler stderr"
        val model = PluginViewModel(manager, mockk<Context>()).also { owner.put("fixture", it) }
        model.setEnabled("fixture", true)
        advanceUntilIdle()
        coVerify { manager.setEnabled("fixture", true) }
        model.compile("fixture")
        // compileOutput is intentionally read on Dispatchers.IO, not on the main test dispatcher.
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5000) { model.hostPanelOpen.first { it } }
        }
        assertEquals("fixture compiler stderr", model.compileOutput.value)
        assertTrue(model.hostPanelOpen.value)
        coVerify { manager.compile("fixture") }
    }
    @Test fun externalStopAndReplacementDetachTheViewBoundToOldGeneration() = runTest {
        val application = mockk<Context>()
        val activity = mockk<Context>()
        val parent = mockk<ViewGroup>(relaxed = true)
        val view = mockk<View>()
        every { view.parent } returns parent
        generations.value = mapOf("fixture" to 1L)
        coEvery { manager.createHostView("fixture", activity) } returns view
        val model = PluginViewModel(manager, application).also { owner.put("fixture", it) }
        model.openHostView("fixture", activity)
        advanceUntilIdle()
        assertSame(view, model.hostView.value)
        assertTrue(model.hostPanelOpen.value)
        coVerify(exactly = 1) { manager.createHostView("fixture", activity) }
        generations.value = emptyMap()
        advanceUntilIdle()
        assertFalse(model.hostPanelOpen.value)
        assertNull(model.hostView.value)
        verify(exactly = 1) { parent.removeView(view) }
        generations.value = mapOf("fixture" to 2L)
        model.openHostView("fixture", activity)
        advanceUntilIdle()
        assertTrue(model.hostPanelOpen.value)
        generations.value = mapOf("fixture" to 3L)
        advanceUntilIdle()
        assertFalse(model.hostPanelOpen.value)
        assertNull(model.hostView.value)
        verify(exactly = 2) { parent.removeView(view) }
    }

    @Test fun generationChangedDuringCreationNeverPublishesStaleView() = runTest {
        val view = mockk<View>()
        val parent = mockk<ViewGroup>(relaxed = true)
        every { view.parent } returns parent
        generations.value = mapOf("fixture" to 1L)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { manager.createHostView("fixture", any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            view
        }
        val model = PluginViewModel(manager, mockk<Context>()).also { owner.put("fixture", it) }
        model.openHostView("fixture")
        entered.await()
        generations.value = mapOf("fixture" to 2L)
        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.hostPanelOpen.value)
        assertNull(model.hostView.value)
        verify(exactly = 1) { parent.removeView(view) }
        assertTrue(model.event.value is PluginEvent.Failed)
    }

}
