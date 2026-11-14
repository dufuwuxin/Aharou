package com.aharou.feature.settings.data.account

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.buffer
import java.net.URI
import java.security.SecureRandom
import java.util.UUID

/** Normal user website sessions only; no work-mode/local-task runtime or signing secret. */
internal object DoubaoAccountProtocol {
    const val WEB = "https://www.doubao.com"
    const val LOGIN = "$WEB/chat/"
    const val ACCOUNT = "/passport/account/info/v2/"
    const val CATALOG = "/alice/slot/action_bar_v3/brief_list"
    const val CHAT = "/chat/completion"
    private const val BOT = "7338286299411103781"
    private const val VERSION = "2.32.10"

    fun isWebOrigin(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "www.doubao.com" && uri.rawUserInfo == null &&
            uri.port == -1 && uri.rawFragment == null && uri.rawPath == "/chat/" && uri.rawQuery == null
    } catch (_: Exception) { false }

    fun requireCookie(cookie: String): String {
        if (cookie.isBlank() || cookie.length > 32768 || cookie.any { it.code !in 32..126 } ||
            cookie.split(';').none { pair -> pair.trim().let {
                val parts = it.split('=', limit = 2)
                parts.size == 2 && parts[0] in setOf("sessionid", "sessionid_ss", "sid_tt") && parts[1].isNotEmpty()
            } }) throw AccountProviderException("invalid_credentials", "Invalid Doubao website session", 400)
        return cookie
    }

    fun newDeviceId(): String = ((SecureRandom().nextLong() and ((1L shl 53) - 1)).coerceAtLeast(1)).toString()

    fun restore(value: JsonObject): AccountCredential {
        if (value.text("kind") != AccountProviderKind.DOUBAO.wireId) throw AccountProviderException("account_mismatch", "Doubao credentials require their provider kind", 400)
        val cookie = requireCookie(value.text("webCookie").orEmpty())
        val uid = value.text("userId")?.takeIf { it.matches(Regex("[1-9][0-9]{5,24}")) }
            ?: throw AccountProviderException("invalid_credentials", "Doubao account identity is missing", 400)
        val device = value.text("deviceId")?.takeIf { it.toLongOrNull()?.let { value -> value in 1L..9007199254740991L } == true }
            ?: throw AccountProviderException("invalid_credentials", "Doubao device metadata is missing", 400)
        return AccountCredential(AccountProviderKind.DOUBAO, "website-session", null, null, device, uid, webCookie = cookie)
    }

    private fun headers(credential: AccountCredential): Map<String, String> = buildMap {
        val cookie = requireCookie(credential.webCookie.orEmpty())
        put("Cookie", cookie); put("Origin", WEB); put("Referer", LOGIN)
        cookie.split(';').map { it.trim().split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "passport_csrf_token" }?.let { put("x-tt-passport-csrf-token", it[1]) }
    }

    fun request(manager: AccountProviderManager, credential: AccountCredential, path: String, method: String = "GET", body: JsonObject? = null): Response {
        if (credential.kind != AccountProviderKind.DOUBAO || path !in setOf(ACCOUNT, CATALOG, CHAT)) {
            throw AccountProviderException("invalid_gateway", "Unexpected Doubao endpoint", 400)
        }
        val device = credential.deviceId
        if (device.toLongOrNull()?.let { it in 1L..9007199254740991L } != true) throw AccountProviderException("invalid_credentials", "Invalid Doubao device metadata", 400)
        val passport = path == ACCOUNT
        val url = (WEB + path).toHttpUrl().newBuilder().apply {
            mapOf("aid" to if (passport) "497858" else "1044603", "device_platform" to "web", "samantha_web" to "1",
                "version_code" to "20800", "language" to "zh", "region" to "CN", "sys_region" to "CN",
                "web_id" to device, "device_id" to device).forEach { (key, value) -> addQueryParameter(key, value) }
            if (!passport) { addQueryParameter("pc_version", VERSION); addQueryParameter("doubao_pc_version", VERSION) }
        }.build()
        val response = manager.request(url.toString(), method, body, headers(credential) + mapOf("Accept" to if (path == CHAT) "text/event-stream" else "application/json"))
        if (response.code in setOf(401, 403) || response.code in 300..399) {
            response.close()
            throw AccountProviderException("login_required", "Doubao session expired or access was challenged; complete the official website login again", 401)
        }
        return response
    }

