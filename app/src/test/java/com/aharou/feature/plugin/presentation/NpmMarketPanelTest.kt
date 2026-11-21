package com.aharou.feature.plugin.presentation

import com.aharou.feature.plugin.domain.npm.NpmPackageMetadata
import com.aharou.feature.plugin.domain.npm.NpmRegistryError
import com.aharou.feature.plugin.domain.npm.NpmRegistryException
import com.aharou.feature.plugin.domain.npm.NpmSearchPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** State/presentation contract tests; these do not claim rendered Compose coverage. */
@OptIn(ExperimentalCoroutinesApi::class)
class NpmMarketPanelTest {
    private class FakeSource : NpmMarketSource {
        val calls = mutableListOf<String>()
        var searchResult: suspend (String, Int, Int) -> NpmSearchPage = { _, from, size ->
            NpmSearchPage(listOf(metadata()), 45, from, size, (from + size).takeIf { it < 45 })
        }
        var detailResult: suspend (String, String) -> NpmPackageMetadata = { name, version ->
            metadata(name, version)
        }

        override suspend fun search(query: String, from: Int, size: Int): NpmSearchPage {
            calls += "search:$query:$from:$size"
            return searchResult(query, from, size)
        }

        override suspend fun packageVersion(packageName: String, exactVersion: String): NpmPackageMetadata {
            calls += "details:$packageName:$exactVersion"
            return detailResult(packageName, exactVersion)
        }
    }

    @Test fun explicitSearchTrimsQueryAndPagesInBothDirections() = runTest {
        val source = FakeSource()
        val owner = NpmMarketStateOwner(source, this)
        assertEquals(NpmLoadStatus.IDLE, owner.state.value.searchStatus)
        owner.query("  compose  ")
        assertTrue(source.calls.isEmpty())
        owner.search()
        assertEquals(NpmLoadStatus.LOADING, owner.state.value.searchStatus)
        owner.next()
        advanceUntilIdle()
        assertEquals(listOf("search:compose:0:20"), source.calls)
        assertFalse(owner.state.value.canGoBack)
        assertTrue(owner.state.value.canGoForward)
        owner.next()
        advanceUntilIdle()
        assertEquals(20, owner.state.value.page?.from)
        owner.next()
        advanceUntilIdle()
        assertFalse(owner.state.value.canGoForward)
        owner.previous()
        advanceUntilIdle()
        assertEquals(20, owner.state.value.page?.from)
        assertEquals(listOf(0, 20, 40, 20), source.calls.map { it.split(':')[2].toInt() })
    }

    @Test fun overlongQueryNeverReachesSource() = runTest {
        val source = FakeSource()
        val owner = NpmMarketStateOwner(source, this)
        owner.query("x".repeat(201))
        assertFalse(owner.state.value.canSearch)
        owner.search()
        advanceUntilIdle()
        assertEquals(NpmRegistryError.INVALID_INPUT, owner.state.value.searchError)
        assertTrue(source.calls.isEmpty())
    }

    @Test fun firstEntryLoadsDefaultOnceWithoutPopulatingTheInput() = runTest {
        val source = FakeSource()
        val owner = NpmMarketStateOwner(source, this)
        owner.browse()
        owner.browse()
        advanceUntilIdle()
        owner.browse()
        owner.query("  ")
        owner.search()
        advanceUntilIdle()
        assertEquals(listOf("search:keywords:mcp:0:20"), source.calls)
        assertEquals("  ", owner.state.value.query)
        assertFalse(owner.state.value.canSearch)
        assertEquals(NpmLoadStatus.READY, owner.state.value.searchStatus)
        assertEquals("keywords:mcp", owner.state.value.activeQuery)
    }

    @Test fun blankSearchUsesDefaultAndClearingExplicitResultsReturnsToFirstDefaultPage() = runTest {
        val source = FakeSource()
        val owner = NpmMarketStateOwner(source, this)
        owner.search()
        advanceUntilIdle()
        assertEquals("", owner.state.value.query)
        owner.query("compose")
        advanceUntilIdle()
        assertEquals(listOf("search:keywords:mcp:0:20"), source.calls)
        owner.search()
        advanceUntilIdle()
        owner.next()
        advanceUntilIdle()
        owner.query("")
        advanceUntilIdle()
        assertEquals(listOf("search:keywords:mcp:0:20", "search:compose:0:20", "search:compose:20:20",
            "search:keywords:mcp:0:20"), source.calls)
        assertEquals("", owner.state.value.query)
        assertEquals(0, owner.state.value.page?.from)
    }

