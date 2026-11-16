package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Personal-account contract from OfficeAce 1.7.0's Huawei OAuth plugin. */
internal object OfficeAceAccountProtocol {
    private const val CLIENT_ID = "pdp5_for_agentarts"
    private const val CALLBACK = "/v1/claw/auth/callback"
    private const val TOKEN = "/v1/oauth2/tokens"
    private const val PERMISSION = "/v1/claw/client-permission-validate"

    suspend fun begin(manager: AccountProviderManager, id: String, revision: Long): AccountLogin = manager.network {
        val response = manager.request(manager.endpoints.officeAce + "/v1/claw/auth/state", "POST")
        val offset = clockOffset(response)
        val payload = manager.readJson(response)
        val state = payload.text("state") ?: throw AccountProviderException("invalid_login", "OfficeAce returned no OAuth state")
        val verifier = OfficeAceDpop.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val challenge = OfficeAceDpop.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val inner = (manager.endpoints.officeAceAuth + "/authui/v1/oauth2/authorize").toHttpUrl().newBuilder()
            .addQueryParameter("client_id", CLIENT_ID).addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "SHA-256").addQueryParameter("state", state)
            .addQueryParameter("scope", "openid").addQueryParameter("redirect_uri", manager.endpoints.officeAce + CALLBACK)
            .addQueryParameter("response_type", "code").build()
        val url = (manager.endpoints.officeAceAuth + "/authui/login.html").toHttpUrl().newBuilder()
            .addQueryParameter("service", inner.toString()).build().toString()
        AccountLogin(id, AccountProviderKind.OFFICEACE, url, null, state, revision, System.currentTimeMillis() + 600_000,
            verifier, UUID.randomUUID().toString(), officeAceDpop = OfficeAceDpop.create(), officeAceClockOffset = offset)
    }

    suspend fun await(manager: AccountProviderManager, login: AccountLogin): AccountCredential {
        while (!login.closed.get() && System.currentTimeMillis() < login.expiresAt) {
            coroutineContext.ensureActive()
            val credential = manager.network {
                manager.requireAccountVersion(login.id, login.revision)
                val url = (manager.endpoints.officeAce + "/v1/claw/auth/code").toHttpUrl().newBuilder().addQueryParameter("state", login.state).build()
                val response = manager.request(url.toString())
                // The official frontend keeps polling non-2xx responses until its deadline.
                // Retain terminal access failures rather than hiding account restrictions.
                if (response.code in setOf(204, 404)) { response.close(); return@network null }
                val payload = readPayload(response, allowPending = response.code == 202)
                if (payload.text("error_code") == "AgentArts.11000009") return@network null
                if (payload.text("state")?.let { it != login.state } == true) throw AccountProviderException("invalid_login", "OfficeAce state mismatch", 400)
                val code = payload.rawText("code")?.takeIf { it.isNotBlank() && it.length <= 8192 }
                if (code == null) null else {
                    val dpop = login.officeAceDpop ?: throw AccountProviderException("invalid_login", "OfficeAce login key is missing")
                    val form = FormBody.Builder().add("client_id", CLIENT_ID).add("grant_type", "authorization_code")
                        .add("code", code).add("code_verifier", login.verifier).add("redirect_uri", manager.endpoints.officeAce + CALLBACK).build()
                    val tokens = token(manager, form, dpop, login.officeAceClockOffset)
                    validate(manager, tokens, dpop, login.deviceId, login.officeAceClockOffset)
                }
            }
            manager.requireAccountVersion(login.id, login.revision)
            if (credential != null) return credential
            delay(1500)
        }
        throw AccountProviderException("login_expired", "OfficeAce login cancelled or expired", 408)
    }

    private fun token(manager: AccountProviderManager, form: FormBody, dpop: OfficeAceDpop, offset: Long): JsonObject {
        val url = manager.endpoints.officeAceIam + TOKEN
        return readPayload(manager.request(url, "POST", headers = mapOf("DPoP" to dpop.proof(url, System.currentTimeMillis() + offset)), form = form))
    }

    fun refresh(manager: AccountProviderManager, old: AccountCredential): AccountCredential {
        val state = old.officeAceState ?: throw AccountProviderException("invalid_credentials", "OfficeAce credentials are missing", 401)
        val refresh = old.refreshToken ?: throw AccountProviderException("login_required", "Sign in to OfficeAce again", 401)
        val dpop = OfficeAceDpop.restore(state.obj("dpop") ?: throw AccountProviderException("invalid_credentials", "OfficeAce key is missing", 401))
        val offset = state.text("clockOffset")?.toLongOrNull() ?: 0L
        val form = FormBody.Builder().add("client_id", CLIENT_ID).add("grant_type", "refresh_token").add("refresh_token", refresh).build()
        val tokens = token(manager, form, dpop, offset).apply { if (text("refresh_token") == null) addProperty("refresh_token", refresh) }
        return validate(manager, tokens, dpop, old.deviceId, offset).also {
            if (it.userId != old.userId) throw AccountProviderException("account_mismatch", "OfficeAce identity changed during refresh", 409)
        }
    }

    private fun validate(manager: AccountProviderManager, tokens: JsonObject, dpop: OfficeAceDpop, deviceId: String, offset: Long): AccountCredential {
        val iam = tokens.obj("credentials") ?: throw AccountProviderException("invalid_credentials", "OfficeAce returned no IAM credentials", 401)
        val expiry = try { Instant.parse(iam.text("expiration")).toEpochMilli() } catch (_: Exception) {
            throw AccountProviderException("invalid_credentials", "OfficeAce returned no credential expiration", 401)
        }
        if (expiry <= System.currentTimeMillis() + offset) throw AccountProviderException("login_required", "OfficeAce credentials expired", 401)
        val url = manager.endpoints.officeAce + PERMISSION
        val permission = unwrap(readPayload(manager.request(url, headers = OfficeAceDpop.signIam(url, iam, System.currentTimeMillis() + offset))))
        val account = permission.text("account_id") ?: throw AccountProviderException("invalid_identity", "OfficeAce account identity is missing", 403)
        val principal = permission.text("principal_id") ?: throw AccountProviderException("invalid_identity", "OfficeAce principal identity is missing", 403)
        if (permission.text("principal_urn") == null) throw AccountProviderException("invalid_identity", "OfficeAce principal URN is missing", 403)
        val info = permission.obj("model_info") ?: permission.obj("subscription")?.let(::unwrap)?.obj("model_info")
            ?: throw AccountProviderException("empty_catalog", "OfficeAce account has no model gateway", 403)
        val state = JsonObject().apply {
            add("credentials", iam.deepCopy()); add("dpop", dpop.json()); add("modelInfo", info.deepCopy()); addProperty("clockOffset", offset)
        }
        val credential = AccountCredential(AccountProviderKind.OFFICEACE, iam.text("access_key_id")!!, tokens.text("refresh_token"), expiry - offset,
            deviceId, "$account:$principal", officeAceState = state)
        gatewayBases(manager, credential)
        val auth = info.obj("model_auth_info") ?: info.obj("modelAuthInfo")
        if (auth?.firstText("model_app_key", "modelAppKey", "app_key", "appKey") == null ||
            auth?.firstText("model_app_secret", "modelAppSecret", "app_secret", "appSecret") == null) {
            throw AccountProviderException("invalid_credentials", "OfficeAce model credentials are incomplete", 401)
        }
        return credential
    }

    fun restore(value: JsonObject): AccountCredential {
        val state = value.obj("officeAceState") ?: throw AccountProviderException("invalid_credentials", "Missing OfficeAce vault state", 400)
        val iam = state.obj("credentials") ?: throw AccountProviderException("invalid_credentials", "Missing OfficeAce IAM credentials", 400)
        for (field in listOf("access_key_id", "secret_access_key", "security_token", "expiration")) {
            if (iam.text(field).isNullOrBlank()) throw AccountProviderException("invalid_credentials", "Incomplete OfficeAce IAM credentials", 400)
        }
        OfficeAceDpop.restore(state.obj("dpop") ?: throw AccountProviderException("invalid_credentials", "Missing OfficeAce DPoP key", 400))
        if (state.obj("modelInfo") == null || value.text("userId") == null) throw AccountProviderException("invalid_credentials", "Missing OfficeAce identity or gateway", 400)
        val expiresAt = value.text("expiresAt")?.toLongOrNull() ?: throw AccountProviderException("invalid_credentials", "Missing OfficeAce expiration", 400)
        return AccountCredential(AccountProviderKind.OFFICEACE, iam.text("access_key_id")!!, value.text("refreshToken"), expiresAt,
            value.text("deviceId") ?: UUID.randomUUID().toString(), value.text("userId"), officeAceState = state.deepCopy())
    }

    fun catalog(manager: AccountProviderManager, id: String): List<AccountProviderModel> {
        val bases = gatewayBases(manager, manager.refresh(id, AccountProviderKind.OFFICEACE))
        for ((index, base) in bases.withIndex()) {
            val response = modelRequest(manager, id, "$base/models")
            if (response.code in setOf(404, 405) && index < bases.lastIndex) { response.close(); continue }
            val payload = readElement(response)
            AccountProviderProtocol.check(payload.objectOrNull() ?: JsonObject())
            val models = modelRows(payload).mapNotNull { entry ->
                val modelId = entry.text("id") ?: return@mapNotNull null
                if (entry.text("enabled") == "false" || entry.text("disabled") == "true") return@mapNotNull null
                AccountProviderModel(modelId, entry.text("name") ?: modelId, configName = base)
            }.distinctBy { it.id }
            if (models.isNotEmpty()) return models
        }
        throw AccountProviderException("empty_catalog", "OfficeAce account returned no available models")
    }

    fun complete(manager: AccountProviderManager, id: String, body: JsonObject, model: AccountProviderModel, streaming: Boolean): Response {
        val bases = gatewayBases(manager, manager.refresh(id, AccountProviderKind.OFFICEACE))
        val base = model.configName?.takeIf { it in bases }
            ?: throw AccountProviderException("account_changed", "OfficeAce model gateway changed; reload models", 409)
        val response = modelRequest(manager, id, "$base/chat/completions", body.deepCopy().apply {
            addProperty("model", model.id); addProperty("stream", streaming)
        })
        if (!response.isSuccessful) {
            val status = response.code
            response.close()
            throw AccountProviderException("upstream_http", "OfficeAce returned HTTP $status", if (status in 400..499) status else 502)
        }
        // Preserve the upstream OpenAI body, including whitespace and tool calls.
        return response
    }

    private fun modelRequest(manager: AccountProviderManager, id: String, url: String, body: JsonObject? = null): Response {
        var credential = manager.refresh(id, AccountProviderKind.OFFICEACE)
        fun request(): Response {
            if (gatewayBases(manager, credential).none { url == "$it/models" || url == "$it/chat/completions" }) {
                throw AccountProviderException("account_changed", "OfficeAce gateway changed; reload models", 409)
            }
            val info = credential.officeAceState!!.obj("modelInfo")!!
            val auth = info.obj("model_auth_info") ?: info.obj("modelAuthInfo")
                ?: throw AccountProviderException("invalid_credentials", "OfficeAce model authorization is missing", 401)
            val key = auth.firstText("model_app_key", "modelAppKey", "app_key", "appKey")
            val secret = auth.firstText("model_app_secret", "modelAppSecret", "app_secret", "appSecret")
            if (key == null || secret == null) throw AccountProviderException("invalid_credentials", "OfficeAce model credentials are incomplete", 401)
            val encoded = java.util.Base64.getEncoder().encodeToString("$key:$secret".toByteArray(Charsets.UTF_8))
            return manager.request(url, if (body == null) "GET" else "POST", body, mapOf("Authorization" to "Basic $encoded"))
        }
        var response = request()
        if (response.code == 401) {
            response.close()
            credential = manager.refresh(id, AccountProviderKind.OFFICEACE, credential.accessToken)
            response = request()
        }
        return response
    }

    private fun gatewayBases(manager: AccountProviderManager, credential: AccountCredential): List<String> {
        val info = credential.officeAceState?.obj("modelInfo")
            ?: throw AccountProviderException("invalid_credentials", "OfficeAce model gateway is missing", 401)
        val raw = info.firstText("model_api_url_base", "modelApiUrlBase", "model_api_url", "modelApiUrl")
            ?: throw AccountProviderException("invalid_credentials", "OfficeAce model gateway URL is missing", 401)
        val url = (if (raw.contains("://")) raw else "https://$raw").toHttpUrl()
        val fixture = manager.endpoints.officeAce.toHttpUrl()
        val localFixture = fixture.scheme == "http" && fixture.host in setOf("localhost", "127.0.0.1", "::1") &&
            url.host == fixture.host && url.port == fixture.port
        if ((!url.isHttps && !localFixture) || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            throw AccountProviderException("invalid_gateway", "OfficeAce returned an invalid model gateway", 400)
        }
        val root = url.encodedPath.trimEnd('/').replace(Regex("/(?:v1|v2)$", RegexOption.IGNORE_CASE), "")
        return listOf("v2", "v1").map { url.newBuilder().encodedPath("$root/$it").build().toString().trimEnd('/') }
    }

    private fun modelRows(value: com.google.gson.JsonElement, depth: Int = 0): List<JsonObject> {
        if (depth > 4) return emptyList()
        if (value.isJsonArray) return value.asJsonArray.mapNotNull { it.objectOrNull() }
        val obj = value.objectOrNull() ?: return emptyList()
        for (key in listOf("data", "list", "models")) {
            val nested = obj.get(key) ?: continue
            val rows = modelRows(nested, depth + 1)
            if (rows.isNotEmpty() || nested.isJsonArray) return rows
        }
        return emptyList()
    }

    private fun JsonObject.firstText(vararg keys: String): String? = keys.firstNotNullOfOrNull { text(it)?.trim() }

    private fun unwrap(value: JsonObject): JsonObject = value.obj("data") ?: value.obj("result") ?: value

    private fun readPayload(response: Response, allowPending: Boolean = false): JsonObject = readElement(response, allowPending).objectOrNull()
        ?: throw AccountProviderException("invalid_response", "OfficeAce returned an invalid object")

    private fun readElement(response: Response, allowPending: Boolean = false): com.google.gson.JsonElement {
        // OAuth's code is the authorization code, not a generic business status.
        return response.use {
            val source = it.body?.source() ?: throw AccountProviderException("empty_response", "OfficeAce returned no response")
            if (source.request(AccountProviderManager.MAX_JSON.toLong() + 1)) throw AccountProviderException("response_too_large", "OfficeAce response exceeds the limit")
            val value = try { com.google.gson.JsonParser.parseString(source.readUtf8()) } catch (_: RuntimeException) {
                throw AccountProviderException("invalid_response", "OfficeAce returned invalid JSON")
            }
            if (!it.isSuccessful) throw AccountProviderException("upstream_http", "OfficeAce returned HTTP ${it.code}", if (it.code in 400..499) it.code else 502)
            val obj = value.objectOrNull()
            val pending = allowPending && it.code == 202 && obj?.text("error_code") == "AgentArts.11000009" &&
                (obj.get("error") == null || obj.get("error").isJsonNull)
            if (it.code == 202 && !pending) throw AccountProviderException("officeace_rejected", "OfficeAce returned an unknown pending status", 502)
            if (!pending && (obj?.text("error_code") != null || obj?.get("error")?.isJsonNull == false))
                throw AccountProviderException("officeace_rejected", "OfficeAce rejected the request")
            value
        }
    }

    private fun clockOffset(response: Response): Long = try {
        response.header("Date")?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis() } ?: 0L
    } catch (_: RuntimeException) { 0L }
}
