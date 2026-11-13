package com.aharou.feature.settings.data.account

import android.content.Context
import com.aharou.core.security.KeystoreCipher
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface AccountCredentialVault {
    fun read(id: String): String?
    fun write(id: String, value: String?)
}

private class EncryptedAccountVault(context: Context) : AccountCredentialVault {
    private val preferences = context.getSharedPreferences("account_provider_credentials_v1", Context.MODE_PRIVATE)
    override fun read(id: String): String? = preferences.getString(id, null)?.let { encrypted ->
        // decryptBytes has no legacy plaintext fallback.
        String(KeystoreCipher.decryptBytes(java.util.Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)
    }
    override fun write(id: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(id) else editor.putString(id, java.util.Base64.getEncoder().encodeToString(KeystoreCipher.encryptBytes(value.toByteArray(Charsets.UTF_8))))
        if (!editor.commit()) throw AccountProviderException("storage_failed", "Unable to save account credentials")
    }
}

@Singleton
class AccountProviderManager internal constructor(
    private val vault: AccountCredentialVault,
    internal val client: OkHttpClient,
    internal val endpoints: AccountEndpoints = AccountEndpoints(),
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        EncryptedAccountVault(context),
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
    )

    private val streamingClient = client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val state = Any()
    private val revisions = HashMap<String, Long>()
    private val refreshLocks = ConcurrentHashMap<String, Any>()
    private val logins = ConcurrentHashMap<String, AccountLogin>()
    private val activeCalls = ConcurrentHashMap<String, MutableSet<Call>>()
    private val operations = ThreadLocal<Operation?>()
    private val parentCalls = ThreadLocal<Call?>()
    private val accountRevision = ThreadLocal<Pair<String, Long>?>()
    private val selectedAccount = ThreadLocal<Pair<String, String>?>()

    private fun scopeId(id: String): String = selectedAccount.get()?.takeIf { it.first == id }?.let { "$id:${it.second}" } ?: id

    private fun pool(id: String, kind: AccountProviderKind): AccountPool {
        val raw = vault.read(id) ?: return AccountPool(kind, emptyList())
        return AccountPool.decode(JsonParser.parseString(raw).asJsonObject, kind) { parseCredential(kind, it) }
    }

    fun accounts(id: String, kind: AccountProviderKind): List<AccountPoolEntry> = synchronized(state) {
        pool(id, kind).accounts.mapIndexed { index, slot -> AccountPoolEntry(slot.accountId, index + 1) }
    }

    internal fun <T> withPoolAccount(id: String, kind: AccountProviderKind, accountId: String, block: () -> T): T {
        val previous = selectedAccount.get()
        selectedAccount.set(id to accountId)
        try {
            return withAccountRevision(scopeId(id)) {
                synchronized(state) {
                    if (pool(id, kind).accounts.none { it.accountId == accountId }) throw AccountProviderException("account_changed", "Account removed", 409)
                }
                block()
            }
        } finally { selectedAccount.set(previous) }
    }

    fun removeAccount(id: String, kind: AccountProviderKind, accountId: String) {
        val cancelled = synchronized(state) {
            val old = pool(id, kind)
            if (old.accounts.none { it.accountId == accountId }) throw AccountProviderException("account_changed", "Account removed", 409)
            val next = AccountPool(kind, old.accounts.filterNot { it.accountId == accountId })
            vault.write(id, next.takeIf { it.accounts.isNotEmpty() }?.json()?.toString())
            revisions[id] = revision(id) + 1
            val key = "$id:$accountId"
            revisions[key] = revision(key) + 1
            val login = logins.remove(id)
            activeCalls.remove(key)?.toList().orEmpty() to login
        }
        cancelled.first.forEach { it.cancel() }
        cancelled.second?.close()
    }

    private fun appendCredential(id: String, expected: Long, credential: AccountCredential) = synchronized(state) {
        operations.get()?.check()
        requireAccountVersion(id, expected)
        val old = pool(id, credential.kind)
        val next = old.append(credential)
        val changed = old.accounts.firstOrNull { oldSlot -> next.accounts.any { it.accountId == oldSlot.accountId && it.credential === credential } }
        val encoded = next.json().toString()
        if (encoded.length > MAX_JSON) throw AccountProviderException("invalid_credentials", "Account pool is too large", 400)
        vault.write(id, encoded)
        revisions[id] = revision(id) + 1
        changed?.let { slot ->
            val key = "$id:${slot.accountId}"
            revisions[key] = revision(key) + 1
            activeCalls.remove(key)?.forEach { it.cancel() }
        }
    }

