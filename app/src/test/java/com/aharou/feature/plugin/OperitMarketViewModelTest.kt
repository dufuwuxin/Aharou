package com.aharou.feature.plugin

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.feature.agent.domain.skill.SkillImportReport
import com.aharou.feature.plugin.domain.market.OperitEntry
import com.aharou.feature.plugin.domain.market.OperitMarketRepository
import com.aharou.feature.plugin.domain.market.OperitPage
import com.aharou.feature.plugin.presentation.OperitMarketViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperitMarketViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<OperitMarketRepository>()
    private val views = mutableListOf<OperitMarketViewModel>()
    private val entry = OperitEntry("fixture", "skill", "Fixture")
    private fun page(type: String = "skill") = OperitPage(true, 2, "fixture-time", "updated", 1, 100, 1,
        listOf(entry.copy(type = type)))
    private fun view() = OperitMarketViewModel(repository).also(views::add)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.page(any(), any()) } returns page()
        every { repository.canDownload(any()) } returns true
        every { repository.canImport(any(), any()) } returns true
    }
    @After fun teardown() {
        views.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun cancellingDocumentChoiceNeverDownloadsOrImportsAndReleasesBusy() = runTest(dispatcher) {
        val vm = view()
        advanceUntilIdle()
        assertNotNull(vm.chooseDownload(entry, null))
        assertTrue(vm.state.value.choosing)
        assertTrue(vm.state.value.busy)
        vm.documentChosen(null)
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.choosing)
        coVerify(exactly = 0) { repository.save(any(), any(), any()) }
        coVerify(exactly = 0) { repository.importSkill(any(), any()) }
    }

    @Test fun realDocumentCallbackSavesOnlyChosenEntryAndReportsFailureWithoutStickingBusy() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        coEvery { repository.save(entry, null, uri) } throws IllegalStateException("fixture failure")
        val vm = view()
        advanceUntilIdle()
        vm.chooseDownload(entry, null)
        vm.documentChosen(uri)
        advanceUntilIdle()
        assertEquals(R.string.operit_operation_failed, vm.state.value.notice)
        assertFalse(vm.state.value.busy)
        coVerify(exactly = 1) { repository.save(entry, null, uri) }
        coVerify(exactly = 0) { repository.importSkill(any(), any()) }
    }

    @Test fun successfulSkillImportCallsExistingRepositoryConsumerSeparatelyFromSave() = runTest(dispatcher) {
        coEvery { repository.importSkill(entry, null) } returns SkillImportReport(listOf("fixture"))
        val vm = view()
        advanceUntilIdle()
        vm.importSkill(entry, null)
        advanceUntilIdle()
        assertEquals(R.string.operit_imported, vm.state.value.notice)
        assertFalse(vm.state.value.busy)
        coVerify(exactly = 1) { repository.importSkill(entry, null) }
        coVerify(exactly = 0) { repository.save(any(), any(), any()) }
    }

    @Test fun lateCancelledPageCannotOverwriteNewType() = runTest(dispatcher) {
        val old = CompletableDeferred<OperitPage>()
        coEvery { repository.page("all", 1) } coAnswers { withContext(NonCancellable) { old.await() } }
        coEvery { repository.page("script", 1) } returns page("script")
        val vm = view()
        advanceUntilIdle()
        vm.type("script")
        advanceUntilIdle()
        old.complete(page("skill"))
        advanceUntilIdle()
        assertEquals("script", vm.state.value.type)
        assertEquals("script", vm.state.value.result?.items?.single()?.type)
        assertFalse(vm.state.value.loading)
    }

    @Test fun cancellingRunningSaveDoesNotReportSuccessAndReleasesBusy() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        coEvery { repository.save(entry, null, uri) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val vm = view()
        advanceUntilIdle()
        vm.chooseDownload(entry, null)
        vm.documentChosen(uri)
        advanceUntilIdle()
        assertTrue(vm.state.value.busy)
        vm.cancel()
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.notice)
    }
}
