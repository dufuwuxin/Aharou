package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

class OfficeAceAccountProtocolTest {
    @Test fun dpopProofIsVerifiableAndBindsMethodUrlTimeAndFreshNonce() {
        val key = OfficeAceDpop.create()
        val url = "https://sts.cn-north-4.myhuaweicloud.com/v1/oauth2/tokens"
        val proof = key.proof(url, 1700000000000L)
        val pieces = proof.split('.')
        fun json(value: String) = JsonParser.parseString(String(Base64.getUrlDecoder().decode(value))).asJsonObject
        val header = json(pieces[0])
        val payload = json(pieces[1])
        assertEquals("dpop+jwt", header.text("typ"))
        assertEquals("ES256", header.text("alg"))
        assertFalse(header.obj("jwk")!!.has("d"))
        assertEquals("POST", payload.text("htm"))
        assertEquals(url, payload.text("htu"))
        assertEquals(1700000000L, payload.get("iat").asLong)
        assertNotEquals(payload.text("jti"), json(key.proof(url, 1700000000000L).split('.')[1]).text("jti"))
        val jwk = header.obj("jwk")!!
        val spec = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, Base64.getUrlDecoder().decode(jwk.text("x"))), BigInteger(1, Base64.getUrlDecoder().decode(jwk.text("y"))))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, spec))
        val raw = Base64.getUrlDecoder().decode(pieces[2])
        assertEquals(64, raw.size)
        fun integer(bytes: ByteArray): ByteArray {
            val value = BigInteger(1, bytes).toByteArray()
            return byteArrayOf(2, value.size.toByte()) + value
        }
        val values = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        val der = byteArrayOf(0x30, values.size.toByte()) + values
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey); update("${pieces[0]}.${pieces[1]}".toByteArray()); verify(der)
        })
        assertEquals(key.json(), OfficeAceDpop.restore(key.json()).json())
        assertFalse(key.toString().contains(key.json().text("privateKey")!!))
    }

    private class Vault : AccountCredentialVault {
        private val values = mutableMapOf<String, String>()
        override fun read(id: String) = values[id]
        override fun write(id: String, value: String?) { if (value == null) values.remove(id) else values[id] = value }
    }

    @Test fun authorizationUsesRemoteStateAndSha256PkceWithoutAppCredentials() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val manager = AccountProviderManager(Vault(), okhttp3.OkHttpClient.Builder()
                .connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).followRedirects(false).build(),
                AccountEndpoints(officeAce = base, officeAceIam = base, officeAceAuth = base))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"state":"fixture-state"}"""))
            val login = manager.beginLogin("id", AccountProviderKind.OFFICEACE)
            val request = server.takeRequest()
            assertEquals("/v1/claw/auth/state", request.path)
            assertEquals("POST", request.method)
            assertNull(request.getHeader("Authorization"))
            val outer = login.url.toHttpUrl()
            val inner = outer.queryParameter("service")!!.toHttpUrl()
            assertEquals("fixture-state", inner.queryParameter("state"))
            assertEquals("SHA-256", inner.queryParameter("code_challenge_method"))
            assertEquals(OfficeAceDpop.encode(java.security.MessageDigest.getInstance("SHA-256").digest(login.verifier.toByteArray())), inner.queryParameter("code_challenge"))
            assertEquals("$base/v1/claw/auth/callback", inner.queryParameter("redirect_uri"))
            assertNotNull(login.officeAceDpop)
            manager.cancelLogin(login)
            assertFalse(manager.isLoggedIn("id"))
            try { manager.awaitLogin(login); fail("Cancelled login must not persist") } catch (_: AccountProviderException) { }
            assertFalse(manager.isLoggedIn("id"))
        }
    }

    @Test fun iamSignatureBindsPermissionRouteAndSubscriptionHeader() {
        val credentials = JsonObject().apply {
            addProperty("access_key_id", "fixture-access")
            addProperty("secret_access_key", "fixture-secret")
            addProperty("security_token", "fixture-security-token")
            addProperty("project_id", "fixture-project")
        }
        val url = "https://agentarts.cn-southwest-2.myhuaweicloud.com/v1/claw/client-permission-validate"
        val signed = OfficeAceDpop.signIam(url, credentials, 1700000000000L)
        assertEquals("v2", signed["x-subscription-type"])
        assertEquals("20231114T221320Z", signed["x-sdk-date"])
        assertTrue(signed["Authorization"]!!.contains("SignedHeaders=host;x-project-id;x-sdk-date;x-security-token;x-subscription-type"))
        assertNotEquals(signed["Authorization"], OfficeAceDpop.signIam(url + "/other", credentials, 1700000000000L)["Authorization"])
    }
    private fun tokens() = """{"credentials":{"access_key_id":"fixture-iam","secret_access_key":"fixture-secret","security_token":"fixture-sts","expiration":"${java.time.Instant.now().plusSeconds(3600)}","project_id":"fixture-project"},"refresh_token":"fixture-refresh"}"""

    private fun permission(base: String, user: String = "principal") = """{"data":{"account_id":"account","principal_id":"$user","principal_urn":"iam::account:user:fixture","model_info":{"model_api_url_base":"$base","model_auth_info":{"model_app_key":"fixture-model","model_app_secret":"fixture-model-secret"}}}}"""

    private fun manager(server: okhttp3.mockwebserver.MockWebServer, vault: Vault = Vault()): AccountProviderManager {
        val base = server.url("/").toString().trimEnd('/')
        return AccountProviderManager(vault, okhttp3.OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT))
            .followRedirects(false).followSslRedirects(false).build(), AccountEndpoints(officeAce = base, officeAceIam = base, officeAceAuth = base))
    }

    private suspend fun login(manager: AccountProviderManager, server: okhttp3.mockwebserver.MockWebServer, user: String = "principal") {
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"state":"fixture-state"}"""))
        val login = manager.beginLogin("id", AccountProviderKind.OFFICEACE)
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"code":"fixture-auth-code"}"""))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(tokens()))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(permission(base, user)))
        manager.awaitLogin(login)
        assertEquals("/v1/claw/auth/state", server.takeRequest().path)
        assertEquals("/v1/claw/auth/code?state=fixture-state", server.takeRequest().path)
        val exchange = server.takeRequest()
        assertEquals("/v1/oauth2/tokens", exchange.path)
        assertTrue(exchange.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        val fields = ("https://fixture.invalid/?" + exchange.body.readUtf8()).toHttpUrl()
        assertEquals("pdp5_for_agentarts", fields.queryParameter("client_id"))
        assertEquals("authorization_code", fields.queryParameter("grant_type"))
        assertEquals(login.verifier, fields.queryParameter("code_verifier"))
        assertEquals("$base/v1/claw/auth/callback", fields.queryParameter("redirect_uri"))
        assertEquals(3, exchange.getHeader("DPoP")!!.split('.').size)
        val validation = server.takeRequest()
        assertEquals("/v1/claw/client-permission-validate", validation.path)
        assertEquals("fixture-sts", validation.getHeader("X-Security-Token"))
        assertEquals("v2", validation.getHeader("x-subscription-type"))
        assertTrue(validation.getHeader("Authorization")!!.startsWith("SDK-HMAC-SHA256 "))
        assertTrue(manager.isLoggedIn("id"))
    }

    @Test fun fullOAuthRefreshCatalogAndInferencePreserveStreamBytesAndStopAt400() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val vault = Vault()
            val original = manager(server, vault)
            login(original, server)
            val restored = manager(server, vault)
            assertTrue(restored.isLoggedIn("id"))
            val slot = restored.accounts("id", AccountProviderKind.OFFICEACE).single()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(tokens()))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(permission(server.url("/").toString().trimEnd('/'))))
            restored.withPoolAccount("id", AccountProviderKind.OFFICEACE, slot.accountId) {
                restored.refresh("id", AccountProviderKind.OFFICEACE, "fixture-iam")
            }
            val refresh = server.takeRequest()
            val refreshFields = ("https://fixture.invalid/?" + refresh.body.readUtf8()).toHttpUrl()
            assertEquals("refresh_token", refreshFields.queryParameter("grant_type"))
            assertEquals("fixture-refresh", refreshFields.queryParameter("refresh_token"))
            assertNotNull(refresh.getHeader("DPoP"))
            server.takeRequest()
            val catalog = """{"data":[{"id":"fixture-model-id","name":"Fixture model"}]}"""
            val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("fixture-model:fixture-model-secret".toByteArray())
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(catalog))
            val snapshot = restored.snapshot("id", AccountProviderKind.OFFICEACE)
            assertEquals("fixture-model-id", snapshot.models.single().id)
            assertNull(snapshot.balance)
            val directory = server.takeRequest()
            assertEquals("/v2/models", directory.path)
            assertEquals(expectedAuth, directory.getHeader("Authorization"))
            val client = okhttp3.OkHttpClient.Builder().addInterceptor(restored.interceptor()).build()
            for (stream in listOf(true, false)) {
                val bytes = if (stream) "data: {\"choices\":[{\"delta\":{\"content\":\"  x \\n\"}}]}\n\ndata: [DONE]\n\n"
                    else """{"choices":[{"message":{"content":"  x \n"}}]}"""
                server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(catalog))
                server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", if (stream) "text/event-stream" else "application/json").setBody(bytes))
                val body = """{"model":"fixture-model-id","messages":[{"role":"user","content":"hi"}],"stream":$stream}"""
                val request = okhttp3.Request.Builder().url(restored.baseUrl(AccountProviderKind.OFFICEACE, "id") + "/chat/completions")
                    .post(body.toRequestBody()).build()
                client.newCall(request).execute().use { assertEquals(200, it.code); assertEquals(bytes, it.body!!.string()) }
                server.takeRequest()
                val inference = server.takeRequest()
                assertEquals("/v2/chat/completions", inference.path)
                assertEquals(expectedAuth, inference.getHeader("Authorization"))
                assertEquals(stream, JsonParser.parseString(inference.body.readUtf8()).asJsonObject.get("stream").asBoolean)
            }
            login(restored, server, "second")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(catalog))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(400).setBody("{}"))
            val body = """{"model":"fixture-model-id","messages":[],"stream":true}"""
            val request = okhttp3.Request.Builder().url(restored.baseUrl(AccountProviderKind.OFFICEACE, "id") + "/chat/completions")
                .post(body.toRequestBody()).build()
            client.newCall(request).execute().use { assertEquals(400, it.code) }
            server.takeRequest(); server.takeRequest()
            assertNull(server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertEquals(listOf(1, 2), restored.accounts("id", AccountProviderKind.OFFICEACE).map { it.position })
        }
    }

    @Test fun pendingPollAndDeniedPermissionNeverPersistAnAccount() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val manager = manager(server)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"state":"fixture-state"}"""))
            val login = manager.beginLogin("id", AccountProviderKind.OFFICEACE)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"code":"fixture-auth-code"}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(tokens()))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(403).setBody("""{"error_code":"denied"}"""))
            try { manager.awaitLogin(login); fail("Denied permission must reject login") }
            catch (error: AccountProviderException) { assertEquals(403, error.httpStatus) }
            assertFalse(manager.isLoggedIn("id"))
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun catalogUsesV1FallbackAndRefreshes401WithoutChangingModelIdentity() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val manager = manager(server)
            login(manager, server)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401).setBody("{}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(tokens()))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(permission(server.url("/").toString().trimEnd('/'))))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404).setBody("{}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""[{"id":"provider-model-zx","name":"Model"}]"""))
            val model = manager.snapshot("id", AccountProviderKind.OFFICEACE).models.single()
            assertEquals("provider-model-zx", model.id)
            assertTrue(model.configName!!.endsWith("/v1"))
            assertEquals("/v2/models", server.takeRequest().path)
            assertEquals("/v1/oauth2/tokens", server.takeRequest().path)
            assertEquals("/v1/claw/client-permission-validate", server.takeRequest().path)
            assertEquals("/v2/models", server.takeRequest().path)
            assertEquals("/v1/models", server.takeRequest().path)
        }
    }

    @Test fun real202PendingResponseKeepsPollingUntilAuthorizationAndNeverExchangesTheErrorCode() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val manager = manager(server)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"state":"fixture-state"}"""))
            val login = manager.beginLogin("id", AccountProviderKind.OFFICEACE)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(202).setBody(
                """{"error_code":"AgentArts.11000009","error_msg":"pending","details":null,"error_reason":null,"error_suggestion":null}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"code":"fixture-auth-code"}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(tokens()))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(permission(server.url("/").toString().trimEnd('/'))))
            manager.awaitLogin(login)
            assertTrue(manager.isLoggedIn("id"))
            assertEquals("/v1/claw/auth/state", server.takeRequest().path)
            repeat(2) { assertEquals("/v1/claw/auth/code?state=fixture-state", server.takeRequest().path) }
            assertEquals("/v1/oauth2/tokens", server.takeRequest().path)
            assertEquals("/v1/claw/client-permission-validate", server.takeRequest().path)
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun unknown202ErrorIsNotTreatedAsPending() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val manager = manager(server)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"state":"fixture-state"}"""))
            val login = manager.beginLogin("id", AccountProviderKind.OFFICEACE)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(202).setBody("""{"error_code":"AgentArts.other"}"""))
            try { manager.awaitLogin(login); fail("Unknown errors must remain visible") }
            catch (error: AccountProviderException) { assertEquals("officeace_rejected", error.errorCode) }
            assertFalse(manager.isLoggedIn("id"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun arbitrary202WithoutOfficialPendingCodeIsRejectedImmediately() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val manager = manager(server)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"state":"fixture-state"}"""))
            val login = manager.beginLogin("id", AccountProviderKind.OFFICEACE)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(202).setBody("""{"message":"unknown"}"""))
            try { manager.awaitLogin(login); fail("An arbitrary HTTP 202 must not be treated as pending") }
            catch (error: AccountProviderException) { assertEquals("officeace_rejected", error.errorCode) }
            assertFalse(manager.isLoggedIn("id"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun mismatchedPollStateCannotExchangeOrSaveCredentials() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val manager = manager(server)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"state":"fixture-state"}"""))
            val login = manager.beginLogin("id", AccountProviderKind.OFFICEACE)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"state":"wrong","code":"fixture-auth-code"}"""))
            try { manager.awaitLogin(login); fail("State must bind the login") }
            catch (error: AccountProviderException) { assertEquals("invalid_login", error.errorCode) }
            assertFalse(manager.isLoggedIn("id"))
            assertEquals(2, server.requestCount)
        }
    }

}