    @Test fun defaultFailureRetriesAndPagesUsingTheDiscoveryQuery() = runTest {
        val source = FakeSource()
        val result = source.searchResult
        source.searchResult = { _, _, _ -> throw NpmRegistryException(NpmRegistryError.RATE_LIMITED) }
        val owner = NpmMarketStateOwner(source, this)
        owner.browse()
        advanceUntilIdle()
        assertEquals(NpmRegistryError.RATE_LIMITED, owner.state.value.searchError)
        owner.browse()
        advanceUntilIdle()
        assertEquals(listOf("search:keywords:mcp:0:20"), source.calls)
        source.searchResult = result
        owner.retrySearch()
        advanceUntilIdle()
        owner.next()
        advanceUntilIdle()
        assertEquals(20, owner.state.value.page?.from)
        owner.previous()
        advanceUntilIdle()
        assertEquals(listOf("search:keywords:mcp:0:20", "search:keywords:mcp:0:20", "search:keywords:mcp:20:20",
            "search:keywords:mcp:0:20"), source.calls)
    }

    @Test fun clearingWhileSearchIsPendingRejectsLateExplicitResults() = runTest {
        val late = CompletableDeferred<NpmSearchPage>()
        val source = FakeSource().apply {
            searchResult = { query, from, size ->
                if (query == "old") withContext(NonCancellable) { late.await() }
                else NpmSearchPage(listOf(metadata("default-fixture")), 1, from, size, null)
            }
        }
        val owner = NpmMarketStateOwner(source, this)
        owner.query("old")
        owner.search()
        runCurrent()
        owner.query("")
        runCurrent()
        late.complete(NpmSearchPage(listOf(metadata("old")), 1, 0, 20, null))
        advanceUntilIdle()
        assertEquals(listOf("search:old:0:20", "search:keywords:mcp:0:20"), source.calls)
        assertEquals("keywords:mcp", owner.state.value.activeQuery)
        assertEquals("default-fixture", owner.state.value.page!!.packages.single().name)
    }

    @Test fun leadingWhitespaceCannotTurnOverlongInputIntoTruncatedSearch() = runTest {
        val source = FakeSource()
        val owner = NpmMarketStateOwner(source, this)
        for (query in listOf("  " + "x".repeat(201), " ".repeat(256) + "x".repeat(201) + "   ")) {
            owner.query(query)
            assertEquals(query, owner.state.value.query)
            assertEquals(NpmLoadStatus.ERROR, owner.state.value.searchStatus)
            assertEquals(NpmRegistryError.INVALID_INPUT, owner.state.value.searchError)
            assertFalse(owner.state.value.canSearch)
            owner.search()
            advanceUntilIdle()
            assertEquals(NpmRegistryError.INVALID_INPUT, owner.state.value.searchError)
            assertTrue(source.calls.isEmpty())
        }
    }

    @Test fun whitespaceWrappedValidQueryIsSentInFullAfterTrimming() = runTest {
        val source = FakeSource()
        val owner = NpmMarketStateOwner(source, this)
        val normalized = "x".repeat(200)
        val query = " ".repeat(256) + normalized + "   "
        owner.query(query)
        assertEquals(query, owner.state.value.query)
        assertTrue(owner.state.value.canSearch)
        assertEquals(NpmLoadStatus.IDLE, owner.state.value.searchStatus)
        owner.search()
        advanceUntilIdle()
        assertEquals(listOf("search:$normalized:0:20"), source.calls)
        assertEquals(normalized, owner.state.value.activeQuery)
    }

    @Test fun emptyResultsAndUnknownTotalsRemainExplicit() = runTest {
        val source = FakeSource().apply {
            searchResult = { _, from, size -> NpmSearchPage(emptyList(), null, from, size, null) }
        }
        val owner = NpmMarketStateOwner(source, this)
        owner.query("empty")
        owner.search()
        advanceUntilIdle()
        assertEquals(NpmLoadStatus.READY, owner.state.value.searchStatus)
        assertTrue(owner.state.value.page!!.packages.isEmpty())
        assertNull(owner.state.value.page!!.total)
        assertFalse(owner.state.value.canGoForward)
    }

