package com.aharou.feature.agent.domain.skill.market

import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SkillMarketRepositoryTest {
    private var conscryptProvider: Provider? = null

    @Before
    fun removeGlobalConscryptProviderForMockHttp() {
        conscryptProvider = Security.getProviders().firstOrNull {
            it.name.equals("Conscrypt", ignoreCase = true)
        }
        conscryptProvider?.let { Security.removeProvider(it.name) }
    }

    @After
    fun restoreGlobalConscryptProvider() {
        conscryptProvider?.let { Security.insertProviderAt(it, 1) }
        conscryptProvider = null
    }

    @Test
    fun clawHubSearchParsesOfficialResultsAndEncodesQuery() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"owner/pdf","displayName":"PDF","summary":"Read files","version":"1.2.0","updatedAt":12,"ownerHandle":"owner"}]}"""))
        val client = ClawHubClient(OkHttpClient(), server.url("/api/v1").toString())

        val found = client.search("pdf & docs")

        assertEquals("owner/pdf", found?.single()?.slug)
        assertEquals("PDF", found?.single()?.displayName)
        assertEquals("owner", found?.single()?.owner)
        assertEquals("/api/v1/search?q=pdf+%26+docs", server.takeRequest().path)
    }

    @Test
    fun clawHubDetailReadsVersionOwnerAndLeavesMissingLicenseUnknown() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"skill":{"slug":"owner/tool","displayName":"Tool","summary":"Summary","tags":["tag"],"updatedAt":9},"latestVersion":{"version":"2.0","changelog":"Changes"},"owner":{"handle":"owner"},"moderation":null}"""))
        val client = ClawHubClient(OkHttpClient(), server.url("/api/v1").toString())

        val detail = client.detail("owner/tool")

        assertEquals("2.0", detail?.skill?.version)
        assertEquals("owner", detail?.skill?.owner)
        assertEquals("/api/v1/skills/owner%2Ftool", server.takeRequest().path)
    }

    @Test
    fun clawHubSearchMissingOptionalFieldsRemainUnknown() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"owner/minimal"}]}"""))
        val client = ClawHubClient(OkHttpClient(), server.url("/api/v1").toString())

        val item = client.search("minimal")?.single()

        assertNotNull(item)
        assertEquals("", item?.version)
        assertEquals("", item?.owner)
    }

    @Test
    fun clawHubScanPreservesPendingSuspiciousAndMalwareStates() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"moderation":{"isPendingScan":true,"isSuspicious":true,"isMalwareBlocked":false,"verdict":"suspicious","reasonCodes":["review"]}}"""))
        val client = ClawHubClient(OkHttpClient(), server.url("/api/v1").toString())

        val result = client.scan("owner/skill", "1.0")

        assertTrue(result?.pending == true)
        assertTrue(result?.suspicious == true)
        assertFalse(result?.malwareBlocked == true)
        assertEquals("/api/v1/skills/owner%2Fskill/scan?version=1.0", server.takeRequest().path)
    }

    @Test
    fun clawHubArchiveUrlAllowlistRequiresTrustedHttpsOriginWithoutCredentials() = MockWebServer().use { server ->
        val client = ClawHubClient(OkHttpClient(), server.url("/api/v1").toString())

        assertTrue(client.isAllowedApiUrl("http://localhost:${server.port}/api/v1/search?q=x"))
        assertFalse(client.isAllowedApiUrl("http://127.0.0.1:${server.port}/api/v1/search?q=x"))
        assertTrue(client.isAllowedArchiveUrl("https://github.com/owner/repo/archive.zip"))
        assertTrue(client.isAllowedArchiveUrl("https://api.github.com/repos/owner/repo/zipball/main"))
        assertFalse(client.isAllowedArchiveUrl("http://github.com/owner/repo/archive.zip"))
        assertFalse(client.isAllowedArchiveUrl("https://github.com:444/owner/repo/archive.zip"))
        assertFalse(client.isAllowedArchiveUrl("https://user@github.com/owner/repo/archive.zip"))
        assertFalse(client.isAllowedArchiveUrl("https://github.com.attacker.invalid/repo/archive.zip"))
    }

    @Test
    fun clawHubApiRejectsCrossHostRedirectBeforeFollowingIt() = MockWebServer().use { server ->
        val baseUrl = server.url("/api/v1").newBuilder().host("localhost").build().toString()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${server.port}/elsewhere"))
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val client = ClawHubClient(OkHttpClient(), baseUrl)

        assertNull(client.search("test"))
        assertEquals(1, server.requestCount)
        assertEquals("/api/v1/search?q=test", server.takeRequest().path)
    }

    @Test
    fun clawHubApiAllowsSameOriginRedirect() = MockWebServer().use { server ->
        val baseUrl = server.url("/api/v1").newBuilder().host("localhost").build().toString()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/v1/search?q=test"))
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val client = ClawHubClient(OkHttpClient(), baseUrl)

        assertTrue(client.search("test")?.isEmpty() == true)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun clawHubDownloadRejectsRedirectToUntrustedHost() = MockWebServer().use { server ->
        val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)
        val baseUrl = server.url("/api/v1").newBuilder().host("localhost").build().toString()
        val archiveUrl = server.url("/redirect").newBuilder().host("localhost").build().toString()
        val handoff = """{"sourceRef":"public-github","repo":"owner/repo","commit":"abc","path":"skill","contentHash":"${sha256(zip)}","archiveUrl":"$archiveUrl"}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(handoff))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${server.port}/artifact"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(okio.Buffer().write(zip)))
        val client = ClawHubClient(OkHttpClient(), baseUrl)

        assertNull(client.download("owner/skill", "1.0"))
        assertEquals(2, server.requestCount)
        assertEquals("/api/v1/download?slug=owner%2Fskill&version=1.0", server.takeRequest().path)
        assertEquals("/redirect", server.takeRequest().path)
    }

    @Test
    fun clawHubDownloadRejectsArchiveUserInfoUnexpectedPortAndHttpGithub() = MockWebServer().use { server ->
        val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)
        val hash = sha256(zip)
        fun handoff(url: String) = """{"sourceRef":"public-github","repo":"owner/repo","commit":"abc","path":"skill","contentHash":"$hash","archiveUrl":"$url"}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(handoff("http://user@localhost:${server.port}/artifact")))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(handoff("http://localhost:${server.port + 1}/artifact")))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(handoff("http://github.com/owner/repo/archive.zip")))
        val client = ClawHubClient(OkHttpClient(), server.url("/api/v1").newBuilder().host("localhost").build().toString())

        repeat(3) { assertNull(client.download("owner/skill", "1.0")) }

        assertEquals(3, server.requestCount)
    }

    @Test
    fun clawHubDownloadAllowsRedirectWithinTrustedOrigin() = MockWebServer().use { server ->
        val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)
        val hash = sha256(zip)
        val archiveUrl = server.url("/redirect").toString()
        val handoff = """{"sourceRef":"public-github","repo":"owner/repo","commit":"abc","path":"skill","contentHash":"$hash","archiveUrl":"$archiveUrl"}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(handoff))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/artifact"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(okio.Buffer().write(zip)))
        val client = ClawHubClient(OkHttpClient(), server.url("/api/v1").toString())

        val verified = client.download("owner/skill", "1.0")

        assertNotNull(verified)
        assertEquals(hash, verified?.contentHash)
        assertEquals("/api/v1/download?slug=owner%2Fskill&version=1.0", server.takeRequest().path)
        assertEquals("/redirect", server.takeRequest().path)
        assertEquals("/artifact", server.takeRequest().path)
    }

    @Test
    fun clawHubInstallRejectsHiddenRemovedAndMalwareStatesButAllowsUnknownReviewStates() {
        fun state(
            hidden: Boolean = false,
            removed: Boolean = false,
            malware: Boolean = false,
            suspicious: Boolean = false,
            verdict: String = ""
        ) = ClawHubClient.ClawHubModeration(
            hidden = hidden,
            removed = removed,
            malwareBlocked = malware,
            suspicious = suspicious,
            verdict = verdict
        )

        assertFalse(canInstallClawHubModeration(state(hidden = true)))
        assertFalse(canInstallClawHubModeration(state(removed = true)))
        assertFalse(canInstallClawHubModeration(state(malware = true)))
        assertFalse(canInstallClawHubModeration(state(verdict = "malicious")))
        assertTrue(canInstallClawHubModeration(null))
        assertTrue(canInstallClawHubModeration(state(verdict = "clean")))
        assertTrue(canInstallClawHubModeration(state(suspicious = true, verdict = "suspicious")))
    }

    @Test
    fun clawHubScanBindsSecurityHashOnlyWhenVersionExplicitlyMatches() = MockWebServer().use { server ->
        val hash = "a".repeat(64)
        server.enqueue(MockResponse().setBody("""{"moderation":{"matchesRequestedVersion":true},"security":{"sha256hash":"$hash"}}"""))
        server.enqueue(MockResponse().setBody("""{"moderation":{"matchesRequestedVersion":false},"security":{"sha256hash":"$hash"}}"""))
        server.enqueue(MockResponse().setBody("""{"moderation":null,"security":{"sha256hash":"$hash"}}"""))
        val client = ClawHubClient(OkHttpClient(), server.url("/api/v1").toString())

        assertEquals(hash, client.scan("owner/skill", "1.2.0")?.securitySha256)
        assertNull(client.scan("owner/skill", "1.2.0")?.securitySha256)
        assertNull(client.scan("owner/skill", "1.2.0")?.securitySha256)
        repeat(3) { assertEquals("/api/v1/skills/owner%2Fskill/scan?version=1.2.0", server.takeRequest().path) }
    }

    @Test
    fun clawHubHostedZipRequiresAndMatchesScanHash() = MockWebServer().use { server ->
        val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)
        val hash = sha256(zip)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(okio.Buffer().write(zip)))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(okio.Buffer().write(zip)))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(okio.Buffer().write(zip + 4)))
        val client = ClawHubClient(OkHttpClient(), server.url("/api/v1").toString())

        assertNull(client.download("owner/skill", "1.0"))
        assertNotNull(client.download("owner/skill", "1.0", hash))
        assertNull(client.download("owner/skill", "1.0", hash))
        repeat(3) { assertEquals("/api/v1/download?slug=owner%2Fskill&version=1.0", server.takeRequest().path) }
    }

    @Test
    fun selectedClawHubVersionWinsOverDetailLatestVersion() {
        assertEquals("1.2.0", effectiveClawHubVersion("1.2.0", "2.0.0"))
        assertEquals("2.0.0", effectiveClawHubVersion("", "2.0.0"))
    }

    @Test
    fun matchingVersionScanCannotClearKnownDetailBlock() {
        val detail = ClawHubClient.ClawHubModeration(hidden = true)
        val scan = ClawHubClient.ClawHubModeration(matchesRequestedVersion = true, verdict = "clean")

        assertFalse(canInstallClawHubModeration(mergeClawHubModeration(detail, scan)))
    }

    @Test
    fun clawHubDownloadRequiresAndVerifiesContentHash() = MockWebServer().use { server ->
        val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3)
        val hash = sha256(zip)
        val archiveUrl = server.url("/artifact").toString()
        fun handoff(contentHash: String) = """{"sourceRef":"public-github","repo":"owner/repo","commit":"abc","path":"skill","contentHash":"$contentHash","archiveUrl":"$archiveUrl"}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(handoff(hash)))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(okio.Buffer().write(zip)))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(handoff("0".repeat(64))))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(okio.Buffer().write(zip)))
        val client = ClawHubClient(OkHttpClient(), server.url("/api/v1").toString())

        val verified = client.download("owner/skill", "1.0")
        val invalid = client.download("owner/skill", "1.0")

        assertNotNull(verified)
        assertEquals(hash, verified?.contentHash)
        assertNull(invalid)
        assertEquals("/api/v1/download?slug=owner%2Fskill&version=1.0", server.takeRequest().path)
        assertEquals("/artifact", server.takeRequest().path)
        assertEquals("/api/v1/download?slug=owner%2Fskill&version=1.0", server.takeRequest().path)
        assertEquals("/artifact", server.takeRequest().path)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