    internal fun <T> withAccountRevision(id: String, block: () -> T): T {
        val previous = accountRevision.get()
        val pin = previous?.takeIf { it.first == id } ?: synchronized(state) { id to revision(id) }
        accountRevision.set(pin)
        try {
            checkRevision(id)
            val result = block()
            try { checkRevision(id) } catch (error: Exception) { (result as? java.io.Closeable)?.close(); throw error }
            return result
        } finally { accountRevision.set(previous) }
    }

    internal fun accountVersion(id: String): Long = synchronized(state) { revision(id) }
    internal fun requireAccountVersion(id: String, expected: Long) = synchronized(state) {
        if (revision(id) != expected) throw AccountProviderException("account_changed", "Account changed while the request was running", 409)
    }

    internal suspend fun persistSnapshot(
        id: String, expected: Long,
        repository: com.aharou.feature.settings.domain.repository.AIProviderRepository,
        config: com.aharou.feature.settings.domain.model.AIProviderConfig,
    ) = network {
        synchronized(state) {
            requireAccountVersion(id, expected)
            kotlinx.coroutines.runBlocking { repository.saveProvider(config) }
        }
    }

    private fun checkRevision(id: String) {
        val pin = accountRevision.get() ?: return
        synchronized(state) {
            if (pin.first != scopeId(id) || pin.second != revision(scopeId(id))) throw AccountProviderException("account_changed", "Account changed while the request was running", 409)
        }
    }

    fun normalizeConfig(config: com.aharou.feature.settings.domain.model.AIProviderConfig): com.aharou.feature.settings.domain.model.AIProviderConfig =
        if (kind(config.baseUrl) == null) config else config.copy(
            type = com.aharou.feature.settings.domain.model.ProviderType.OPENAI,
            apiKey = "", apiKeys = emptyList(), multiKeyEnabled = false, useFullUrl = false, useResponseApi = false,
            customHeaders = emptyMap(), openaiChatCacheKey = false,
        )

    internal fun <T> withParentCall(call: Call, block: () -> T): T {
        parentCalls.set(call)
        try { return block() } finally { parentCalls.remove() }
    }

