package com.aharou.feature.plugin

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.net.AppProxy
import com.aharou.feature.plugin.presentation.RevericMarketViewModel
import com.aharou.feature.plugin.domain.market.RevericCatalog
import com.aharou.feature.plugin.domain.market.RevericFailure
import com.aharou.feature.plugin.domain.market.RevericMarketException
import com.aharou.feature.plugin.domain.market.RevericMarketRepository
import com.aharou.feature.plugin.domain.market.RevericVersion
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RevericMarketRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = "synthetic ZIP fixture; never a real plugin".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val version get() = RevericVersion("1.0.0", "^1", listOf("android"),
        "https://downloads.reveric.app/plugins/fixture/v1.0.0/fixture.zip", "https://example.org/fallback.zip",
        bytes.size.toLong(), hash, "https://example.org/release", "fixture-commit")
    private val fixture get() = """{"schemaVersion":1,"plugins":[{"id":"fixture.plugin",
        "name":{"zh-CN":"夹具名称","en":"Fixture"},"description":{"en":"Fixture description"},
        "author":"Fixture author","repository":"https://example.org/repository",
        "versions":[{"version":"1.0.0","pluginApiVersion":"^1","platforms":["android"],
        "url":"${version.url}","fallbackUrl":"${version.fallbackUrl}","size":${bytes.size},
        "sha256":"$hash","releaseUrl":"${version.releaseUrl}","sourceCommit":"fixture-commit"}]}]}"""

    private fun expect(reason: RevericFailure, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (failure: RevericMarketException) { assertEquals(reason, failure.reason) }
    }

    @Test fun parsesLocalizedNamesAndRevericCompatibilityWithoutHostCapabilities() {
        val entry = RevericCatalog.parse(fixture).single()
        assertEquals("夹具名称", entry.title("zh-CN"))
        assertEquals("Fixture description", entry.summary("zh-CN"))
        assertEquals(version, entry.versions.single())
        assertEquals("Fixture author", entry.author)
        assertEquals("https://example.org/repository", entry.repository)
    }

    @Test fun rejectsUnknownSchemaAndNonNumericSchema() {
        expect(RevericFailure.UNKNOWN_SCHEMA) { RevericCatalog.parse(fixture.replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
        expect(RevericFailure.UNKNOWN_SCHEMA) { RevericCatalog.parse(fixture.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")) }
    }

    @Test fun rejectsMalformedAndMissingOrWrongTypeFields() {
        listOf("{", "[]", fixture.replace("\"author\":\"Fixture author\",", ""),
            fixture.replace("\"platforms\":[\"android\"]", "\"platforms\":[1]"),
            fixture.replace("\"size\":${bytes.size}", "\"size\":\"${bytes.size}\""),
            fixture.replace(hash, "missing-hash")).forEach { invalid ->
            expect(RevericFailure.INVALID_CATALOG) { RevericCatalog.parse(invalid) }
        }
    }

    @Test fun limitsDepthBeforeParsingAndCatalogBytes() {
        fun catalogAtDepth(depth: Int): String = fixture.dropLast(1) + ",\"unknown\":" +
            "[".repeat(depth - 1) + "0" + "]".repeat(depth - 1) + "}"
        assertEquals(1, RevericCatalog.parse(catalogAtDepth(128)).size)
        expect(RevericFailure.INVALID_CATALOG) { RevericCatalog.parse(catalogAtDepth(129)) }
        expect(RevericFailure.TOO_LARGE) { RevericCatalog.parse(" ".repeat(RevericCatalog.MAX_CATALOG.toInt() + 1)) }
        assertEquals(1, RevericCatalog.parse(fixture.replace("Fixture description", "[braces { inside text}]")).size)
    }

    @Test fun rejectsForeignHttpCredentialsTraversalAndOversizedDownloads() {
        listOf("http://downloads.reveric.app/plugins/fixture.zip", "https://example.org/plugins/fixture.zip",
            "https://downloads.reveric.app.evil.test/plugins/fixture.zip",
            "https://user:pass@downloads.reveric.app/plugins/fixture.zip",
            "https://downloads.reveric.app/plugins/../fixture.zip",
            "https://downloads.reveric.app/plugins/%2e%2e/fixture.zip",
            "https://downloads.reveric.app/plugins/fixture.zip?token=fixture").forEach { url ->
            assertFalse(url, RevericCatalog.canDownload(version.copy(url = url)))
        }
        expect(RevericFailure.TOO_LARGE) { RevericCatalog.checkDownload(version.copy(size = RevericCatalog.MAX_DOWNLOAD + 1)) }
        assertTrue(RevericCatalog.canDownload(version))
    }

    @Test fun verifiesHashAndExactSizeAndStopsBeforeExcessBytesAreWritten() = runBlocking {
        val output = ByteArrayOutputStream()
        RevericCatalog.copyVerified(ByteArrayInputStream(bytes), output, version)
        assertArrayEquals(bytes, output.toByteArray())
        expect(RevericFailure.HASH_MISMATCH) { runBlocking {
            RevericCatalog.copyVerified(ByteArrayInputStream(bytes), ByteArrayOutputStream(), version.copy(sha256 = "0".repeat(64)))
        } }
        expect(RevericFailure.SIZE_MISMATCH) { runBlocking {
            RevericCatalog.copyVerified(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), ByteArrayOutputStream(), version)
        } }
        val excessOutput = ByteArrayOutputStream()
        expect(RevericFailure.TOO_LARGE) { runBlocking {
            RevericCatalog.copyVerified(ByteArrayInputStream(bytes + byteArrayOf(1)), excessOutput, version)
        } }
        assertTrue(excessOutput.size() <= bytes.size)
    }

    @Test fun hardDownloadLimitUsesStreamingCounts() {
        var supplied = 0L
        val stream = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                supplied += length
                return length
            }
        }
        expect(RevericFailure.TOO_LARGE) { runBlocking {
            RevericCatalog.copyVerified(stream, object : java.io.OutputStream() { override fun write(value: Int) = Unit
                override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit },
                version.copy(size = RevericCatalog.MAX_DOWNLOAD))
        } }
        assertEquals(RevericCatalog.MAX_DOWNLOAD + 1, supplied)
    }

    @Test fun allStreamingLimitsReadAtMostOneByteBeyondRemainingBudget() = runBlocking {
        for (limit in listOf(0L, 7L, bytes.size.toLong(), RevericCatalog.MAX_CATALOG)) {
            var supplied = 0L
            val input = object : InputStream() {
                override fun read(): Int = error("Bulk read required")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    assertTrue(length.toLong() <= limit - supplied + 1)
                    supplied += length
                    return length
                }
            }
            val output = object : java.io.OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
            }
            expect(RevericFailure.TOO_LARGE) { runBlocking { RevericCatalog.copyBounded(input, output, limit) } }
            assertEquals(limit + 1, supplied)
        }
    }

    @Test fun productionClientDisablesBothRedirectModesAndOriginCredentials() {
        val production = RevericMarketRepository.newClient(
            OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
        )
        assertFalse(production.followRedirects)
        assertFalse(production.followSslRedirects)
        assertSame(AppProxy.selector, production.proxySelector)
        assertSame(AppProxy.okHttpAuthenticator, production.proxyAuthenticator)
        assertSame(okhttp3.Authenticator.NONE, production.authenticator)
        assertSame(okhttp3.CookieJar.NO_COOKIES, production.cookieJar)
    }

    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val client = mockk<OkHttpClient>()
    private val uri = mockk<Uri>()

    private fun repository(body: ByteArray = bytes, status: Int = 200): RevericMarketRepository {
        every { context.cacheDir } returns temp.root
        every { context.contentResolver } returns resolver
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            mockk<Call>().also { call ->
                every { call.cancel() } returns Unit
                every { call.execute() } returns Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(status).message("fixture").body(body.toResponseBody()).build()
            }
        }
        return RevericMarketRepository(context, client)
    }

    @Test fun cancelledSelectionMakesNoRequestAndOpensNoDocument() = runBlocking {
        assertFalse(repository().save(version, null))
        verify(exactly = 0) { client.newCall(any()) }
        verify(exactly = 0) { resolver.openOutputStream(any(), any()) }
    }

    @Test fun downloadOnlySavesVerifiedBytesAndDoesNotDependOnInstaller() = runBlocking {
        val output = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri, "wt") } returns output
        assertTrue(repository().save(version, uri))
        assertArrayEquals(bytes, output.toByteArray())
        verify(exactly = 1) { resolver.openOutputStream(uri, "wt") }
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
        assertTrue(RevericMarketRepository::class.java.declaredConstructors.all { constructor ->
            constructor.parameterTypes.none { it.simpleName.contains("PluginManager") }
        })
    }

    @Test fun invalidDownloadNeverTouchesDestinationAndCleansCache() {
        val repo = repository()
        expect(RevericFailure.HASH_MISMATCH) { runBlocking { repo.save(version.copy(sha256 = "0".repeat(64)), uri) } }
        verify(exactly = 0) { resolver.openOutputStream(any(), any()) }
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun redirectIsRejectedWithoutFallbackOrSecondRequest() {
        val repo = repository(status = 302)
        expect(RevericFailure.REDIRECT) { runBlocking { repo.save(version, uri) } }
        verify(exactly = 1) { client.newCall(any()) }
        verify(exactly = 0) { resolver.openOutputStream(any(), any()) }
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun documentOpenFailureCannotReportSuccessAndCleansCache() = runBlocking {
        every { resolver.openOutputStream(uri, "wt") } returns null
        expect(RevericFailure.DOCUMENT) { runBlocking { repository().save(version, uri) } }
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RevericSavedDownloadTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<RevericMarketRepository>()
    private val views = mutableListOf<RevericMarketViewModel>()
    private val version = RevericVersion("1.0", "^1", listOf("android"),
        "https://downloads.reveric.app/plugins/fixture/fixture.zip", "https://example.org/fallback.zip",
        10, "a".repeat(64), "https://example.org/release", "fixture-commit")
    private val entry = com.aharou.feature.plugin.domain.market.RevericEntry("fixture", mapOf("en" to "Fixture"),
        mapOf("en" to "Description"), "Author", "https://example.org/repo", listOf(version))
    private val uri = mockk<Uri>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.load() } returns listOf(entry)
        coEvery { repository.save(any(), any()) } returns true
    }

    @After fun teardown() {
        views.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun view(handle: SavedStateHandle = SavedStateHandle()) =
        RevericMarketViewModel(repository, handle).also(views::add)

    private fun select(handle: SavedStateHandle): RevericMarketViewModel {
        val vm = view(handle)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.chooseDownload(entry, version))
        return vm
    }

    private fun snapshot(handle: SavedStateHandle): SavedStateHandle = SavedStateHandle(mapOf(
        RevericMarketViewModel.PENDING_KEY to ArrayList(handle.get<ArrayList<String>>(RevericMarketViewModel.PENDING_KEY)!!)))

    @Test fun recreatedSelectionReloadsCurrentMetadataAndSavesOnce() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val original = select(handle)
        val token = original.state.value.requestToken
        val restoredHandle = snapshot(handle)
        val current = version.copy(fallbackUrl = "https://example.org/current-fallback.zip",
            releaseUrl = "https://example.org/current-release", platforms = listOf("current-platform"))
        coEvery { repository.load() } returns listOf(entry.copy(versions = listOf(current)))
        val restored = view(restoredHandle)
        assertTrue(restored.state.value.choosing)
        assertEquals(token, restored.state.value.requestToken)
        restored.documentChosen(token, uri)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.save(current, uri) }
        coVerify(exactly = 0) { repository.save(version, uri) }
        coVerify(exactly = 2) { repository.load() }
        assertEquals(R.string.reveric_saved, restored.state.value.notice)
        assertFalse(restored.state.value.busy)
        assertTrue(restored.state.value.loaded)
        assertTrue(restored.state.value.loadAttempted)
        assertFalse(restoredHandle.contains(RevericMarketViewModel.PENDING_KEY))
        restored.documentChosen(token, uri)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.save(current, uri) }
        coVerify(exactly = 0) { repository.save(version, uri) }
        coVerify(exactly = 2) { repository.load() }
        assertEquals(R.string.reveric_selection_lost, restored.state.value.notice)
    }

    @Test fun recreatedSelectionCancellationNeverDownloads() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        select(handle)
        val restored = view(snapshot(handle))
        restored.documentChosen(restored.state.value.requestToken, null)
        advanceUntilIdle()
        assertFalse(restored.state.value.busy)
        assertEquals(R.string.reveric_cancelled, restored.state.value.notice)
        assertFalse(restored.state.value.loaded)
        assertFalse(restored.state.value.loadAttempted)
        coVerify(exactly = 1) { repository.load() }
        restored.load()
        advanceUntilIdle()
        assertTrue(restored.state.value.loaded)
        assertTrue(restored.state.value.loadAttempted)
        coVerify(exactly = 2) { repository.load() }
        coVerify(exactly = 0) { repository.save(any(), any()) }
    }

    @Test fun firstCatalogFailureRecordsAttemptAndAllowsManualRefresh() = runTest(dispatcher) {
        coEvery { repository.load() } throws IllegalStateException("fixture catalog unavailable")
        val vm = view()
        assertFalse(vm.state.value.loadAttempted)
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value.loadAttempted)
        assertFalse(vm.state.value.loaded)
        assertFalse(vm.state.value.loading)
        coVerify(exactly = 1) { repository.load() }
        coEvery { repository.load() } returns listOf(entry)
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value.loaded)
        assertEquals(listOf(entry), vm.state.value.entries)
        coVerify(exactly = 2) { repository.load() }
    }

    @Test fun invalidRestoredUrlIsRejectedBeforeAnyDownload() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        select(handle)
        val parameters = ArrayList(handle.get<ArrayList<String>>(RevericMarketViewModel.PENDING_KEY)!!)
        parameters[4] = "https://example.org/foreign.zip"
        val restored = view(SavedStateHandle(mapOf(RevericMarketViewModel.PENDING_KEY to parameters)))
        restored.documentChosen(restored.state.value.requestToken, uri)
        advanceUntilIdle()
        assertEquals(R.string.reveric_selection_lost, restored.state.value.notice)
        assertFalse(restored.state.value.busy)
        coVerify(exactly = 0) { repository.save(any(), any()) }
    }

    @Test fun savedStateContainsOnlySevenBoundedFieldsDespiteHugeDisplayMetadata() = runTest(dispatcher) {
        val huge = "x".repeat(100_000)
        val largeVersion = version.copy(fallbackUrl = huge, releaseUrl = huge, platforms = listOf(huge),
            pluginApiVersion = huge, sourceCommit = huge)
        val largeEntry = entry.copy(versions = listOf(largeVersion), name = mapOf("en" to huge))
        coEvery { repository.load() } returns listOf(largeEntry)
        val handle = SavedStateHandle()
        val vm = view(handle)
        vm.load()
        advanceUntilIdle()
        assertNotNull(vm.chooseDownload(largeEntry, largeVersion))
        val fields = handle.get<ArrayList<String>>(RevericMarketViewModel.PENDING_KEY)!!
        assertEquals(7, fields.size)
        assertEquals(listOf(largeEntry.id, version.version, version.url, version.size.toString(), version.sha256),
            fields.drop(2))
        assertTrue(fields.zip(listOf(36, 11, 128, 128, 2048, 8, 64)).all { (value, cap) -> value.length <= cap })
        assertFalse(fields.any { it == huge })
        vm.documentChosen(vm.state.value.requestToken, null)
    }

    @Test fun oversizedSavedFieldsAndLegacyFullVersionAreRejected() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        select(handle)
        val original = handle.get<ArrayList<String>>(RevericMarketViewModel.PENDING_KEY)!!
        for ((index, cap) in listOf(36, 11, 128, 128, 2048, 8, 64).withIndex()) {
            val fields = ArrayList(original).apply { this[index] = "x".repeat(cap + 1) }
            val restoredHandle = SavedStateHandle(mapOf(RevericMarketViewModel.PENDING_KEY to fields))
            val restored = view(restoredHandle)
            assertEquals(R.string.reveric_selection_lost, restored.state.value.notice)
            assertFalse(restoredHandle.contains(RevericMarketViewModel.PENDING_KEY))
        }
        val legacy = ArrayList(original).apply { repeat(4) { add("legacy-display-field") } }
        assertEquals(R.string.reveric_selection_lost,
            view(SavedStateHandle(mapOf(RevericMarketViewModel.PENDING_KEY to legacy))).state.value.notice)
        coVerify(exactly = 0) { repository.save(any(), any()) }
    }

    @Test fun freshOversizedIdentifierIsNeverPersisted() = runTest(dispatcher) {
        val oversized = entry.copy(id = "x".repeat(129))
        coEvery { repository.load() } returns listOf(oversized)
        val handle = SavedStateHandle()
        val vm = view(handle)
        vm.load()
        advanceUntilIdle()
        assertNull(vm.chooseDownload(oversized, version))
        assertFalse(handle.contains(RevericMarketViewModel.PENDING_KEY))
        assertEquals(R.string.reveric_selection_lost, vm.state.value.notice)
    }

    @Test fun restoredMissingEntryVersionOrChangedDownloadFieldsNeverSave() = runTest(dispatcher) {
        val changedCatalogs = listOf(emptyList(), listOf(entry.copy(id = "other-plugin")),
            listOf(entry.copy(versions = listOf(version.copy(version = "2.0")))),
            listOf(entry.copy(versions = listOf(version.copy(url = "https://downloads.reveric.app/plugins/changed.zip")))),
            listOf(entry.copy(versions = listOf(version.copy(size = version.size + 1)))),
            listOf(entry.copy(versions = listOf(version.copy(sha256 = "b".repeat(64))))))
        for (catalog in changedCatalogs) {
            coEvery { repository.load() } returns listOf(entry)
            val handle = SavedStateHandle()
            select(handle)
            coEvery { repository.load() } returns catalog
            val restored = view(snapshot(handle))
            restored.documentChosen(restored.state.value.requestToken, uri)
            advanceUntilIdle()
            assertFalse(restored.state.value.busy)
            assertEquals(R.string.reveric_selection_lost, restored.state.value.notice)
            assertTrue(restored.state.value.loaded)
            assertTrue(restored.state.value.loadAttempted)
            assertEquals(catalog, restored.state.value.entries)
        }
        coVerify(exactly = changedCatalogs.size * 2) { repository.load() }
        coVerify(exactly = 0) { repository.save(any(), any()) }
    }

    @Test fun restoredCatalogReadFailureIsExplicitAndNeverSaves() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        select(handle)
        coEvery { repository.load() } throws IllegalStateException("fixture catalog unavailable")
        val restored = view(snapshot(handle))
        restored.documentChosen(restored.state.value.requestToken, uri)
        advanceUntilIdle()
        assertEquals(R.string.reveric_selection_lost, restored.state.value.notice)
        assertFalse(restored.state.value.busy)
        assertFalse(restored.state.value.loaded)
        assertTrue(restored.state.value.loadAttempted)
        coVerify(exactly = 2) { repository.load() }
        coEvery { repository.load() } returns listOf(entry)
        restored.load()
        advanceUntilIdle()
        assertTrue(restored.state.value.loaded)
        assertEquals(listOf(entry), restored.state.value.entries)
        coVerify(exactly = 3) { repository.load() }
        coVerify(exactly = 0) { repository.save(any(), any()) }
    }

    @Test fun staleSelectionTokenCannotConsumeNewSelection() = runTest(dispatcher) {
        val vm = select(SavedStateHandle())
        val oldToken = vm.state.value.requestToken
        vm.documentChosen(oldToken, null)
        assertNotNull(vm.chooseDownload(entry, version))
        val newToken = vm.state.value.requestToken
        assertNotEquals(oldToken, newToken)
        vm.documentChosen(oldToken, uri)
        advanceUntilIdle()
        assertTrue(vm.state.value.choosing)
        assertEquals(newToken, vm.state.value.requestToken)
        coVerify(exactly = 0) { repository.save(any(), any()) }
        vm.documentChosen(newToken, null)
    }

    @Test fun cancelledLateResultCannotClearNewRequestState() = runTest(dispatcher) {
        val gate = CompletableDeferred<Boolean>()
        coEvery { repository.save(any(), any()) } coAnswers { withContext(NonCancellable) { gate.await() } }
        val vm = select(SavedStateHandle())
        vm.documentChosen(vm.state.value.requestToken, uri)
        runCurrent()
        vm.cancel()
        assertEquals(R.string.reveric_cancelled, vm.state.value.notice)
        assertNotNull(vm.chooseDownload(entry, version))
        val newToken = vm.state.value.requestToken
        gate.complete(true)
        advanceUntilIdle()
        assertTrue(vm.state.value.choosing)
        assertTrue(vm.state.value.busy)
        assertEquals(newToken, vm.state.value.requestToken)
        assertNull(vm.state.value.notice)
        vm.documentChosen(newToken, null)
    }

    @Test fun interruptedDownloadCannotRestoreSuccessOrAutomaticallyRetry() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        select(handle)
        val parameters = ArrayList(handle.get<ArrayList<String>>(RevericMarketViewModel.PENDING_KEY)!!)
        parameters[1] = "downloading"
        val restored = view(SavedStateHandle(mapOf(RevericMarketViewModel.PENDING_KEY to parameters)))
        assertEquals(R.string.reveric_selection_lost, restored.state.value.notice)
        assertFalse(restored.state.value.busy)
        restored.load()
        advanceUntilIdle()
        assertEquals(R.string.reveric_selection_lost, restored.state.value.notice)
        coVerify(exactly = 0) { repository.save(any(), any()) }
    }
}