    @Test fun failedNextPageRetriesSameOffsetWithoutSkippingOrClearingCurrentResults() = runTest {
        val source = FakeSource()
        val owner = NpmMarketStateOwner(source, this)
        owner.query("retry")
        owner.search()
        advanceUntilIdle()
        source.searchResult = { _, _, _ -> throw NpmRegistryException(NpmRegistryError.RATE_LIMITED) }
        owner.next()
        advanceUntilIdle()
        assertEquals(NpmLoadStatus.ERROR, owner.state.value.searchStatus)
        assertEquals(NpmRegistryError.RATE_LIMITED, owner.state.value.searchError)
        assertEquals(0, owner.state.value.page?.from)
        assertFalse(owner.state.value.canGoForward)
        assertEquals(2, source.calls.size)
        source.searchResult = { _, from, size -> NpmSearchPage(listOf(metadata()), 45, from, size, 40) }
        owner.retrySearch()
        advanceUntilIdle()
        assertEquals("search:retry:20:20", source.calls.last())
        assertEquals(20, owner.state.value.page?.from)
        assertNull(owner.state.value.searchError)
    }

    @Test fun allRegistryErrorsRemainTypedAndReleaseSearchLoading() = runTest {
        val source = FakeSource()
        val owner = NpmMarketStateOwner(source, this)
        for (error in NpmRegistryError.values()) {
            source.searchResult = { _, _, _ -> throw NpmRegistryException(error) }
            owner.query(error.name)
            owner.search()
            advanceUntilIdle()
            assertEquals(NpmLoadStatus.ERROR, owner.state.value.searchStatus)
            assertEquals(error, owner.state.value.searchError)
        }
    }

    @Test fun changedQueryRejectsLateUncooperativeSearchResponse() = runTest {
        val late = CompletableDeferred<NpmSearchPage>()
        val source = FakeSource().apply {
            searchResult = { query, from, size ->
                if (query == "old") withContext(NonCancellable) { late.await() }
                else NpmSearchPage(listOf(metadata(query)), 1, from, size, null)
            }
        }
        val owner = NpmMarketStateOwner(source, this)
        owner.query("old")
        owner.search()
        runCurrent()
        owner.query("new")
        assertNull(owner.state.value.page)
        owner.search()
        runCurrent()
        late.complete(NpmSearchPage(listOf(metadata("old")), 1, 0, 20, null))
        advanceUntilIdle()
        assertEquals("new", owner.state.value.activeQuery)
        assertEquals("new", owner.state.value.page!!.packages.single().name)
        assertEquals(NpmLoadStatus.READY, owner.state.value.searchStatus)
    }

    @Test fun detailsReadOnlyExactScopedCoordinateAndMissingCoordinateIsNotFetched() = runTest {
        val source = FakeSource()
        val owner = NpmMarketStateOwner(source, this)
        owner.select(metadata().copy(name = null))
        owner.select(metadata().copy(version = null))
        advanceUntilIdle()
        assertTrue(source.calls.isEmpty())
        owner.select(metadata("@scope/example", "1.2.3-beta.1"))
        assertEquals(NpmLoadStatus.LOADING, owner.state.value.detailStatus)
        advanceUntilIdle()
        assertEquals(listOf("details:@scope/example:1.2.3-beta.1"), source.calls)
        assertEquals("1.2.3-beta.1", owner.state.value.detail?.version)
        assertEquals(NpmLoadStatus.READY, owner.state.value.detailStatus)
        assertEquals(setOf("search", "packageVersion"), NpmMarketSource::class.java.declaredMethods.map { it.name }.toSet())
    }

    @Test fun rateLimitedDetailsRetryOnlyWhenRequested() = runTest {
        val source = FakeSource().apply {
            detailResult = { _, _ -> throw NpmRegistryException(NpmRegistryError.RATE_LIMITED) }
        }
        val owner = NpmMarketStateOwner(source, this)
        owner.select(metadata())
        advanceUntilIdle()
        assertEquals(NpmRegistryError.RATE_LIMITED, owner.state.value.detailError)
        assertEquals(1, source.calls.size)
        source.detailResult = { name, version -> metadata(name, version) }
        owner.retryDetails()
        advanceUntilIdle()
        assertEquals(2, source.calls.size)
        assertEquals(NpmLoadStatus.READY, owner.state.value.detailStatus)
        assertNull(owner.state.value.detailError)
    }

