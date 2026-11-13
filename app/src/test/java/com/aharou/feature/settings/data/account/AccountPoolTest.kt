package com.aharou.feature.settings.data.account

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AccountPoolTest {
    private class Vault : AccountCredentialVault {
        val values = ConcurrentHashMap<String, String>()
        override fun read(id: String) = values[id]
        override fun write(id: String, value: String?) { if (value == null) values.remove(id) else values[id] = value }
    }
    private fun newServer() = MockWebServer().apply { start(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0) }
    private val kind = AccountProviderKind.RACCOON
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject
    private fun client() = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).followRedirects(false).retryOnConnectionFailure(false).build()
    private fun credential(user: String, token: String = "fixture-$user", service: AccountProviderKind = kind) =
        AccountCredential(service, token, null, null, "device-$user", user)
    private fun pool(vararg users: String): String = AccountPool(kind, users.map { PoolAccount(it, credential(it)) }).json().toString()
    private fun manager(server: MockWebServer, vault: Vault = Vault(), http: OkHttpClient = client()): AccountProviderManager =
        AccountProviderManager(vault, http, AccountEndpoints(raccoon = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')))
    private fun catalog(vararg models: String) = MockResponse().setBody(JsonObject().apply {
        add("data", JsonObject().apply { add("categories", JsonArray().apply { add(JsonObject().apply {
            addProperty("type", "chat"); add("models", JsonArray().apply { models.forEach { id -> add(JsonObject().apply { addProperty("name", id) }) } })
        }) }) })
    }.toString())
    private fun answer(text: String = "ok") = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"role":"assistant","content":"$text"}}]}""")
    private fun chat(manager: AccountProviderManager, model: String = "m", stream: Boolean = false): okhttp3.Response =
        OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).addInterceptor(manager.interceptor()).build().newCall(Request.Builder()
            .url(manager.baseUrl(kind, "id") + "/chat/completions")
            .post("""{"model":"$model","stream":$stream,"messages":[{"role":"user","content":"hello"}]}""".toRequestBody("application/json".toMediaType())).build()).execute()

    @Test fun legacyMigrationKeepsAllFieldsAndPoolRoundTripKeepsOrder() {
        val vault = Vault()
        val legacy = """{"kind":"raccoon","accessToken":"fixture-access","refreshToken":"fixture-refresh","expiresAt":4102444800000,"deviceId":"device-old","userId":"user-old","domain":"domain-old","enterpriseId":"tenant-old"}"""
        vault.write("id", legacy)
        val manager = AccountProviderManager(vault, client())
        assertEquals("legacy", manager.accounts("id", kind).single().accountId)
        val before = manager.credential("id", kind).json()
        // Export alone must not rewrite the old encrypted record.
        assertEquals(legacy, manager.exportCredentials("id"))
        manager.importCredentials("id", kind, manager.exportCredentials("id"))
        assertEquals(before, manager.credential("id", kind).json())
        assertEquals("2", json(manager.exportCredentials("id")).get("poolVersion").asString)
        manager.importCredentials("id", kind, pool("first", "second", "third"))
        val restored = AccountProviderManager(Vault(), client())
        restored.importCredentials("id", kind, manager.exportCredentials("id"))
        assertEquals(listOf("first", "second", "third"), restored.accounts("id", kind).map { it.accountId })
        assertEquals("fixture-first", restored.credential("id", kind).accessToken)
    }

    @Test fun stableRemoteIdentityUpdatesOriginalSlotButMissingIdentityAppends() {
        val initial = AccountPool(kind, listOf(PoolAccount("first", credential("one")), PoolAccount("second", credential("two"))))
        val updated = initial.append(credential("one", "fixture-new"))
        assertEquals(listOf("first", "second"), updated.accounts.map { it.accountId })
        assertEquals("fixture-new", updated.accounts[0].credential.accessToken)
        val unknown = AccountCredential(kind, "fixture-anonymous", null, null, "device-new", null)
        assertEquals(4, updated.append(unknown).append(unknown).accounts.size)
    }

    @Test fun rejectsMixedRegionAndUnknownVersionWithoutOverwritingExistingPool() {
        val manager = AccountProviderManager(Vault(), client())
        manager.importCredentials("id", kind, pool("first"))
        val before = manager.exportCredentials("id")
        val invalid = AccountPool(kind, listOf(PoolAccount("other", credential("other", service = AccountProviderKind.TRAE_GLOBAL)))).json()
        for (value in listOf(invalid, json(before).apply { addProperty("poolVersion", 999) })) {
            try { manager.importCredentials("id", kind, value.toString()); fail("Invalid pool must be rejected") }
            catch (_: AccountProviderException) { }
            assertEquals(before, manager.exportCredentials("id"))
        }
    }

    @Test fun threeCatalogsUnionInFirstSeenOrderAndPartialIsExplicit() {
        newServer().use { server ->
            server.enqueue(catalog("a", "shared")); server.enqueue(catalog("shared", "b")); server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
            val manager = manager(server)
            manager.importCredentials("id", kind, pool("first", "second", "third"))
            val result = manager.poolCatalog("id", kind)
            assertEquals(listOf("a", "shared", "b"), result.models.map { it.id })
            assertEquals("third", result.issues.single().accountId)
            assertEquals(3, server.requestCount)
            assertEquals("Bearer fixture-first", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer fixture-second", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer fixture-third", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun firstQuotaFailureUsesSecondAndNeverTouchesThird() {
        newServer().use { server ->
            server.enqueue(catalog("m")); server.enqueue(MockResponse().setResponseCode(402).setBody("{}"))
            server.enqueue(catalog("m")); server.enqueue(answer())
            val manager = manager(server)
            manager.importCredentials("id", kind, pool("first", "second", "third"))
            chat(manager).use { assertEquals(200, it.code) }
            assertEquals(4, server.requestCount)
            assertEquals(listOf("Bearer fixture-first", "Bearer fixture-first", "Bearer fixture-second", "Bearer fixture-second"),
                (1..4).map { server.takeRequest().getHeader("Authorization") })
        }
    }

    @Test fun onlyAccountsAdvertisingTheSelectedModelAreInvoked() {
        newServer().use { server ->
            server.enqueue(catalog("other")); server.enqueue(catalog("m")); server.enqueue(answer())
            val manager = manager(server)
            manager.importCredentials("id", kind, pool("first", "second", "third"))
            chat(manager).use { assertEquals(200, it.code) }
            val requests = (1..3).map { server.takeRequest() }
            assertEquals(1, requests.count { it.path!!.endsWith("/chat/completions") })
            assertEquals("Bearer fixture-second", requests.last().getHeader("Authorization"))
        }
    }

    @Test fun connectionEstablishmentFailureMayUseSecondAccount() {
        newServer().use { server ->
            server.enqueue(catalog("m")); server.enqueue(catalog("m")); server.enqueue(answer())
            val http = client().newBuilder().addInterceptor { chain ->
                if (chain.request().url.encodedPath.endsWith("/chat/completions") && chain.request().header("Authorization") == "Bearer fixture-first") throw java.net.ConnectException("fixture connection failure")
                chain.proceed(chain.request())
            }.build()
            val manager = manager(server, http = http)
            manager.importCredentials("id", kind, pool("first", "second"))
            chat(manager).use { assertEquals(200, it.code) }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun client400DoesNotFailOver() {
        newServer().use { server ->
            server.enqueue(catalog("m")); server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))
            val manager = manager(server)
            manager.importCredentials("id", kind, pool("first", "second"))
            chat(manager).use { assertEquals(400, it.code) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun onceStreamingOutputStartsNoOtherAccountIsReplayed() {
        newServer().use { server ->
            server.enqueue(catalog("m"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\ndata: {\"code\":14018}\n\n"))
            val manager = manager(server)
            manager.importCredentials("id", kind, pool("first", "second"))
            chat(manager, stream = true).use { response ->
                assertTrue(response.body!!.source().readUtf8Line()!!.contains("partial"))
                try { response.body!!.string(); fail("Late stream error must surface") } catch (_: IOException) { }
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun removingOneSlotRejectsLateReadsAndKeepsTheOtherAccount() {
        newServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") == "Bearer fixture-first") {
                        entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS))
                    }
                    return catalog("m")
                }
            }
            val manager = manager(server)
            manager.importCredentials("id", kind, pool("first", "second"))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val late = executor.submit<Boolean> {
                    try { manager.withPoolAccount("id", kind, "first") { manager.catalogForAccount("id", kind) }; false }
                    catch (_: IOException) { true }
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                manager.removeAccount("id", kind, "first")
                release.countDown()
                assertTrue(late.get(3, TimeUnit.SECONDS))
                assertEquals(listOf("second"), manager.accounts("id", kind).map { it.accountId })
                assertEquals("m", manager.withPoolAccount("id", kind, "second") { manager.catalogForAccount("id", kind) }.single().id)
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun appendedLoomySessionDoesNotReplaceExistingAccount() = runBlocking {
        newServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""{"code":"000000","data":[{"id":"fixture-model"}]}""")) }
            val manager = AccountProviderManager(Vault(), client(), AccountEndpoints(loomy = server.url("/").toString().trimEnd('/')))
            manager.importLoomySession("id", "fixture-session-one")
            manager.importLoomySession("id", "fixture-session-two")
            assertEquals(2, manager.accounts("id", AccountProviderKind.LOOMY).size)
        }
    }

    @Test fun consecutiveWorkBuddyLoginsAppendAndKnownUserKeepsPosition() = runBlocking {
        newServer().use { server ->
            val manager = AccountProviderManager(Vault(), client(), AccountEndpoints(workbuddy = server.url("/").toString().trimEnd('/')))
            suspend fun login(user: String, token: String) {
                server.enqueue(MockResponse().setBody("""{"data":{"state":"fixture-state","authUrl":"https://copilot.tencent.com/login"}}"""))
                server.enqueue(MockResponse().setBody("""{"data":{"accessToken":"$token"}}"""))
                server.enqueue(MockResponse().setBody("""{"data":{"uid":"$user"}}"""))
                manager.awaitLogin(manager.beginLogin("id", AccountProviderKind.WORKBUDDY))
            }
            login("one", "fixture-one")
            val first = manager.accounts("id", AccountProviderKind.WORKBUDDY).single().accountId
            login("two", "fixture-two")
            val order = manager.accounts("id", AccountProviderKind.WORKBUDDY).map { it.accountId }
            login("one", "fixture-one-new")
            assertEquals(2, order.size)
            assertEquals(first, order.first())
            assertEquals(order, manager.accounts("id", AccountProviderKind.WORKBUDDY).map { it.accountId })
            assertEquals("fixture-one-new", manager.credential("id", AccountProviderKind.WORKBUDDY).accessToken)
        }
    }

    @Test fun cancellationDuringTokenExchangeCannotAppendLateAccount() = runBlocking {
        newServer().use { server ->
            val manager = AccountProviderManager(Vault(), client(), AccountEndpoints(workbuddy = server.url("/").toString().trimEnd('/')))
            server.enqueue(MockResponse().setBody("""{"data":{"state":"fixture-state","authUrl":"https://copilot.tencent.com/login"}}"""))
            val login = manager.beginLogin("id", AccountProviderKind.WORKBUDDY)
            server.takeRequest()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/v2/plugin/auth/token")) {
                        entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS))
                        return MockResponse().setBody("""{"data":{"accessToken":"fixture-late"}}""")
                    }
                    return MockResponse().setBody("""{"data":{"uid":"fixture-late-user"}}""")
                }
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val late = executor.submit<Boolean> {
                    try { runBlocking { manager.awaitLogin(login) }; false } catch (_: AccountProviderException) { true }
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                manager.cancelLogin(login)
                release.countDown()
                assertTrue(late.get(3, TimeUnit.SECONDS))
                assertFalse(manager.isLoggedIn("id"))
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun lowercaseAuthorizationCannotOverrideCredentialOnNormalOr401Retry() {
        newServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"data":{"access_token":"fixture-rotated","refresh_token":"fixture-refresh-two"}}"""))
            server.enqueue(MockResponse().setBody("{}"))
            val manager = manager(server)
            manager.importCredentials("id", kind, """{"accessToken":"fixture-old","refreshToken":"fixture-refresh"}""")
            manager.authorized("id", kind, server.url("/protected").toString(), extraHeaders = mapOf("authorization" to "must-not-be-used", "X-Client-Platform" to "desktop-windows")).close()
            val first = server.takeRequest(); server.takeRequest(); val retry = server.takeRequest()
            assertEquals("Bearer fixture-old", first.getHeader("Authorization"))
            assertEquals("Bearer fixture-rotated", retry.getHeader("Authorization"))
            assertEquals("desktop-windows", first.getHeader("X-Client-Platform"))
            assertEquals("desktop-windows", retry.getHeader("X-Client-Platform"))
        }
    }
}
