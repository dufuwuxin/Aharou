package com.aharou.feature.settings.data.account

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.buffer
import java.net.URI

/**
 * Huawei Cloud CodeArts (码道) website session, cookie based, text only.
 * Route and payload shapes follow the 2026-11 web reverse notes; owner verification is still pending.
 */
internal object CodeArtsAccountProtocol {
    const val WEB = "https://devcloud.cn-north-4.huaweicloud.com"
    const val LOGIN = "$WEB/chat/"
    const val IDENTITY = "/chat/rest/me"
    const val SESSION_CREATE = "/chat/v1/cloudagent/sessions"
    const val MODELS = "/chat/PromptCenterService/v1/agent-center/agents/detail?agent_id=a8bcb36232554267a5142361cc25a393"
    private const val CFTK_COOKIE = "devclouddevuibjtcftk"
    private val SESSION_ROUTE = Regex("^/chat/codebaseservice/v1/cloudagent/sessions/[A-Za-z0-9_-]{1,128}/messages$")

    fun isWebOrigin(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.host == "devcloud.cn-north-4.huaweicloud.com" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() &&
            try { URI(value).rawUserInfo == null } catch (_: java.net.URISyntaxException) { false }
    }

    fun requireCookie(cookie: String): String {
        if (cookie.isBlank() || cookie.length > 32768 || cookie.any { it.code !in 32..126 } ||
            cookie.split(';').none { pair ->
                pair.trim().let {
                    val parts = it.split('=', limit = 2)
                    parts.size == 2 && parts[0] == CFTK_COOKIE && parts[1].isNotEmpty()
                }
            }) throw AccountProviderException("invalid_credentials", "Invalid CodeArts website session", 400)
        return cookie
    }

    private fun cftk(cookie: String): String = cookie.split(';').map { it.trim().split('=', limit = 2) }
        .singleOrNull { it.size == 2 && it[0] == CFTK_COOKIE && it[1].isNotEmpty() }?.get(1)
        ?: throw AccountProviderException("invalid_credentials", "Invalid CodeArts website session", 400)

    fun restore(value: JsonObject): AccountCredential {
        if (value.text("kind") != AccountProviderKind.CODEARTS.wireId || value.text("domain") != WEB) {
            throw AccountProviderException("account_mismatch", "CodeArts credentials require their fixed website", 400)
        }
        val cookie = requireCookie(value.text("webCookie")
            ?: throw AccountProviderException("login_required", "Sign in to CodeArts again", 401))
        val uid = value.text("userId")?.takeIf { it.isNotBlank() && !it.startsWith("cftk-") }
            ?: throw AccountProviderException("invalid_credentials", "CodeArts account identity is missing", 400)
        return AccountCredential(AccountProviderKind.CODEARTS, "website-session", null, null,
            value.text("deviceId") ?: throw AccountProviderException("invalid_credentials", "Missing website device identity", 400),
            uid, WEB, webCookie = cookie)
    }

    private fun headers(credential: AccountCredential, streaming: Boolean): Map<String, String> {
        if (credential.kind != AccountProviderKind.CODEARTS) throw AccountProviderException("invalid_gateway", "Unexpected CodeArts credential", 400)
        return buildMap {
            val cookie = requireCookie(credential.webCookie.orEmpty())
            put("Cookie", cookie)
            put("cftk", cftk(cookie))
            put("Origin", WEB); put("Referer", LOGIN)
            put("User-Agent", AccountProviderProtocol.WEBSITE_USER_AGENT)
            put("Content-Type", "application/json")
            put("x-requested-with", "XMLHttpRequest")
            put("x-codearts-doer-scenario-type", "web")
            put("Accept", if (streaming) "text/event-stream" else "application/json")
        }
    }