    fun exchange(manager: AccountProviderManager, cookie: String, deviceId: String): AccountCredential {
        val provisional = AccountCredential(AccountProviderKind.DOUBAO, "website-session", null, null, deviceId, null, webCookie = requireCookie(cookie))
        val data = manager.readJson(request(manager, provisional, ACCOUNT)).obj("data")
            ?: throw AccountProviderException("login_required", "Doubao account identity is missing", 401)
        val uid = (data.text("user_id_str") ?: data.text("user_id"))?.takeIf { it.matches(Regex("[1-9][0-9]{5,24}")) }
        if (uid == null || data.text("error_code")?.let { it != "0" } == true || data.text("is_visitor_account") in setOf("true", "1")) {
            throw AccountProviderException("login_required", "Sign in to a nonvisitor Doubao account", 401)
        }
        val credential = AccountCredential(AccountProviderKind.DOUBAO, "website-session", null, null, deviceId, uid, webCookie = cookie)
        if (catalog(manager, credential).isEmpty()) throw AccountProviderException("empty_catalog", "Doubao returned no usable chat models")
        return credential
    }

    fun catalog(manager: AccountProviderManager, credential: AccountCredential): List<AccountProviderModel> = models(manager.readJson(
        request(manager, credential, CATALOG, "POST", JsonObject().apply { addProperty("language_code", "zh"); addProperty("bot_id", BOT) }),
    ))

    fun models(payload: JsonObject): List<AccountProviderModel> {
        AccountProviderProtocol.check(payload)
        if (payload.text("code") != "0") throw AccountProviderException("invalid_response", "Doubao model directory has no success status")
        val config = payload.obj("data")?.array("entry_list")?.mapNotNull { it.objectOrNull() }
            ?.firstOrNull { it.text("action_bar_key") == "coco_deep_thinking" }?.obj("active_switch_conf") ?: return emptyList()
        val result = linkedMapOf<String, AccountProviderModel>()
        fun add(item: JsonObject) {
            if (item.text("disabled") in setOf("true", "1") || item.obj("subscribe_config")?.text("need_upgrade") in setOf("true", "1")) return
            val id = item.text("model_item_key") ?: item.text("item_id") ?: return
            val name = item.text("name") ?: return
            val extra = item.obj("model_extra_params")
            val think = if (extra?.text("model_type") == "custom") 10001 else id.toIntOrNull() ?: item.text("item_id")?.toIntOrNull() ?: return
            result[id] = AccountProviderModel(id, name, function = think.toString(), configName = extra?.text("provider_id").orEmpty())
        }
        val v2 = config.obj("menu_conf_v2")
        val modes = v2?.obj("mode_list")?.array("item_list") ?: JsonArray()
        for (mode in modes.mapNotNull { it.objectOrNull() }.filter { it.text("agent_mode") == "2" && it.text("disabled") !in setOf("true", "1") && it.obj("subscribe_config")?.text("need_upgrade") !in setOf("true", "1") }) {
            mode.obj("model_list")?.array("item_list")?.mapNotNull { it.objectOrNull() }?.forEach(::add)
            val supported = mode.array("support_models")
            v2?.obj("model_list")?.array("item_list")?.mapNotNull { it.objectOrNull() }?.filter { item ->
                supported.isEmpty || supported.any { support ->
                    val key = support.objectOrNull()?.let { it.text("model_item_key") ?: it.text("item_id") }
                        ?: support.takeIf { it.isJsonPrimitive }?.asString
                    key == (item.text("model_item_key") ?: item.text("item_id"))
                }
            }?.forEach(::add)
        }
        if (v2 == null) config.obj("menu_conf")?.array("item_list")?.mapNotNull { it.objectOrNull() }
            ?.filter { it.text("agent_mode") == "2" }?.forEach(::add)
        return result.values.toList()
    }