    private fun validateId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,256}"))) { "Invalid account provider ID" }
    }

    internal fun deviceId(id: String): String = synchronized(state) {
        validateId(id)
        val key = "device:$id"
        val existing = vault.read(key)
        if (existing != null) existing else UUID.randomUUID().toString().also { vault.write(key, it) }
    }
    private class Operation {
        val cancelled = AtomicBoolean(false)
        val calls = ConcurrentHashMap.newKeySet<Call>()
        fun check() { if (cancelled.get()) throw kotlinx.coroutines.CancellationException("Account operation cancelled") }
    }

    fun interceptor(): Interceptor = AccountGatewayInterceptor(this)

    fun baseUrl(kind: AccountProviderKind, id: String): String {
        validateId(id)
        return okhttp3.HttpUrl.Builder().scheme("https").host(SENTINEL).addPathSegment(kind.wireId).addPathSegment(id).addPathSegment("v1").build().toString()
    }

    fun kind(baseUrl: String): AccountProviderKind? = try {
        val url = baseUrl.toHttpUrlOrNull()
        if (url == null || url.scheme != "https" || url.host != SENTINEL || url.port != 443 || url.pathSegments.size != 3 || url.pathSegments[2] != "v1" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) null
        else AccountProviderKind.fromWireId(url.pathSegments[0])
    } catch (_: IllegalArgumentException) { null }

    fun isLoggedIn(id: String): Boolean = synchronized(state) {
        val raw = vault.read(id) ?: return@synchronized false
        val value = JsonParser.parseString(raw).asJsonObject
        !value.has("poolVersion") || value.array("accounts").size() > 0
    }

    private fun revision(id: String): Long = revisions[id] ?: 0L
    internal fun nextRevision(id: String): Long = synchronized(state) {
        validateId(id)
        val next = revision(id) + 1
        revisions[id] = next
        next
    }

    internal fun credential(id: String, kind: AccountProviderKind): AccountCredential = synchronized(state) {
        val current = pool(id, kind)
        val selected = selectedAccount.get()?.takeIf { it.first == id }?.second
        (if (selected == null) current.accounts.firstOrNull() else current.accounts.firstOrNull { it.accountId == selected })?.credential
            ?: throw AccountProviderException("login_required", "Sign in to the account provider", 401)
    }

    internal fun save(id: String, expectedRevision: Long, credential: AccountCredential) = synchronized(state) {
        operations.get()?.check()
        val key = scopeId(id)
        if (revision(key) != expectedRevision) throw AccountProviderException("account_changed", "Account changed while the request was running", 409)
        val current = pool(id, credential.kind)
        val selected = selectedAccount.get()?.takeIf { it.first == id }?.second ?: current.accounts.firstOrNull()?.accountId
        if (current.accounts.none { it.accountId == selected }) throw AccountProviderException("account_changed", "Account removed", 409)
        vault.write(id, AccountPool(credential.kind, current.accounts.map { if (it.accountId == selected) PoolAccount(it.accountId, credential) else it }).json().toString())
    }

    fun logout(id: String) {
        validateId(id)
        val (previousCalls, previousLogin) = synchronized(state) {
            revisions[id] = revision(id) + 1
            vault.write(id, null)
            val calls = activeCalls.keys.filter { it == id || it.startsWith("$id:") }.flatMap { key ->
                revisions[key] = revision(key) + 1
                activeCalls.remove(key)?.toList().orEmpty()
            }
            calls to logins.remove(id)
        }
        previousCalls.forEach { it.cancel() }
        previousLogin?.close()
    }

    internal fun exportCredentials(id: String): String = synchronized(state) {
        vault.read(id) ?: throw AccountProviderException("login_required", "No account credentials to export", 401)
    }

    internal fun importCredentials(id: String, kind: AccountProviderKind, json: String) {
        validateId(id)
        require(json.length <= MAX_JSON) { "Credential input is too large" }
        val value = try { JsonParser.parseString(json).asJsonObject } catch (_: RuntimeException) { throw AccountProviderException("invalid_credentials", "Credentials must be a JSON object", 400) }
        val declared = value.text("kind")
        if (declared != null && declared != kind.wireId) throw AccountProviderException("account_mismatch", "Imported account provider does not match", 400)
        val imported = AccountPool.decode(value, kind) { parseCredential(kind, it) }
        val (previousCalls, previousLogin) = synchronized(state) {
            val keys = (pool(id, kind).accounts.map { "$id:${it.accountId}" } + id).toSet()
            vault.write(id, imported.takeIf { it.accounts.isNotEmpty() }?.json()?.toString())
            val calls = keys.flatMap { key ->
                revisions[key] = revision(key) + 1
                activeCalls.remove(key)?.toList().orEmpty()
            }
            calls to logins.remove(id)
        }
        previousCalls.forEach { it.cancel() }
        previousLogin?.close()
    }

    internal fun parseCredential(kind: AccountProviderKind, response: JsonObject, previous: AccountCredential? = null, deviceId: String? = null): AccountCredential {
        AccountProviderProtocol.check(response)
        val value = response.obj("Result") ?: response.obj("result") ?: response.obj("data") ?: response
        val token = value.text("accessToken") ?: value.text("access_token") ?: value.text("Token") ?: value.text("token") ?: value.text("AccessToken")
            ?: throw AccountProviderException("invalid_credentials", "Provider response has no access token", 401)
        if (token.length > 32768 || token.any { it == '\r' || it == '\n' }) throw AccountProviderException("invalid_credentials", "Invalid access token", 400)
        val refresh = value.text("refreshToken") ?: value.text("refresh_token") ?: value.text("RefreshToken") ?: previous?.refreshToken
        if (refresh != null && (refresh.length > 32768 || refresh.any { it == '\r' || it == '\n' })) throw AccountProviderException("invalid_credentials", "Invalid refresh token", 400)
        val expiry = (value.text("expiresAt") ?: value.text("TokenExpireAt") ?: value.text("ExpiresAt"))?.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it } ?: value.text("expiresIn")?.toLongOrNull()?.let { System.currentTimeMillis() + it * 1000 } ?: AccountProviderProtocol.expiry(token)
        return AccountCredential(kind, token, refresh, expiry, deviceId ?: value.text("deviceId") ?: previous?.deviceId ?: UUID.randomUUID().toString(), value.text("userId") ?: value.text("UserID") ?: previous?.userId,
            value.text("domain") ?: previous?.domain, value.text("enterpriseId") ?: previous?.enterpriseId)
    }

    internal fun headers(credential: AccountCredential): Map<String, String> = when (credential.kind) {
        AccountProviderKind.LOOMY -> LoomyAccountProtocol.headers(credential.accessToken)
        AccountProviderKind.RACCOON -> mapOf("Authorization" to "Bearer ${credential.accessToken}")
        AccountProviderKind.WORKBUDDY, AccountProviderKind.WORKBUDDY_GLOBAL -> buildMap {
            put("Authorization", "Bearer ${credential.accessToken}")
            put("X-Client-Type", "CLI"); put("X-Auth-Refresh-Source", "workbuddy")
            put("X-Conversation-ID", credential.deviceId); put("X-Conversation-Request-ID", UUID.randomUUID().toString()); put("X-Conversation-Message-ID", UUID.randomUUID().toString())
            put("X-IDE-Type", "WorkBuddy"); put("X-IDE-Name", "WorkBuddy"); put("X-Product", "SaaS")
            val website = if (credential.kind == AccountProviderKind.WORKBUDDY_GLOBAL) "https://www.workbuddy.ai" else "https://www.codebuddy.cn"
            put("Origin", website); put("Referer", "$website/")
            credential.userId?.let { put("X-User-Id", it) }
            credential.domain?.let { put("X-Domain", it) }
            credential.enterpriseId?.let { put("X-Enterprise-Id", it); put("X-Tenant-Id", it) }
        }
        AccountProviderKind.TRAE, AccountProviderKind.TRAE_GLOBAL -> mapOf(
            "Authorization" to "Cloud-IDE-JWT ${credential.accessToken}", "X-Cloudide-Token" to credential.accessToken, "X-Ide-Token" to credential.accessToken,
            "X-Device-Id" to credential.deviceId, "X-Machine-Id" to credential.deviceId, "X-App-Id" to "6eefa01c-1036-4c7e-9ca5-d891f63bfcd8",
            "X-App-Version" to "0.1.69", "X-Ide-Version" to "0.1.69", "X-Plugin-Channel" to "icube-ai",
        )
    }

    internal fun request(url: String, method: String = "GET", body: JsonObject? = null, headers: Map<String, String> = emptyMap()): Response {
        operations.get()?.check()
        val builder = Request.Builder().url(url).header("User-Agent", "Aharou-AccountProvider/1.0").header("Accept", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.method(method, if (method == "GET") null else (body ?: JsonObject()).toString().toRequestBody(JSON))
        val selectedClient = if (body?.get("stream")?.toString() == "true") streamingClient else client
        val call = selectedClient.newCall(builder.build())
        val operation = operations.get()
        val parent = parentCalls.get()
        val pinnedAccount = accountRevision.get()
        if (pinnedAccount != null) synchronized(state) {
            requireAccountVersion(pinnedAccount.first, pinnedAccount.second)
            activeCalls.computeIfAbsent(pinnedAccount.first) { ConcurrentHashMap.newKeySet<Call>() }.add(call)
        }
        operation?.calls?.add(call)
        val timer = if (parent != null) java.util.Timer("AccountCallCancellation", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() { if (parent.isCanceled()) call.cancel() }
            }, 0L, 100L)
        } else null
        fun finish() {
            timer?.cancel(); operation?.calls?.remove(call)
            pinnedAccount?.let { activeCalls[it.first]?.remove(call) }
        }
        try {
            operation?.check()
            val response = call.execute()
            if (response.code in 300..399) {
                response.close()
                throw AccountProviderException("redirect_rejected", "Account provider redirects are not permitted")
            }
            val raw = response.body ?: run { finish(); return response }
            val source = object : ForwardingSource(raw.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long = try {
                    operation?.check()
                    pinnedAccount?.let { requireAccountVersion(it.first, it.second) }
                    super.read(sink, byteCount).also {
                        pinnedAccount?.let { pin -> requireAccountVersion(pin.first, pin.second) }
                        if (it == -1L) finish()
                    }
                } catch (error: Exception) { try { super.close() } finally { finish() }; throw error }
                override fun close() { try { super.close() } finally { finish() } }
            }.buffer()
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = raw.contentType()
                override fun contentLength() = raw.contentLength()
                override fun source() = source
            }).build()
        } catch (error: Exception) { finish(); throw error }
    }

    internal fun readJson(response: Response, allowLoginPending: Boolean = false): JsonObject = response.use {
        val source = it.body?.source() ?: throw AccountProviderException("empty_response", "Account provider returned no response")
        if (source.request(MAX_JSON.toLong() + 1)) throw AccountProviderException("response_too_large", "Account provider response exceeds the size limit")
        val json = try { JsonParser.parseString(source.readUtf8()).asJsonObject } catch (_: RuntimeException) { throw AccountProviderException("invalid_response", "Account provider returned invalid JSON") }
        if (!(allowLoginPending && json.text("code") == "11217")) {
            try { AccountProviderProtocol.check(json) }
            catch (error: AccountProviderException) {
                if (it.isSuccessful || error.httpStatus in setOf(401, 402, 403, 429)) throw error
            }
        }
        if (!it.isSuccessful) throw AccountProviderException("upstream_http", "Account provider returned HTTP ${it.code}", if (it.code in 400..499) it.code else 502)
        json
    }

    internal fun refresh(id: String, kind: AccountProviderKind, failedToken: String? = null): AccountCredential = synchronized(refreshLocks.getOrPut("${kind.wireId}:${scopeId(id)}") { Any() }) {
        checkRevision(id)
        val (old, expected) = synchronized(state) { credential(id, kind) to revision(scopeId(id)) }
        if (failedToken != null && old.accessToken != failedToken) return@synchronized old
        if (failedToken == null && (old.expiresAt == null || old.expiresAt > System.currentTimeMillis() + 60_000)) return@synchronized old
        val refreshToken = old.refreshToken ?: throw AccountProviderException("login_required", "Account token expired; sign in again", 401)
        val body = JsonObject()
        val url: String
        val refreshHeaders = headers(old).toMutableMap()
        when (kind) {
            AccountProviderKind.LOOMY -> throw AccountProviderException("login_required", "Import a new Loomy session", 401)
            AccountProviderKind.RACCOON -> { url = endpoints.raccoon + "/api/electron/auth/v1/refresh"; body.addProperty("refresh_token", refreshToken) }
            AccountProviderKind.WORKBUDDY, AccountProviderKind.WORKBUDDY_GLOBAL -> { url = endpoints.workbuddy(kind) + "/v2/plugin/auth/token/refresh"; refreshHeaders["X-Refresh-Token"] = refreshToken }
            AccountProviderKind.TRAE, AccountProviderKind.TRAE_GLOBAL -> {
                url = endpoints.traeAccount(kind) + "/cloudide/api/v3/trae/oauth/ExchangeToken"
                body.addProperty("ClientID", traeClientId(kind)); body.addProperty("RefreshToken", refreshToken); body.addProperty("ClientSecret", "-"); body.addProperty("UserID", "")
            }
        }
        val next = parseCredential(kind, readJson(request(url, "POST", body, refreshHeaders)), old)
        save(id, expected, next)
        next
    }

    internal fun authorized(id: String, kind: AccountProviderKind, url: String, method: String = "GET", body: JsonObject? = null, extraHeaders: Map<String, String> = emptyMap()): Response {
        checkRevision(id)
        var credential = refresh(id, kind)
        // OkHttp headers are case-insensitive, Kotlin maps are not. Never accept auth overrides.
        val authentication = headers(credential).keys + setOf("Authorization", "Cookie", "X-Refresh-Token")
        val safeExtra = extraHeaders.filterKeys { name -> authentication.none { it.equals(name, ignoreCase = true) } }
        checkRevision(id)
        var response = request(url, method, body, safeExtra + headers(credential))
        if (response.code == 401) {
            response.close()
            checkRevision(id)
            credential = refresh(id, kind, credential.accessToken)
            checkRevision(id)
            response = request(url, method, body, safeExtra + headers(credential))
        }
        return response
    }

    internal fun catalogForAccount(id: String, kind: AccountProviderKind): List<AccountProviderModel> {
        val (url, method, body) = when (kind) {
            AccountProviderKind.LOOMY -> Triple(endpoints.loomy + "/api/v1/models", "GET", null)
            AccountProviderKind.RACCOON -> Triple(endpoints.raccoon + "/api/web/llm/v2/model_catalog", "GET", null)
            AccountProviderKind.WORKBUDDY, AccountProviderKind.WORKBUDDY_GLOBAL -> Triple(endpoints.workbuddy(kind) + "/v3/config", "GET", null)
            AccountProviderKind.TRAE, AccountProviderKind.TRAE_GLOBAL -> Triple(endpoints.trae(kind) + "/api/ide/v1/get_detail_param", "POST", JsonObject().apply { addProperty("function", "chat_v3") })
        }
        return AccountProviderProtocol.models(kind, readJson(authorized(id, kind, url, method, body)))
    }

    internal fun poolCatalog(id: String, kind: AccountProviderKind): AccountCatalog = withAccountRevision(id) {
        val entries = accounts(id, kind)
        if (entries.isEmpty()) throw AccountProviderException("login_required", "Sign in to the account provider", 401)
        val models = linkedMapOf<String, AccountProviderModel>()
        val issues = mutableListOf<AccountPoolIssue>()
        var firstFailure: IOException? = null
        for (slot in entries) {
            try {
                withPoolAccount(id, kind, slot.accountId) { catalogForAccount(id, kind) }.forEach { models.putIfAbsent(it.id, it) }
            } catch (error: IOException) {
                if (error is AccountProviderException && error.errorCode == "account_changed") throw error
                if (firstFailure == null) firstFailure = error
                issues.add(AccountPoolIssue(slot.accountId, (error as? AccountProviderException)?.errorCode ?: "network_error"))
            }
        }
        if (models.isEmpty()) throw firstFailure ?: AccountProviderException("empty_catalog", "Account pool returned no usable models")
        AccountCatalog(models.values.toList(), issues)
    }

    internal fun catalog(id: String, kind: AccountProviderKind): List<AccountProviderModel> =
        if (selectedAccount.get()?.first == id) catalogForAccount(id, kind) else poolCatalog(id, kind).models

    suspend fun snapshot(id: String, kind: AccountProviderKind): AccountSnapshot = network { withAccountRevision(id) {
        val directory = poolCatalog(id, kind)
        val (url, method) = when (kind) {
            AccountProviderKind.LOOMY -> endpoints.loomy + "/api/v1/points/records?pageNo=1&pageSize=20&recordType=all" to "GET"
            AccountProviderKind.RACCOON -> endpoints.raccoon + "/api/web/points/v1/balance" to "GET"
            AccountProviderKind.WORKBUDDY, AccountProviderKind.WORKBUDDY_GLOBAL -> endpoints.billing(kind) + "/v2/billing/meter/get-user-resource" to "POST"
            AccountProviderKind.TRAE -> endpoints.usage(kind) + "/trae/api/v2/pay/ide_user_ent_usage" to "POST"
            AccountProviderKind.TRAE_GLOBAL -> endpoints.usage(kind) + "/trae/api/v1/pay/ide_user_ent_usage" to "POST"
        }
        val issues = directory.issues.toMutableList()
        val balances = mutableListOf<String>()
        val slots = accounts(id, kind)
        for (slot in slots) {
            try {
                val balance = withPoolAccount(id, kind, slot.accountId) { AccountProviderProtocol.balance(kind, readJson(authorized(id, kind, url, method))) }
                balance?.let { balances.add(if (slots.size == 1) it else "#${slot.position}: $it") }
            } catch (error: IOException) {
                if (error is AccountProviderException && error.errorCode == "account_changed") throw error
                issues.add(AccountPoolIssue(slot.accountId, (error as? AccountProviderException)?.errorCode ?: "network_error"))
            }
        }
        AccountSnapshot(directory.models, balances.takeIf { it.isNotEmpty() }?.joinToString("; "), System.currentTimeMillis(),
            if (issues.isEmpty()) null else if (slots.size == 1) issues.first().errorCode else "partial", issues.distinct())
    } }

    suspend fun checkInAccount(id: String, kind: AccountProviderKind, accountId: String): AccountCheckInResult = network {
        withPoolAccount(id, kind, accountId) { AccountCheckIn.perform(this, id, kind) }
    }

    suspend fun checkIn(id: String, kind: AccountProviderKind): AccountCheckInResult = network { withAccountRevision(id) {
        val slots = accounts(id, kind)
        val issues = mutableListOf<AccountPoolIssue>()
        val results = slots.mapNotNull { slot ->
            try { withPoolAccount(id, kind, slot.accountId) { AccountCheckIn.perform(this, id, kind) } }
            catch (error: IOException) {
                if (error is AccountProviderException && error.errorCode == "account_changed") throw error
                if (slots.size == 1) throw error
                issues.add(AccountPoolIssue(slot.accountId, (error as? AccountProviderException)?.errorCode ?: "network_error"))
                null
            }
        }
        if (slots.isEmpty()) throw AccountProviderException("login_required", "Sign in to the account provider", 401)
        AccountCheckInResult(when {
            issues.isNotEmpty() || results.any { it.status == AccountCheckInStatus.UNKNOWN } -> AccountCheckInStatus.UNKNOWN
            results.any { it.status == AccountCheckInStatus.CLAIMED } -> AccountCheckInStatus.CLAIMED
            results.any { it.status == AccountCheckInStatus.ALREADY_CHECKED } -> AccountCheckInStatus.ALREADY_CHECKED
            else -> AccountCheckInStatus.UNSUPPORTED
        }, accountIssues = issues)
    } }

    suspend fun importLoomySession(id: String, session: String) {
        validateId(id)
        if (session.isBlank() || session.length > 32768 || session.any { it.isISOControl() }) throw AccountProviderException("invalid_credentials", "Invalid Loomy session")
        val expected = nextRevision(id)
        network {
            requireAccountVersion(id, expected)
            val credential = AccountCredential(AccountProviderKind.LOOMY, session.trim(), null, null, UUID.randomUUID().toString(), null)
            val payload = readJson(request(endpoints.loomy + "/api/v1/models", headers = headers(credential)))
            if (LoomyAccountProtocol.models(payload).isEmpty()) throw AccountProviderException("empty_catalog", "Loomy session returned no usable models")
            appendCredential(id, expected, credential)
        }
    }

    suspend fun beginLogin(id: String, kind: AccountProviderKind): AccountLogin {
        logins.remove(id)?.close()
        val revision = nextRevision(id)
        val login = AccountProviderLogin.begin(this, id, kind, revision)
        synchronized(state) {
            if (revision(id) != revision) { login.close(); throw AccountProviderException("account_changed", "Account changed during login", 409) }
            logins[id] = login
        }
        return login
    }

    suspend fun awaitLogin(login: AccountLogin) {
        try {
            val credential = AccountProviderLogin.await(this, login)
            coroutineContext.ensureActive()
            network { synchronized(state) {
                if (login.closed.get() || logins[login.id] !== login) throw AccountProviderException("account_changed", "Login cancelled", 409)
                appendCredential(login.id, login.revision, credential)
            } }
        } finally {
            logins.remove(login.id, login)
            login.close()
        }
    }

    fun acceptCallback(login: AccountLogin, url: String): Boolean = logins[login.id] === login && login.accept(url, System.currentTimeMillis())
    fun cancelLogin(login: AccountLogin) {
        synchronized(state) {
            if (logins.remove(login.id, login)) revisions[login.id] = revision(login.id) + 1
            login.close()
        }
    }

    internal suspend fun <T> network(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val operation = Operation()
        continuation.invokeOnCancellation {
            operation.cancelled.set(true)
            operation.calls.forEach { it.cancel() }
        }
        client.dispatcher.executorService.execute {
            operations.set(operation)
            try {
                operation.check()
                val result = block()
                if (continuation.isActive) continuation.resume(result) { _, value, _ -> (value as? java.io.Closeable)?.close() }
                else (result as? java.io.Closeable)?.close()
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { operations.remove() }
        }
    }

    companion object {
        internal const val SENTINEL = "accounts.aharou.invalid"
        internal const val MAX_JSON = 4 * 1024 * 1024
        internal fun traeClientId(kind: AccountProviderKind): String = if (kind == AccountProviderKind.TRAE_GLOBAL) "en1oxy7wnw8j9n" else TRAE_CLIENT_ID
        internal const val TRAE_CLIENT_ID = "ono9krqynydwx5"
        internal val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