    private fun rotatedCookie(credential: AccountCredential, response: Response): String? {
        val loginUrl = LOGIN.toHttpUrlOrNull() ?: return null
        val values = linkedMapOf<String, String>()
        requireCookie(credential.webCookie.orEmpty()).split(';').forEach { pair ->
            val parts = pair.trim().split('=', limit = 2)
            if (parts.size == 2) values[parts[0]] = parts[1]
        }
        var changed = false
        okhttp3.Cookie.parseAll(response.request.url, response.headers).forEach { cookie ->
            if (cookie.matches(loginUrl)) {
                changed = true
                values.remove(cookie.name)
                if (cookie.expiresAt > System.currentTimeMillis()) values[cookie.name] = cookie.value
            }
        }
        if (!changed) return null
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }.takeIf { runCatching { requireCookie(it) }.isSuccess }
    }

    fun request(manager: AccountProviderManager, credential: AccountCredential, path: String, method: String = "GET",
        body: JsonObject? = null, streaming: Boolean = false): Response {
        if (credential.kind != AccountProviderKind.CODEARTS ||
            (path !in setOf(IDENTITY, SESSION_CREATE, MODELS) && !SESSION_ROUTE.matches(path))) {
            throw AccountProviderException("invalid_gateway", "Unexpected CodeArts route", 400)
        }
        manager.checkRequestActive()
        val extra = when (path) {
            MODELS -> mapOf("Agent-Type" to "AgentCenter")
            SESSION_CREATE -> mapOf("Agent-Type" to "CodeBase")
            else -> emptyMap()
        }
        val response = manager.request(WEB + path, method, body, headers(credential, streaming) + extra, streaming = streaming)
        rotatedCookie(credential, response)?.let { manager.persistRotatedWebsiteCookie(credential, it) }
        if (!response.isSuccessful) {
            val status = response.code
            val code = try {
                val payload = response.peekBody(4096).string().let(JsonParser::parseString).objectOrNull()
                payload?.text("error_code")?.takeIf { it.matches(Regex("(?:CODEBASE|APIG|IAM)\\.[A-Za-z0-9.]{1,64}")) }
            } catch (_: Exception) { null } finally { response.close() }
            val stage = when (path) {
                IDENTITY -> "identity"
                MODELS -> "catalog"
                SESSION_CREATE -> "create_session"
                else -> "send_message"
            }
            throw AccountProviderException(if (status == 401 || status == 403) "login_required" else "upstream_http",
                "CodeArts $stage returned HTTP $status${code?.let { " ($it)" }.orEmpty()}", if (status in 400..499) status else 502)
        }
        return response
    }

    fun models(payload: JsonObject): List<AccountProviderModel> {
        AccountProviderProtocol.check(payload)
        val data = payload.obj("data") ?: payload
        val container = data.obj("data") ?: data
        return container.obj("gpts")?.array("models")?.mapNotNull { element ->
            val entry = element.objectOrNull() ?: return@mapNotNull null
            val parameters = entry.obj("model_parameters") ?: JsonObject()
            if (parameters.get("display_enabled")?.toString() == "false" || parameters.get("enable")?.toString() == "false") return@mapNotNull null
            val name = entry.text("model_name")
            if (name in setOf("GLM-5.1", "Qwen3-VL-235B", "maas-glm-4.7")) return@mapNotNull null
            val id = parameters.text("model_id") ?: parameters.text("id") ?: parameters.text("name") ?: name ?: return@mapNotNull null
            AccountProviderModel(id, name ?: id)
        }?.distinctBy { it.id }.orEmpty()
    }

    fun exchange(manager: AccountProviderManager, cookie: String, deviceId: String): AccountCredential {
        val session = requireCookie(cookie)
        val provisional = AccountCredential(AccountProviderKind.CODEARTS, "website-session", null, null, deviceId, null, WEB, webCookie = session)
        val identityResponse = request(manager, provisional, IDENTITY)
        val identityCookie = rotatedCookie(provisional, identityResponse)
        val identity = manager.readJson(identityResponse)
        val uid = identity.text("userId")?.takeIf { it.isNotBlank() }
            ?: throw AccountProviderException("invalid_credentials", "CodeArts account identity is missing", 401)
        val identified = AccountCredential(AccountProviderKind.CODEARTS, "website-session", null, null, deviceId, uid, WEB,
            webCookie = identityCookie ?: session)
        val catalogResponse = request(manager, identified, MODELS)
        val catalogCookie = rotatedCookie(identified, catalogResponse)
        if (models(manager.readJson(catalogResponse)).isEmpty()) throw AccountProviderException("empty_catalog", "CodeArts returned no usable builtin models")
        return AccountCredential(AccountProviderKind.CODEARTS, identified.accessToken, identified.refreshToken, identified.expiresAt,
            identified.deviceId, identified.userId, identified.domain, identified.enterpriseId,
            webCookie = catalogCookie ?: identified.webCookie)
    }

    private fun prompt(source: JsonObject): String {
        val unsupported = source.entrySet().any { (key, value) ->
            key !in setOf("model", "messages", "stream", "stream_options") && !value.isJsonNull
        }
        if (unsupported) throw AccountProviderException("unsupported_capability", "CodeArts website mode supports text only; tools and generation options are unavailable", 400)
        val messages = source.array("messages")
        if (messages.isEmpty) throw AccountProviderException("invalid_request", "Text messages are required", 400)
        val text = messages.joinToString("\n\n") { element ->
            val message = element.objectOrNull() ?: throw AccountProviderException("invalid_request", "Invalid message", 400)
            val role = message.text("role")
            if (role !in setOf("system", "user", "assistant") || message.has("tool_calls") || message.has("tool_call_id")) {
                throw AccountProviderException("unsupported_capability", "CodeArts website mode cannot replay tool messages", 400)
            }
            val content = message.get("content")
            val value = when {
                content?.isJsonPrimitive == true && content.asJsonPrimitive.isString -> content.asString
                content?.isJsonArray == true -> content.asJsonArray.joinToString("\n") { part ->
                    val item = part.objectOrNull()
                    if (item?.text("type") != "text") throw AccountProviderException("unsupported_capability", "CodeArts website mode accepts text only", 400)
                    item.rawText("text").orEmpty()
                }
                else -> throw AccountProviderException("unsupported_capability", "CodeArts website mode accepts text only", 400)
            }
            "$role: $value"
        }
        if (text.length > 256 * 1024) throw AccountProviderException("request_too_large", "CodeArts text exceeds the size limit", 413)
        return text
    }

    fun complete(manager: AccountProviderManager, id: String, source: JsonObject, model: String): Response =
        manager.withWebsiteCookieLock(AccountProviderKind.CODEARTS) {
            val text = prompt(source)
            var credential = manager.credential(id, AccountProviderKind.CODEARTS)
            val created = manager.readJson(request(manager, credential, SESSION_CREATE, "POST", JsonObject()))
            credential = manager.credential(id, AccountProviderKind.CODEARTS)
            val session = created.obj("result")?.text("session_id")
                ?: throw AccountProviderException("invalid_response", "CodeArts returned no session ID")
            if (!session.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw AccountProviderException("invalid_response", "CodeArts returned an invalid session ID")
            request(manager, credential, "/chat/codebaseservice/v1/cloudagent/sessions/$session/messages", "POST", JsonObject().apply {
                addProperty("content", text)
                addProperty("model_id", model)
                add("repos", JsonArray())
            }, streaming = true)
        }

    fun sseBody(upstream: ResponseBody, model: String): ResponseBody {
        if (upstream.contentType()?.subtype?.contains("event-stream") != true) {
            upstream.close()
            throw AccountProviderException("invalid_stream", "CodeArts did not return an event stream")
        }
        val source = Events(upstream, model).buffer()
        return object : ResponseBody() {
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = -1L
            override fun source() = source
        }
    }

    private class Events(private val upstream: ResponseBody, private val model: String) : Source {
        private val input = upstream.source()
        private val pending = Buffer()
        private var finished = false
        private var answered = false
        private var total = 0L

        private fun chunk(text: String? = null, stop: Boolean = false) {
            val delta = JsonObject().apply { if (text != null) addProperty("content", text) }
            val value = JsonObject().apply {
                addProperty("id", "chatcmpl-codearts"); addProperty("object", "chat.completion.chunk"); addProperty("model", model)
                add("choices", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("index", 0); add("delta", delta); if (stop) addProperty("finish_reason", "stop")
                    })
                })
            }
            pending.writeUtf8("data: $value\n\n")
        }

        private fun text(value: String) {
            if (value.isEmpty()) return
            answered = true
            chunk(value)
        }

        private fun end() {
            if (finished) return
            if (!answered) throw AccountProviderException("empty_response", "CodeArts returned no text")
            finished = true
            chunk(stop = true)
            pending.writeUtf8("data: [DONE]\n\n")
        }

        private fun frame() {
            var event = ""
            val data = StringBuilder()
            while (true) {
                if (input.exhausted()) throw AccountProviderException("truncated_stream", "CodeArts stream ended without completion")
                val line = input.readUtf8LineStrict(512 * 1024)
                total += line.length
                if (total > 16 * 1024 * 1024) throw AccountProviderException("response_too_large", "CodeArts stream exceeds the size limit")
                if (line.isEmpty()) break
                if (line.startsWith("event:")) event = line.substring(6).trim()
                if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
            }
            if (event == "error") throw AccountProviderException("upstream_error", "CodeArts stream failed")
            if (data.isNotEmpty()) {
                val item = try { JsonParser.parseString(data.toString()).asJsonObject } catch (_: RuntimeException) {
                    throw AccountProviderException("invalid_stream", "Invalid CodeArts event")
                }
                AccountProviderProtocol.check(item)
                when (event) {
                    "message" -> if (item.text("subagent_id").isNullOrBlank()) item.rawText("content")?.let(::text)
                    "done" -> end()
                    "tool_authorization" -> throw AccountProviderException("interaction_required", "CodeArts requires approval in its website")
                }
            }
        }

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (byteCount == 0L) return 0
            try {
                while (pending.size == 0L && !finished) frame()
                return if (pending.size == 0L) -1 else pending.read(sink, byteCount)
            } catch (error: Exception) { upstream.close(); throw error }
        }
        override fun timeout() = input.timeout()
        override fun close() = upstream.close()
    }
}
