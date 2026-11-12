package com.aharou.feature.settings.data.account

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import java.io.IOException

class AccountGatewayInterceptor internal constructor(private val manager: AccountProviderManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != AccountProviderManager.SENTINEL) return chain.proceed(request)
        return try {
            manager.withParentCall(chain.call()) {
                val id = request.url.pathSegments.getOrNull(1) ?: throw AccountProviderException("invalid_gateway", "Missing account ID", 400)
                manager.withAccountRevision(id) { dispatch(request) }
            }
        } catch (error: AccountProviderException) {
            error(request, error.httpStatus, error.errorCode, error.message ?: "Account provider request failed")
        } catch (_: IOException) {
            if (chain.call().isCanceled()) throw IOException("Account request cancelled")
            error(request, 502, "network_error", "Unable to reach the account provider")
        } catch (_: RuntimeException) {
            error(request, 400, "invalid_request", "Invalid account provider request")
        }
    }

    private fun dispatch(request: Request): Response {
        val url = request.url
        val segments = url.pathSegments
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null || segments.size !in 4..5 || segments.getOrNull(2) != "v1") {
            throw AccountProviderException("invalid_gateway", "Invalid account gateway URL", 400)
        }
        val kind = AccountProviderKind.fromWireId(segments[0]) ?: throw AccountProviderException("invalid_provider", "Unknown account provider", 400)
        val id = segments[1]
        val endpoint = segments.drop(3).joinToString("/")
        if (endpoint == "models" && request.method == "GET") {
            val models = manager.catalog(id, kind)
            val result = JsonObject().apply {
                addProperty("object", "list")
                add("data", JsonArray().apply { models.forEach { model -> add(JsonObject().apply { addProperty("id", model.id); addProperty("object", "model"); addProperty("owned_by", kind.wireId) }) } })
            }
            return response(request, ResponseBody.create(AccountProviderManager.JSON, result.toString()))
        }
        if (endpoint != "chat/completions" || request.method != "POST") throw AccountProviderException("unsupported_endpoint", "Account gateway only supports models and chat/completions", 404)
        val body = readRequest(request)
        val modelId = body.text("model") ?: throw AccountProviderException("invalid_model", "A model ID is required", 400)
        val model = manager.catalog(id, kind).firstOrNull { it.id == modelId } ?: throw AccountProviderException("unknown_model", "Model is absent from the current provider catalog", 400)
        val streaming = body.get("stream")?.asBoolean == true
        val upstreamBody = when (kind) {
            AccountProviderKind.TRAE -> TraeAccountProtocol.request(body, model)
            AccountProviderKind.WORKBUDDY -> body.deepCopy().apply { addProperty("stream", true); remove("stream_options") }
            else -> body.deepCopy().apply { remove("reasoning_effort") }
        }
        val upstreamUrl = when (kind) {
            AccountProviderKind.RACCOON -> manager.endpoints.raccoon + "/api/web/llm/v2/chat/completions"
            AccountProviderKind.WORKBUDDY -> manager.endpoints.workbuddy + "/v2/chat/completions"
            AccountProviderKind.TRAE -> manager.endpoints.trae + "/api/agent/v3/llm_utils_chat"
        }
        val upstream = manager.authorized(id, kind, upstreamUrl, "POST", upstreamBody)
        if (!upstream.isSuccessful) {
            val status = upstream.code
            manager.readJson(upstream)
            throw AccountProviderException("upstream_http", "Account provider returned HTTP $status", if (status in setOf(401, 402, 403, 429)) status else 502)
        }
        val rawBody = upstream.body ?: run { upstream.close(); throw AccountProviderException("empty_response", "Account provider returned no body") }
        try {
            if (kind == AccountProviderKind.TRAE) {
                if (rawBody.contentType()?.subtype?.contains("json") == true) {
                    manager.readJson(upstream)
                    throw AccountProviderException("invalid_stream", "Trae returned JSON instead of its native event stream")
                }
                return response(request, TraeAccountProtocol.responseBody(rawBody, modelId, streaming))
            }
            if (rawBody.contentType()?.subtype?.contains("event-stream") != true) {
                val json = manager.readJson(upstream)
                if (json.array("choices").isEmpty) throw AccountProviderException("empty_response", "Account provider returned no completion")
                return response(request, ResponseBody.create(AccountProviderManager.JSON, json.toString()))
            }
            val checked = CheckedOpenAiBody(rawBody)
            return response(request, if (streaming) checked else aggregate(checked, modelId))
        } catch (error: Exception) { upstream.close(); throw error }
    }

    private fun aggregate(body: ResponseBody, model: String): ResponseBody = body.use {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = sortedMapOf<Int, JsonObject>()
        var reason = "stop"
        var usage: JsonObject? = null
        val source = it.source()
        while (!source.exhausted()) {
            val line = source.readUtf8LineStrict(512 * 1024)
            if (!line.startsWith("data: ") || line == "data: [DONE]") continue
            val chunk = JsonParser.parseString(line.substring(6)).asJsonObject
            chunk.obj("usage")?.let { value -> usage = value }
            val choice = chunk.array("choices").firstOrNull()?.objectOrNull() ?: continue
            choice.text("finish_reason")?.let { value -> reason = value }
            val delta = choice.obj("delta") ?: continue
            delta.text("content")?.let(text::append)
            delta.text("reasoning_content")?.let(reasoning::append)
            delta.array("tool_calls").forEach { element ->
                val incoming = element.asJsonObject
                val index = incoming.get("index")?.asInt ?: throw AccountProviderException("invalid_stream", "Tool fragment has no index")
                val call = calls.getOrPut(index) { JsonObject().apply { addProperty("type", "function"); add("function", JsonObject()) } }
                incoming.text("id")?.let { id -> call.addProperty("id", id) }
                incoming.obj("function")?.let { function ->
                    val target = call.obj("function")!!
                    listOf("name", "arguments").forEach { key -> function.text(key)?.let { value -> target.addProperty(key, target.text(key).orEmpty() + value) } }
                }
            }
        }
        val message = JsonObject().apply {
            addProperty("role", "assistant"); addProperty("content", text.toString())
            if (reasoning.isNotEmpty()) addProperty("reasoning_content", reasoning.toString())
            if (calls.isNotEmpty()) add("tool_calls", JsonArray().apply { calls.values.forEach { value -> add(value) } })
        }
        val result = JsonObject().apply {
            addProperty("id", "chatcmpl-account"); addProperty("object", "chat.completion"); addProperty("model", model)
            add("choices", JsonArray().apply { add(JsonObject().apply { addProperty("index", 0); add("message", message); addProperty("finish_reason", if (calls.isNotEmpty() && reason == "stop") "tool_calls" else reason) }) })
            usage?.let { value -> add("usage", value) }
        }
        ResponseBody.create(AccountProviderManager.JSON, result.toString())
    }

    private fun readRequest(request: Request): JsonObject {
        val body = request.body ?: throw AccountProviderException("invalid_request", "A request body is required", 400)
        if (body.contentLength() > AccountProviderManager.MAX_JSON) throw AccountProviderException("request_too_large", "Account request exceeds the size limit", 413)
        val buffer = Buffer()
        var count = 0L
        val sink = object : ForwardingSink(buffer) {
            override fun write(source: Buffer, byteCount: Long) {
                count += byteCount
                if (count > AccountProviderManager.MAX_JSON) throw AccountProviderException("request_too_large", "Account request exceeds the size limit", 413)
                super.write(source, byteCount)
            }
        }.buffer()
        body.writeTo(sink)
        sink.flush()
        return try { JsonParser.parseString(buffer.readUtf8()).asJsonObject } catch (_: RuntimeException) { throw AccountProviderException("invalid_request", "Request body must be a JSON object", 400) }
    }

    private fun response(request: Request, body: ResponseBody): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .header("Content-Type", body.contentType()?.toString() ?: "application/json").body(body).build()

    private fun error(request: Request, status: Int, code: String, message: String): Response {
        val json = JsonObject().apply { add("error", JsonObject().apply { addProperty("type", "account_provider_error"); addProperty("code", code); addProperty("message", message) }) }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Account provider error")
            .body(ResponseBody.create("application/json".toMediaType(), json.toString())).build()
    }

    private class CheckedOpenAiBody(private val upstream: ResponseBody) : ResponseBody() {
        private val converted = object : okio.Source {
            val source = upstream.source()
            val pending = Buffer()
            var done = false
            var sawAnswer = false
            var total = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (byteCount == 0L) return 0
                while (pending.size == 0L && !done) {
                    val line = try { source.readUtf8LineStrict(512 * 1024) } catch (_: java.io.EOFException) { throw AccountProviderException("truncated_stream", "Account provider stream ended before completion") }
                    total += line.length
                    if (total > 32 * 1024 * 1024) throw AccountProviderException("response_too_large", "Account provider stream exceeds the size limit")
                    if (line.startsWith("data:")) {
                        val data = line.substring(5).trim()
                        if (data == "[DONE]") {
                            if (!sawAnswer) throw AccountProviderException("empty_response", "Account provider completed without an answer")
                            done = true
                        } else if (data.isNotEmpty()) {
                            val json = try { JsonParser.parseString(data).asJsonObject } catch (_: RuntimeException) { throw AccountProviderException("invalid_stream", "Account provider returned an invalid stream") }
                            AccountProviderProtocol.check(json)
                            if (json.array("choices").any { value ->
                                    val delta = value.objectOrNull()?.obj("delta")
                                    delta?.text("content") != null || delta?.text("reasoning_content") != null || delta?.array("tool_calls")?.isEmpty == false
                                }) sawAnswer = true
                        }
                    }
                    pending.writeUtf8(line + "\n")
                    if (done) pending.writeUtf8("\n")
                }
                return if (pending.size == 0L) -1 else pending.read(sink, byteCount)
            }
            override fun timeout() = source.timeout()
            override fun close() = upstream.close()
        }.buffer()
        override fun contentType() = "text/event-stream".toMediaType()
        override fun contentLength() = -1L
        override fun source() = converted
    }
}
