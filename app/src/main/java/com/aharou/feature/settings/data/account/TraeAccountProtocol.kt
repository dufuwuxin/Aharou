package com.aharou.feature.settings.data.account

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.EOFException
import java.util.UUID

internal object TraeAccountProtocol {
    fun balance(payload: JsonObject): String? {
        val data = payload.obj("data") ?: payload.obj("result") ?: payload.obj("Result") ?: payload
        if (!data.has("user_entitlement_pack_list")) return null
        val packs = data.array("user_entitlement_pack_list")
        if (packs.isEmpty) return null
        var total = java.math.BigDecimal.ZERO
        var consumed = java.math.BigDecimal.ZERO
        for (entry in packs) {
            val pack = entry.objectOrNull() ?: return null
            val quota = pack.obj("entitlement_base_info")?.obj("quota") ?: return null
            val limit = number(quota.get("credits_limit"))?.toBigDecimal() ?: return null
            val usage = number(pack.obj("usage")?.get("credits_amount"))?.toBigDecimal() ?: return null
            total += limit
            consumed += usage
        }
        return "${total.subtract(consumed).stripTrailingZeros().toPlainString()} / ${total.stripTrailingZeros().toPlainString()} credits"
    }

    fun models(payload: JsonObject): List<AccountProviderModel> {
        val data = payload.obj("data") ?: payload
        val groups = data.array("function_configs").mapNotNull { it.objectOrNull() }.ifEmpty {
            listOf(JsonObject().apply { addProperty("function", "chat_v3"); add("config_info_list", data.array("config_info_list")) })
        }
        return groups.flatMap { group ->
            val function = group.text("function") ?: return@flatMap emptyList()
            group.array("config_info_list").mapNotNull inner@ { element ->
                val entry = element.objectOrNull() ?: return@inner null
                if (entry.get("config_switch")?.toString() == "false" || entry.get("is_custom_model")?.toString() == "true") return@inner null
                if (entry.text("usage") != null && entry.text("usage") != "chat_completion") return@inner null
                val config = entry.text("config_name") ?: return@inner null
                val display = embedded(entry.get("display_config"))
                val detail = entry.array("model_detail_list").mapNotNull { it.objectOrNull() }.firstOrNull { it.text("model_name")?.endsWith("__dev") == true }
                    ?: entry.array("model_detail_list").firstOrNull()?.objectOrNull()
                val consumption = embedded(entry.get("display_contact_config"))?.let { it.obj("consumption_rate") ?: it.obj("ConsumptionRate") }
                val rate = if (consumption?.get("enable")?.toString() == "false") null else (consumption?.obj("data") ?: consumption?.obj("Data"))?.let { number(it.get("rate")) ?: number(it.get("Rate")) }
                AccountProviderModel(config, display?.text("display_name") ?: entry.text("display_model_name") ?: config, rate, function, config, detail?.text("model_name"))
            }
        }.distinctBy { it.id }
    }