    fun completion(source: JsonObject, model: AccountProviderModel): JsonObject {
        val allowed = setOf("model", "messages", "stream", "stream_options")
        if (source.keySet().any { it !in allowed && !source.get(it).isJsonNull }) throw AccountProviderException("unsupported_capability", "Doubao website chat supports plain text only; tools, images and generation overrides are not supported", 400)
        val messages = source.array("messages")
        if (messages.isEmpty || messages.size() > 256) throw AccountProviderException("unsupported_history", "Doubao requires a bounded plain-text conversation", 400)
        for (row in messages) {
            val message = row.objectOrNull()
            if (message == null || message.text("role") !in setOf("system", "developer", "user", "assistant") ||
                message.keySet().any { it !in setOf("role", "content", "reasoning_content") && !message.get(it).isJsonNull } ||
                message.get("content")?.let { !it.isJsonPrimitive || !it.asJsonPrimitive.isString } != false) {
                throw AccountProviderException("unsupported_history", "Doubao text mode cannot consume tools, tool results or images; use a tool-capable provider for agent tasks", 400)
            }
        }
        if (messages.last().asJsonObject.text("role") != "user") throw AccountProviderException("unsupported_history", "Doubao text conversation must end with a user message", 400)
        // Website chat has no verified role-history API: preserve ordering as an explicit text transcript, not native roles.
        val transcript = "The following JSON is an ordered text conversation transcript, not native session history. Reply to its final user message.\\n" + messages.toString()
        if (transcript.length > 512 * 1024) throw AccountProviderException("request_too_large", "Doubao text transcript exceeds the size limit", 413)
        val now = System.currentTimeMillis()
        val localMessage = UUID.randomUUID().toString()
        val init = JsonObject().apply { addProperty("need_ack_conversation", true) }
        val task = JsonParser.parseString("""{"action":0,"thread_local_message_id":["$localMessage"],"runtime_type":1,"agent_task_param":{"runtime_type":1},"agent_task_param_change":{"runtime_changed":false,"device_changed":false,"sandbox_auth_type_changed":false},"need_modify_conversation":false,"task_input_json":"{\"schema_version\":1,\"project_context\":{}}"}""").asJsonObject
        val think = model.function?.toIntOrNull() ?: throw AccountProviderException("invalid_model", "Doubao model has no native routing metadata", 400)
        return JsonObject().apply {
            add("client_meta", JsonObject().apply {
                addProperty("local_conversation_id", "local_${now}_${UUID.randomUUID()}"); addProperty("conversation_id", "")
                addProperty("bot_id", BOT); addProperty("last_section_id", ""); add("last_message_index", com.google.gson.JsonNull.INSTANCE)
            })
            add("messages", JsonArray().apply { add(JsonObject().apply {
                addProperty("local_message_id", localMessage); addProperty("message_status", 0)
                add("content_block", JsonArray().apply { add(JsonObject().apply {
                    addProperty("block_type", 10000); addProperty("block_id", UUID.randomUUID().toString())
                    add("content", JsonObject().apply { add("text_block", JsonObject().apply { addProperty("text", transcript) }) })
                }) })
            }) })
            add("option", JsonObject().apply {
                addProperty("create_time_ms", now); addProperty("agent_mode", 2); addProperty("need_deep_think", think); addProperty("unique_key", UUID.randomUUID().toString())
                add("recovery_option", JsonObject().apply { addProperty("is_recovery", false); addProperty("req_create_time_sec", now / 1000); addProperty("append_sse_event_scene", 0) })
                addProperty("need_create_conversation", true); addProperty("is_old_user", true); addProperty("message_from", 0)
                add("sse_recv_event_options", JsonObject().apply { addProperty("support_chunk_delta", true) }); add("general_task_param", task)
                add("conversation_init_option", init); add("conversation_init_ext", JsonObject().apply { addProperty("mode_id", "1"); addProperty("model_item_key", model.id) })
                add("model_config", JsonObject().apply { addProperty("model_item_key", model.id) })
                add("aggregate_params", JsonObject().apply { addProperty("provider_id", model.configName.orEmpty()) })
            })
            add("ext", JsonObject().apply {
                addProperty("general_task_param", task.toString()); addProperty("use_deep_think", think.toString()); addProperty("agent_mode", "2")
                addProperty("sub_conv_firstmet_type", "1"); addProperty("collection_id", ""); addProperty("is_finish", "1")
                addProperty("conversation_init_option", init.toString()); addProperty("commerce_credit_config_enable", "0")
            })
            add("user_context", JsonArray())
        }
    }

    private fun chunk(model: String, delta: JsonObject, finish: Boolean = false): JsonObject = JsonObject().apply {
        addProperty("object", "chat.completion.chunk"); addProperty("model", model)
        add("choices", JsonArray().apply { add(JsonObject().apply {
            addProperty("index", 0); add("delta", delta)
            if (finish) addProperty("finish_reason", "stop") else add("finish_reason", com.google.gson.JsonNull.INSTANCE)
        }) })
    }