    @Test fun closingDetailsRejectsLateResponseAndDoesNotReopenSheet() = runTest {
        val late = CompletableDeferred<NpmPackageMetadata>()
        val source = FakeSource().apply { detailResult = { _, _ -> withContext(NonCancellable) { late.await() } } }
        val owner = NpmMarketStateOwner(source, this)
        owner.select(metadata())
        runCurrent()
        owner.dismissDetails()
        late.complete(metadata())
        advanceUntilIdle()
        assertNull(owner.state.value.selected)
        assertNull(owner.state.value.detail)
        assertEquals(NpmLoadStatus.IDLE, owner.state.value.detailStatus)
    }

    @Test fun newDetailSelectionRejectsLateOldPackage() = runTest {
        val late = CompletableDeferred<NpmPackageMetadata>()
        val source = FakeSource().apply {
            detailResult = { name, version ->
                if (name == "old") withContext(NonCancellable) { late.await() } else metadata(name, version)
            }
        }
        val owner = NpmMarketStateOwner(source, this)
        owner.select(metadata("old"))
        runCurrent()
        owner.select(metadata("new"))
        runCurrent()
        late.complete(metadata("old"))
        advanceUntilIdle()
        assertEquals("new", owner.state.value.selected?.name)
        assertEquals("new", owner.state.value.detail?.name)
    }

    @Test fun mismatchedDetailsAreRejectedAndMissingFieldsRemainUnknown() = runTest {
        val source = FakeSource().apply { detailResult = { _, _ -> metadata(version = "9.9.9") } }
        val owner = NpmMarketStateOwner(source, this)
        owner.select(metadata())
        advanceUntilIdle()
        assertEquals(NpmRegistryError.INVALID_RESPONSE, owner.state.value.detailError)
        assertNull(owner.state.value.detail)
        source.detailResult = { _, _ -> metadata().copy(name = null, version = null) }
        owner.retryDetails()
        advanceUntilIdle()
        assertEquals(NpmLoadStatus.READY, owner.state.value.detailStatus)
        assertNull(owner.state.value.detail?.name)
        assertNull(owner.state.value.detail?.version)
    }

    @Test fun fullDiscoveryFlowOnlyCallsReadOperationsAndNeverFetchesDetailsAutomatically() = runTest {
        val source = FakeSource()
        val owner = NpmMarketStateOwner(source, this)
        owner.query("fixture")
        owner.search()
        advanceUntilIdle()
        owner.next()
        advanceUntilIdle()
        assertEquals(listOf("search:fixture:0:20", "search:fixture:20:20"), source.calls)
        owner.select(owner.state.value.page!!.packages.single())
        advanceUntilIdle()
        owner.dismissDetails()
        owner.query("changed")
        advanceUntilIdle()
        assertEquals(listOf("search:fixture:0:20", "search:fixture:20:20", "details:fixture:1.0.0"), source.calls)
        assertNull(owner.state.value.selected)
        assertNull(owner.state.value.detail)
        assertNull(owner.state.value.page)
    }

    @Test fun presentationContainsExactlySevenPlainTextFieldsAndDoesNotInterpretMarkupOrRepository() {
        val metadata = NpmPackageMetadata(
            "fixture", "1.0.0", "<script>fixture</script>", "MIT", "javascript:fixture", "deprecated", "sha512-fixture",
        )
        val fields = npmMetadataFields(metadata)
        assertEquals(NpmMetadataField.values().toList(), fields.map { it.first })
        assertEquals(listOf("fixture", "1.0.0", "<script>fixture</script>", "MIT", "javascript:fixture", "deprecated", "sha512-fixture"),
            fields.map { it.second })
        assertTrue(npmMetadataFields(metadata.copy(name = null, version = null, description = null,
            license = null, repository = null, deprecated = null, integrity = null)).all { it.second == null })
    }

    @Test fun displayTextBoundsLengthRemovesControlsAndPreservesSurrogateBoundary() {
        assertNull(npmDisplayText(null))
        assertNull(npmDisplayText(" \n "))
        assertEquals("safetext", npmDisplayText("safe\u0000\u202etext\u2066"))
        assertEquals("x".repeat(2000) + "…", npmDisplayText("x".repeat(3000)))
        assertEquals("x".repeat(1999) + "…", npmDisplayText("x".repeat(1999) + "\uD83D\uDE00"))
    }

    companion object {
        private fun metadata(name: String = "fixture", version: String = "1.0.0") =
            NpmPackageMetadata(name, version, "Description", "MIT", "https://example.invalid/repo", null, "sha512-fixture")
    }
}
