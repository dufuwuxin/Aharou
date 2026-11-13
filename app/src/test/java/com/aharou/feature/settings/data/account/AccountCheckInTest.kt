package com.aharou.feature.settings.data.account

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AccountCheckInTest {
    private class Vault : AccountCredentialVault {
        private val values = mutableMapOf<String, String>()
        override fun read(id: String) = values[id]
        override fun write(id: String, value: String?) { if (value == null) values.remove(id) else values[id] = value }
    }
    private fun manager(server: MockWebServer): AccountProviderManager {
        val base = server.url("/").toString().trimEnd('/')
        return AccountProviderManager(Vault(), OkHttpClient.Builder().followRedirects(false).build(),
            AccountEndpoints(workbuddyBilling = base, raccoon = base, traeAccount = base))
    }
    @Test fun personalCheckInDoesNotInventRewardAndDuplicateIsNotNewCredit() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            manager.importCredentials("id", AccountProviderKind.WORKBUDDY, """{"accessToken":"fixture"}""")
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}"""))
            assertEquals(AccountCheckInStatus.CLAIMED, manager.checkIn("id", AccountProviderKind.WORKBUDDY).status)
            val request = server.takeRequest()
            assertEquals("/v2/billing/meter/daily-checkin", request.path)
            assertEquals("POST", request.method)
            assertEquals("{}", request.body.readUtf8())
            server.enqueue(MockResponse().setBody("""{"code":1001,"message":"already checked in"}"""))
            val duplicate = manager.checkIn("id", AccountProviderKind.WORKBUDDY)
            assertEquals(AccountCheckInStatus.ALREADY_CHECKED, duplicate.status)
            assertNull(duplicate.awardedPoints)
            server.enqueue(MockResponse().setBody("""{"code":999,"message":"fixture-sensitive-error"}"""))
            try { manager.checkIn("id", AccountProviderKind.WORKBUDDY); fail("Business errors must fail") }
            catch (error: AccountProviderException) { assertFalse(error.message.orEmpty().contains("fixture-sensitive-error")) }
        }
    }
    @Test fun alreadyInAnErrorMessageNeverMeansCheckInSucceeded() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            manager.importCredentials("id", AccountProviderKind.WORKBUDDY, """{"accessToken":"fixture"}""")
            for ((code, message) in listOf(12153 to "session already expired", 11140 to "already checked in", 999 to "account already exists")) {
                server.enqueue(MockResponse().setBody("""{"code":$code,"message":"$message"}"""))
                try { manager.checkIn("id", AccountProviderKind.WORKBUDDY); fail("Provider errors must not be check-in success") }
                catch (_: AccountProviderException) { }
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun internationalAndEnterpriseCheckInNeverContactsProvider() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            for (kind in listOf(AccountProviderKind.WORKBUDDY_GLOBAL, AccountProviderKind.TRAE_GLOBAL, AccountProviderKind.LOOMY)) {
                manager.importCredentials(kind.wireId, kind, """{"accessToken":"fixture"}""")
                assertEquals(AccountCheckInStatus.UNSUPPORTED, manager.checkIn(kind.wireId, kind).status)
            }
            manager.importCredentials("id", AccountProviderKind.WORKBUDDY, """{"accessToken":"fixture","enterpriseId":"enterprise"}""")
            assertEquals(AccountCheckInStatus.UNSUPPORTED, manager.checkIn("id", AccountProviderKind.WORKBUDDY).status)
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun raccoonUsesDesktopHeadersAndUnknownIsNotClaimed() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            manager.importCredentials("id", AccountProviderKind.RACCOON, """{"accessToken":"fixture"}""")
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"available_points":10}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"available_points":10}}"""))
            assertEquals(AccountCheckInStatus.UNKNOWN, manager.checkIn("id", AccountProviderKind.RACCOON).status)
            assertEquals("/api/web/points/v1/balance", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/web/office/v3/setting_info", request.path)
            assertEquals("desktop-windows", request.getHeader("X-Client-Platform"))
            assertEquals("v1.0.0", request.getHeader("X-Client-Version"))
        }
    }
    @Test fun traeAlreadyCheckedDoesNotClaimAgain() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            manager.importCredentials("id", AccountProviderKind.TRAE, """{"accessToken":"fixture"}""")
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"checked_in":true,"credits":100}}"""))
            val result = manager.checkIn("id", AccountProviderKind.TRAE)
            assertEquals(AccountCheckInStatus.ALREADY_CHECKED, result.status)
            assertNull(result.awardedPoints)
            assertEquals("/trae/api/v2/ug/checkin_credits/status", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun traeClaimIsConfirmedByStatusAndNeverReusesStatusCreditsAsReward() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            manager.importCredentials("id", AccountProviderKind.TRAE, """{"accessToken":"fixture"}""")
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"checked_in":false,"enable":true,"credits":900}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"checked_in":true,"credits":900}}"""))
            val result = manager.checkIn("id", AccountProviderKind.TRAE)
            assertEquals(AccountCheckInStatus.CLAIMED, result.status)
            assertNull(result.awardedPoints)
            assertEquals("/trae/api/v2/ug/checkin_credits/status", server.takeRequest().path)
            assertEquals("/trae/api/v2/ug/checkin_credits/claim", server.takeRequest().path)
            assertEquals("/trae/api/v2/ug/checkin_credits/status", server.takeRequest().path)
        }
    }

    @Test fun raccoonRequiresObservedBalanceIncreaseAndDoesNotInventAward() = runBlocking {
        MockWebServer().use { server ->
            val manager = manager(server)
            manager.importCredentials("id", AccountProviderKind.RACCOON, """{"accessToken":"fixture"}""")
            server.enqueue(MockResponse().setBody("""{"data":{"available_points":10}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"point_grant_popups":true}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"available_points":15}}"""))
            val result = manager.checkIn("id", AccountProviderKind.RACCOON)
            assertEquals(AccountCheckInStatus.CLAIMED, result.status)
            assertNull(result.awardedPoints)
        }
    }

}