    fun responseBody(upstream: ResponseBody, model: String, streaming: Boolean): ResponseBody {
        if (upstream.contentType()?.subtype != "event-stream") { upstream.close(); throw AccountProviderException("invalid_stream", "Doubao did not return a native event stream") }
        val converted = NativeSource(upstream, model).buffer()
        if (streaming) return object : ResponseBody() {
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = -1L
            override fun source() = converted
        }
        return converted.use { input ->
            val answer = StringBuilder(); val thinking = StringBuilder()
            while (true) {
                val line = input.readUtf8Line() ?: break
                if (!line.startsWith("data: ") || line == "data: [DONE]") continue
                val delta = JsonParser.parseString(line.removePrefix("data: ")).asJsonObject.array("choices").first().asJsonObject.obj("delta") ?: continue
                delta.text("content")?.let(answer::append); delta.text("reasoning_content")?.let(thinking::append)
            }
            val json = JsonObject().apply {
                addProperty("object", "chat.completion"); addProperty("model", model)
                add("choices", JsonArray().apply { add(JsonObject().apply {
                    addProperty("index", 0); addProperty("finish_reason", "stop")
                    add("message", JsonObject().apply { addProperty("role", "assistant"); addProperty("content", answer.toString()); if (thinking.isNotEmpty()) addProperty("reasoning_content", thinking.toString()) })
                }) })
            }
            ResponseBody.create(AccountProviderManager.JSON, json.toString())
        }
    }

    private class NativeSource(private val upstream: ResponseBody, private val model: String) : Source {
        private val input = upstream.source()
        private val pending = Buffer()
        private var done = false
        private var ack = false
        private var answer = false
        private var total = 0L
        private fun frame() {
            var event = ""
            val data = StringBuilder()
            var size = 0
            while (true) {
                val line = input.readUtf8LineStrict(AccountProviderManager.MAX_JSON.toLong())
                size += line.toByteArray(Charsets.UTF_8).size
                if (size > AccountProviderManager.MAX_JSON) throw AccountProviderException("response_too_large", "Doubao frame exceeds the size limit")
                if (line.isEmpty()) break
                if (line.startsWith("event:")) event = line.substring(6).trim()
                if (line.startsWith("data:")) { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trimStart()) }
            }
            total += size
            if (total > 32L * 1024 * 1024) throw AccountProviderException("response_too_large", "Doubao stream exceeds the size limit")
            if (data.isEmpty()) return
            val payload = try { JsonParser.parseString(data.toString()).asJsonObject } catch (_: RuntimeException) { throw AccountProviderException("invalid_stream", "Doubao returned malformed event data") }
            if (payload.text("code")?.let { it != "0" } == true) throw AccountProviderException("upstream_error", "Doubao rejected the chat request")
            when (event) {
                "SSE_ACK" -> ack = true
                "STREAM_ERROR" -> throw AccountProviderException("upstream_error", "Doubao returned a stream error")
                "FETCH_STREAM" -> throw AccountProviderException("unsupported_capability", "Doubao chat requested a task handoff; work mode is not supported")
                "STREAM_CHUNK" -> {
                    if (!ack) throw AccountProviderException("invalid_stream", "Doubao stream is missing its acknowledgement")
                    for (op in payload.array("patch_op")) for (entry in (op.objectOrNull()?.obj("patch_value")?.array("content_block") ?: JsonArray())) {
                        val block = entry.objectOrNull() ?: continue
                        val content = block.obj("content") ?: continue
                        val delta = JsonObject()
                        when (block.text("block_type")) {
                            "10000" -> content.obj("text_block")?.text("text")?.let { delta.addProperty("content", it); answer = true }
                            "10040" -> content.obj("thinking_block")?.text("content")?.let { delta.addProperty("reasoning_content", it) }
                            else -> throw AccountProviderException("unsupported_capability", "Doubao returned unsupported nontext content")
                        }
                        if (delta.size() > 0) pending.writeUtf8("data: ${chunk(model, delta)}\n\n")
                    }
                }
                "SSE_REPLY_END" -> if (payload.text("end_type") == "3") {
                    if (!ack || !answer) throw AccountProviderException("empty_response", "Doubao completed without an acknowledged text answer")
                    pending.writeUtf8("data: ${chunk(model, JsonObject(), true)}\n\ndata: [DONE]\n\n")
                    done = true
                    upstream.close()
                }
            }
        }
        override fun read(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0)
            if (byteCount == 0L) return 0
            try { while (pending.size == 0L && !done) frame() }
            catch (_: java.io.EOFException) { upstream.close(); throw AccountProviderException("incomplete_stream", "Doubao stream ended before SSE_REPLY_END end_type=3") }
            catch (error: Exception) { upstream.close(); throw error }
            return if (pending.size == 0L && done) -1 else pending.read(sink, byteCount)
        }
        override fun timeout() = input.timeout()
        override fun close() = upstream.close()
    }
}
