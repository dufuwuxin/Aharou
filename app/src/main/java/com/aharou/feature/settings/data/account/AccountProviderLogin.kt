package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class AccountLogin internal constructor(
    internal val id: String,
    internal val kind: AccountProviderKind,
    val url: String,
    val callbackPrefix: String?,
    internal val state: String,
    internal val revision: Long,
    internal val expiresAt: Long,
    internal val verifier: String,
    internal val deviceId: String,
    internal val server: ServerSocket? = null,
    internal val devicePublicKey: String? = null,
    internal val cookies: LoginCookies = LoginCookies(),
) {
    private val consumed = AtomicBoolean(false)
    internal val closed = AtomicBoolean(false)
    private val expiryTimer = if (server != null) java.util.Timer("AccountLoginExpiry", true).apply {
        schedule(object : java.util.TimerTask() { override fun run() { close() } }, (expiresAt - System.currentTimeMillis()).coerceAtLeast(1L))
    } else null
    @Volatile internal var code: String? = null

    internal fun accept(value: String, now: Long): Boolean {
        if (closed.get() || consumed.get() || now >= expiresAt || value.length > 16384) return false
        val expected = callbackPrefix ?: return false
        return try {
            val uri = URI(value)
            val target = URI(expected)
            if (uri.scheme != target.scheme || uri.host != target.host || uri.port != target.port || uri.rawPath != target.rawPath || uri.userInfo != null || uri.fragment != null) return false
            val pairs = (uri.rawQuery ?: return false).split('&').map {
                val parts = it.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
            if (pairs.map { it.first }.distinct().size != pairs.size) return false
            val params = pairs.toMap()
            val stateMatches = params["state"] == state || (kind.isTrae && server != null && params["state"] == null)
            if (!stateMatches || params.keys.any { it in setOf("error", "error_code", "refreshToken", "refresh_token", "access_token") }) return false
            val authCode = params["code"] ?: params["authCode"] ?: params["authorization_code"] ?: return false
            if (authCode.isBlank() || authCode.length > 8192 || !consumed.compareAndSet(false, true)) return false
            code = authCode
            true
        } catch (_: Exception) { false }
    }

    internal fun close() {
        closed.set(true)
        expiryTimer?.cancel()
        cookies.clear()
        try { server?.close() } catch (_: java.io.IOException) { }
    }
    override fun toString(): String = "AccountLogin(${kind.wireId}, redacted)"
}

internal class LoginCookies {
    private val values = mutableListOf<okhttp3.Cookie>()
    @Synchronized fun absorb(response: okhttp3.Response) {
        val incoming = okhttp3.Cookie.parseAll(response.request.url, response.headers)
        for (cookie in incoming) {
            values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            values.add(cookie)
        }
    }
    @Synchronized fun headers(url: String): Map<String, String> {
        val target = url.toHttpUrl()
        val active = values.filter { it.matches(target) && it.expiresAt > System.currentTimeMillis() }
        return if (active.isEmpty()) emptyMap() else mapOf("Cookie" to active.joinToString("; ") { "${it.name}=${it.value}" })
    }
    @Synchronized fun clear() = values.clear()
}

internal object AccountProviderLogin {
    private fun nonce(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    suspend fun begin(manager: AccountProviderManager, id: String, kind: AccountProviderKind, revision: Long): AccountLogin {
        if (kind == AccountProviderKind.LOOMY) throw AccountProviderException("session_import_required", "Loomy requires a user-provided session", 400)
        val state = nonce()
        val verifier = nonce()
        val deviceId = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + 10 * 60_000
        if (kind.isWorkBuddy) {
            val cookies = LoginCookies()
            val data = manager.network {
                val response = manager.request(manager.endpoints.workbuddy(kind) + "/v2/plugin/auth/state?platform=CLI", "POST", headers = workbuddyLoginHeaders(kind))
                cookies.absorb(response)
                manager.readJson(response).let { it.obj("data") ?: it }
            }
            val remoteState = data.text("state") ?: data.text("authState") ?: throw AccountProviderException("invalid_login", "Login service did not return state")
            val url = data.text("authUrl") ?: throw AccountProviderException("invalid_login", "Login service did not return an authorization URL")
            val target = url.toHttpUrl()
            val allowed = if (kind == AccountProviderKind.WORKBUDDY_GLOBAL) setOf("www.workbuddy.ai") else setOf("www.workbuddy.cn", "copilot.tencent.com", "accounts.tencent.com", "www.codebuddy.cn", "workbuddy.cn")
            if (target.scheme != "https" || target.host !in allowed || target.username.isNotEmpty() || target.password.isNotEmpty()) throw AccountProviderException("invalid_login", "Unexpected authorization website")
            return AccountLogin(id, kind, url, null, remoteState, revision, expiresAt, verifier, deviceId, cookies = cookies)
        }
        if (kind == AccountProviderKind.RACCOON) {
            val url = (manager.endpoints.raccoon + "/authorize").toHttpUrl().newBuilder()
                .addQueryParameter("login_source", "desktop").addQueryParameter("appname", "办公小浣熊客户端").addQueryParameter("state", state).build()
            return AccountLogin(id, kind, url.toString(), "office-raccoon://auth/callback", state, revision, expiresAt, verifier, deviceId)
        }
        val server = manager.network { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 250 } }
        try {
            val keyPair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val publicKey = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(keyPair.public.encoded) + "\n-----END PUBLIC KEY-----\n"
            val prefix = "http://127.0.0.1:${server.localPort}/authorize"
            val callback = "$prefix?state=$state"
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            val url = (if (kind == AccountProviderKind.TRAE_GLOBAL) "https://www.trae.ai/authorization" else "https://www.trae.cn/authorization").toHttpUrl().newBuilder()
                .addQueryParameter("login_version", "1").addQueryParameter("auth_from", "trae").addQueryParameter("login_channel", "native_ide")
                .addQueryParameter("auth_type", "local").addQueryParameter("client_id", AccountProviderManager.traeClientId(kind))
                .addQueryParameter("redirect", "0").addQueryParameter("login_trace_id", UUID.randomUUID().toString())
                .addQueryParameter("auth_callback_url", callback).addQueryParameter("machine_id", deviceId).addQueryParameter("device_id", deviceId)
                .addQueryParameter("code_challenge", challenge).addQueryParameter("code_challenge_method", "S256").build()
            val authorizationUrl = url.toString().replace(Regex("auth_callback_url=[^&]*"), "auth_callback_url=$prefix") + "&state=$state"
            return AccountLogin(id, kind, authorizationUrl, prefix, state, revision, expiresAt, verifier, deviceId, server, publicKey)
        } catch (error: Exception) { server.close(); throw error }
    }

    suspend fun await(manager: AccountProviderManager, login: AccountLogin): AccountCredential {
        while (System.currentTimeMillis() < login.expiresAt && !login.closed.get()) {
            coroutineContext.ensureActive()
            if (login.kind.isWorkBuddy) {
                val response = manager.network {
                    val url = (manager.endpoints.workbuddy(login.kind) + "/v2/plugin/auth/token").toHttpUrl().newBuilder().addQueryParameter("state", login.state).build()
                    val response = manager.request(url.toString(), headers = workbuddyLoginHeaders(login.kind) + login.cookies.headers(url.toString()))
                    login.cookies.absorb(response)
                    manager.readJson(response, allowLoginPending = true)
                }
                val data = response.obj("data") ?: response
                if (data.text("accessToken") != null) {
                    return manager.network {
                        val token = data.text("accessToken")!!
                        val accountUrl = (manager.endpoints.workbuddy(login.kind) + "/v2/plugin/login/account").toHttpUrl().newBuilder().addQueryParameter("state", login.state).build().toString()
                        val accountResponse = manager.request(accountUrl, headers = workbuddyLoginHeaders(login.kind) + login.cookies.headers(accountUrl) + mapOf("Authorization" to "Bearer $token"))
                        login.cookies.absorb(accountResponse)
                        val account = manager.readJson(accountResponse).let { it.obj("data") ?: it }
                        val combined = data.deepCopy().apply {
                            account.text("uid")?.let { addProperty("userId", it) }
                            account.text("enterpriseId")?.let { addProperty("enterpriseId", it) }
                        }
                        manager.parseCredential(login.kind, combined, deviceId = login.deviceId)
                    }
                }
            } else {
                if (login.server != null && login.code == null) manager.network { readLoopback(login) }
                val code = login.code
                if (code != null) {
                    login.server?.close()
                    return manager.network {
                        val body = JsonObject()
                        val url = when (login.kind) {
                            AccountProviderKind.RACCOON -> {
                                body.addProperty("authorization_code", code)
                                manager.endpoints.raccoon + "/api/web/auth/v1/login_with_authorization_code"
                            }
                            else -> {
                                body.addProperty("ClientID", AccountProviderManager.traeClientId(login.kind))
                                body.addProperty("AuthCode", code)
                                body.addProperty("CodeVerifier", login.verifier)
                                body.addProperty("IDEVersion", "0.1.69")
                                body.add("DeviceInfo", JsonObject().apply {
                                    addProperty("DeviceID", login.deviceId); addProperty("MachineID", login.deviceId)
                                    addProperty("PlatformCode", "IDE_PC"); addProperty("DeviceType", "PC"); addProperty("DeviceName", "Aharou")
                                    addProperty("ClientVersion", "0.1.69"); addProperty("DevicePublicKey", login.devicePublicKey)
                                })
                                manager.endpoints.traeAccount(login.kind) + "/trae/api/v3/oauth/ExchangeToken"
                            }
                        }
                        manager.parseCredential(login.kind, manager.readJson(manager.request(url, "POST", body)), deviceId = login.deviceId)
                    }
                }
            }
            delay(if (login.kind.isWorkBuddy) 1500 else 150)
        }
        throw AccountProviderException("login_expired", "Login cancelled or expired; start a new login", 408)
    }

    private fun workbuddyLoginHeaders(kind: AccountProviderKind): Map<String, String> = mapOf(
        "Origin" to (if (kind == AccountProviderKind.WORKBUDDY_GLOBAL) "https://www.workbuddy.ai" else "https://www.codebuddy.cn"), "Referer" to (if (kind == AccountProviderKind.WORKBUDDY_GLOBAL) "https://www.workbuddy.ai/" else "https://www.codebuddy.cn/"), "X-Requested-With" to "XMLHttpRequest",
        "User-Agent" to "CLI/2.63.2 CodeBuddy/2.63.2 Aharou/1.0",
    )

    private fun readLoopback(login: AccountLogin) {
        val server = login.server ?: return
        try {
            server.accept().use { socket ->
                socket.soTimeout = 1500
                val input = socket.getInputStream()
                val line = StringBuilder()
                while (line.length < 16384) {
                    val byte = input.read()
                    if (byte == -1 || byte == 10) break
                    line.append(byte.toChar())
                }
                val request = line.toString().trim().split(' ')
                val path = request.getOrNull(1)
                val valid = request.firstOrNull() == "GET" && path != null && path.startsWith("/authorize?") && login.accept("http://127.0.0.1:${server.localPort}$path", System.currentTimeMillis())
                val body = if (valid) "Login callback received. You may return to Aharou." else "Invalid or expired login callback."
                val status = if (valid) "200 OK" else "400 Bad Request"
                socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
            }
        } catch (_: SocketTimeoutException) { }
    }
}