    fun request(source: JsonObject, route: AccountProviderModel): JsonObject {
        val function = route.function ?: throw AccountProviderException("invalid_model", "Trae model has no verified route", 400)
        val config = route.configName ?: throw AccountProviderException("invalid_model", "Trae model has no verified config", 400)
        val messages = source.array("messages").deepCopy()
        if (messages.isEmpty) throw AccountProviderException("invalid_request", "Messages must not be empty", 400)
        val callIds = HashSet<String>()
        for (element in messages) {
            val message = element.objectOrNull() ?: throw AccountProviderException("invalid_request", "Message must be an object", 400)
            val calls = message.array("tool_calls")
            for (callElement in calls) {
                val call = callElement.objectOrNull() ?: throw AccountProviderException("invalid_request", "Tool call must be an object", 400)
                call.text("id")?.let { callIds.add(it) }
                call.remove("function")?.let { call.add("function_call", it) }
            }
            if (message.text("role") == "tool" && message.text("tool_call_id") !in callIds) throw AccountProviderException("unmatched_tool_result", "Tool result has no matching call ID", 400)
            val content = message.get("content")
            if (content != null && content.isJsonPrimitive && content.asJsonPrimitive.isString) {
                message.add("content", JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", content.asString) }) })
            } else if (content == null || content.isJsonNull) {
                message.add("content", JsonArray())
            }
        }
        val result = JsonObject().apply {
            add("messages", messages); addProperty("function", function); addProperty("config_name", config); addProperty("model", config)
            route.modelName?.let { addProperty("model_name", it) }
            addProperty("stream", true); addProperty("session_id", UUID.randomUUID().toString()); addProperty("request_id", UUID.randomUUID().toString())
        }
        val tools = source.array("tools").deepCopy()
        for (element in tools) {
            val tool = element.objectOrNull() ?: throw AccountProviderException("invalid_request", "Tool must be an object", 400)
            val definition = tool.obj("function") ?: throw AccountProviderException("invalid_request", "Tool must contain a function", 400)
            val parameters = definition.get("parameters")
            if (parameters != null && !parameters.isJsonNull && !(parameters.isJsonPrimitive && parameters.asJsonPrimitive.isString)) definition.addProperty("parameters", parameters.toString())
        }
        if (!tools.isEmpty) result.add("tools", tools)
        listOf("temperature", "top_p", "max_tokens", "parallel_tool_calls", "reasoning_effort").forEach { key -> source.get(key)?.let { result.add(key, it.deepCopy()) } }
        source.get("max_completion_tokens")?.let { result.add("max_tokens", it.deepCopy()) }
        source.get("tool_choice")?.let { choice ->
            val normalized = choice.objectOrNull()?.obj("function")?.text("name")
            result.add("tool_choice", if (normalized != null) com.google.gson.JsonPrimitive(normalized) else choice.deepCopy())
        }
        return result
    }

    fun convertEvent(event: String, data: String, model: String): JsonObject? {
        if (data == "[DONE]") return finish(model, "stop")
        val parsed = try { JsonParser.parseString(data) } catch (_: RuntimeException) { throw AccountProviderException("invalid_stream", "Trae returned an invalid stream event") }
        val eventType = if (event.isEmpty()) parsed.objectOrNull()?.text("event") ?: parsed.objectOrNull()?.text("type") ?: "" else event
        if (eventType == "error") throw AccountProviderException("upstream_error", "Trae returned a stream error")
        val value = parsed.objectOrNull() ?: if (parsed.isJsonPrimitive && event in setOf("text", "output", "delta")) {
            JsonObject().apply { addProperty("content", parsed.asString) }
        } else return null
        AccountProviderProtocol.check(value)
        val terminal = eventType in setOf("done", "response_done", "stream_done", "finish", "end", "message_stop")
        val deltaValue = value.obj("delta") ?: value
        val delta = JsonObject()
        fun content(element: JsonElement?): String? = when {
            element == null || element.isJsonNull -> null
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
            element.isJsonArray -> element.asJsonArray.mapNotNull { it.objectOrNull()?.text("text") }.joinToString("").takeIf { it.isNotEmpty() }
            else -> null
        }
        val text = content(deltaValue.get("content")) ?: content(value.get("delta")) ?: value.text("text") ?: value.text("response")
        val reasoning = content(deltaValue.get("reasoning_content")) ?: content(deltaValue.get("reasoning")) ?: content(value.get("reasoning_content")) ?: content(value.get("reasoning"))
        text?.let { delta.addProperty("content", it) }
        reasoning?.let { delta.addProperty("reasoning_content", it) }
        val tools = (deltaValue.get("tool_calls") ?: value.get("tool_calls"))?.takeIf { it.isJsonArray }?.asJsonArray?.deepCopy()
        if (tools != null) {
            tools.forEach { element ->
                val tool = element.asJsonObject
                tool.remove("function_call")?.let { tool.add("function", it) }
                tool.obj("function")?.let { function ->
                    function.remove("namespace")
                    val partial = function.remove("partial_arguments")
                    if (!function.has("arguments") && partial?.isJsonPrimitive == true && partial.asJsonPrimitive.isString) function.add("arguments", partial)
                    function.get("arguments")?.takeUnless { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.let { function.addProperty("arguments", it.toString()) }
                }
            }
            delta.add("tool_calls", tools)
        }
        val usage = value.obj("usage") ?: if (eventType == "token_usage") value else null
        if (delta.size() == 0 && usage == null && !value.has("finish_reason") && !terminal) return null
        val reason = value.text("finish_reason") ?: if (terminal) value.text("stop_reason") ?: "stop" else null
        return chunk(model, delta, reason).apply { usage?.let { add("usage", it.deepCopy()) } }
    }

    private fun chunk(model: String, delta: JsonObject, reason: String?): JsonObject = JsonObject().apply {
        addProperty("id", "chatcmpl-account"); addProperty("object", "chat.completion.chunk"); addProperty("created", System.currentTimeMillis() / 1000); addProperty("model", model)
        add("choices", JsonArray().apply { add(JsonObject().apply { addProperty("index", 0); add("delta", delta); addProperty("finish_reason", reason) }) })
    }
    private fun finish(model: String, reason: String): JsonObject = chunk(model, JsonObject(), when (reason) { "max_tokens", "max_output_tokens", "max_length" -> "length"; "sensitive" -> "content_filter"; else -> reason })

    fun responseBody(upstream: ResponseBody, model: String, streaming: Boolean): ResponseBody {
        val converted = EventSource(upstream, model).buffer()
        if (streaming) return object : ResponseBody() {
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = converted
        }
        return converted.use { source ->
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val calls = sortedMapOf<Int, JsonObject>()
            var reason = "stop"
            var usage: JsonObject? = null
            var bytes = 0L
            while (!source.exhausted()) {
                val line = source.readUtf8LineStrict(512 * 1024)
                bytes += line.length
                if (bytes > 16 * 1024 * 1024) throw AccountProviderException("response_too_large", "Trae answer exceeds the size limit")
                if (!line.startsWith("data: ") || line == "data: [DONE]") continue
                val value = JsonParser.parseString(line.substring(6)).asJsonObject
                val choice = value.array("choices").firstOrNull()?.asJsonObject ?: continue
                val delta = choice.obj("delta") ?: JsonObject()
                delta.text("content")?.let { content.append(it) }
                delta.text("reasoning_content")?.let { reasoning.append(it) }
                choice.text("finish_reason")?.let { reason = it }
                value.obj("usage")?.let { usage = it }
                delta.array("tool_calls").forEach { toolElement ->
                    val incoming = toolElement.asJsonObject
                    val index = incoming.get("index").asInt
                    val call = calls.getOrPut(index) { JsonObject().apply { addProperty("type", "function"); add("function", JsonObject()) } }
                    incoming.text("id")?.let { call.addProperty("id", it) }
                    val fn = call.obj("function")!!
                    incoming.obj("function")?.let { incomingFn -> listOf("name", "arguments").forEach { key -> incomingFn.text(key)?.let { fn.addProperty(key, (fn.text(key) ?: "") + it) } } }
                }
            }
            val message = JsonObject().apply { addProperty("role", "assistant"); addProperty("content", content.toString()) }
            if (reasoning.isNotEmpty()) message.addProperty("reasoning_content", reasoning.toString())
            if (calls.isNotEmpty()) message.add("tool_calls", JsonArray().apply { calls.values.forEach { add(it) } })
            val result = JsonObject().apply {
                addProperty("id", "chatcmpl-account"); addProperty("object", "chat.completion"); addProperty("created", System.currentTimeMillis() / 1000); addProperty("model", model)
                add("choices", JsonArray().apply { add(JsonObject().apply { addProperty("index", 0); add("message", message); addProperty("finish_reason", reason) }) })
                usage?.let { add("usage", it) }
            }
            ResponseBody.create("application/json".toMediaType(), result.toString())
        }
    }

    private class EventSource(private val body: ResponseBody, private val model: String) : Source {
        private val input = body.source()
        private val pending = Buffer()
        private var finished = false
        private var sawOutput = false
        private var sawTools = false
        private var total = 0L
        private var sawFinishReason = false
        private val toolIndices = mutableMapOf<String, Int>()
        private val emittedNames = mutableSetOf<Int>()
        private var nextToolIndex = 0
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (byteCount == 0L) return 0
            while (pending.size == 0L && !finished) {
                var event = ""
                val data = StringBuilder()
                var eventSize = 0L
                while (true) {
                    val line = try { input.readUtf8LineStrict(512 * 1024) } catch (_: EOFException) {
                        if (sawFinishReason && data.isEmpty()) {
                            finished = true
                            pending.writeUtf8("data: [DONE]\n\n")
                            return pending.read(sink, byteCount)
                        }
                        throw AccountProviderException("truncated_stream", "Trae stream ended before its final event")
                    }
                    eventSize += line.length
                    total += line.length
                    if (eventSize > 512 * 1024 || total > 32 * 1024 * 1024) throw AccountProviderException("response_too_large", "Trae stream exceeds the size limit")
                    if (line.isEmpty()) break
                    if (line.startsWith("event:")) event = line.substring(6).trim()
                    if (line.startsWith("data:")) { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trimStart()) }
                }
                if (data.isEmpty()) continue
                val value = convertEvent(event, data.toString(), model) ?: continue
                val choice = value.array("choices")[0].asJsonObject
                val delta = choice.obj("delta")!!
                if (delta.size() > 0) sawOutput = true
                delta.array("tool_calls").forEach { element ->
                    val tool = element.asJsonObject
                    val id = tool.text("id")
                    val declared = tool.get("index")?.takeIf { it.isJsonPrimitive }?.asInt
                    val index = if (id != null) toolIndices.getOrPut(id) { declared ?: nextToolIndex++ }
                        else declared ?: if (toolIndices.values.distinct().size == 1) toolIndices.values.first()
                        else throw AccountProviderException("invalid_stream", "Trae tool fragment has no stable identity")
                    nextToolIndex = maxOf(nextToolIndex, index + 1)
                    tool.addProperty("index", index)
                    val function = tool.obj("function")
                    if (function?.has("name") == true && !emittedNames.add(index)) function.remove("name")
                    sawTools = true
                }
                if (choice.text("finish_reason") != null) {
                    if (!sawOutput) throw AccountProviderException("empty_response", "Trae completed without an answer")
                    if (sawTools && choice.text("finish_reason") == "stop") choice.addProperty("finish_reason", "tool_calls")
                    sawFinishReason = true
                }
                if (data.toString() == "[DONE]" || event in setOf("done", "response_done", "stream_done", "finish", "end", "message_stop")) finished = true
                pending.writeUtf8("data: $value\n\n")
                if (finished) pending.writeUtf8("data: [DONE]\n\n")
            }
            return if (pending.size == 0L) -1 else pending.read(sink, byteCount)
        }
        override fun timeout(): Timeout = input.timeout()
        override fun close() = body.close()
    }
}
