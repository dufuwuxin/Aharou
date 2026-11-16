package com.aharou.feature.settings.data.account

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.buffer

internal object DeepSeekAccountSse {
    fun body(upstream: ResponseBody, model: String): ResponseBody {
        if (upstream.contentType()?.subtype?.contains("event-stream") != true) {
            upstream.close()
            throw AccountProviderException("invalid_stream", "DeepSeek did not return an event stream")
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
        private var path = ""
        private var type = "RESPONSE"
        private var finished = false
        private var answered = false
        private var total = 0L

        private fun chunk(text: String? = null, stop: Boolean = false) {
            val delta = JsonObject().apply {
                if (text != null) addProperty(if (type == "THINK") "reasoning_content" else "content", text)
            }
            val value = JsonObject().apply {
                addProperty("id", "chatcmpl-deepseek"); addProperty("object", "chat.completion.chunk"); addProperty("model", model)
                add("choices", JsonArray().apply { add(JsonObject().apply {
                    addProperty("index", 0); add("delta", delta); if (stop) addProperty("finish_reason", "stop")
                }) })
            }
            pending.writeUtf8("data: $value\n\n")
        }

        private fun text(value: String) {
            if (value.isEmpty()) return
            if (type !in setOf("THINK", "RESPONSE")) throw AccountProviderException("unsupported_stream", "Unknown DeepSeek fragment type")
            answered = true
            chunk(value)
        }

        private fun fragments(values: JsonArray) {
            values.forEach { value ->
                val fragment = value.objectOrNull() ?: throw AccountProviderException("invalid_stream", "Invalid DeepSeek fragment")
                type = fragment.text("type") ?: throw AccountProviderException("invalid_stream", "Missing DeepSeek fragment type")
                fragment.rawText("content")?.let(::text)
            }
        }

        private fun end() {
            if (!answered) throw AccountProviderException("empty_response", "DeepSeek returned no text")
            finished = true
            chunk(stop = true)
            pending.writeUtf8("data: [DONE]\n\n")
        }

        private fun frame() {
            var event = ""
            val data = StringBuilder()
            while (true) {
                if (input.exhausted()) throw AccountProviderException("truncated_stream", "DeepSeek stream ended without completion")
                val line = input.readUtf8LineStrict(512 * 1024)
                total += line.length
                if (total > 16 * 1024 * 1024) throw AccountProviderException("response_too_large", "DeepSeek stream exceeds the size limit")
                if (line.isEmpty()) break
                if (line.startsWith("event:")) event = line.substring(6).trim()
                if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
            }
            if (event == "error") throw AccountProviderException("upstream_error", "DeepSeek stream failed")
            if (data.isNotEmpty()) {
                if (data.toString() == "[DONE]") { end(); return }
                val item = try { JsonParser.parseString(data.toString()).asJsonObject } catch (_: RuntimeException) {
                    throw AccountProviderException("invalid_stream", "Invalid DeepSeek event")
                }
                AccountProviderProtocol.check(item)
                item.text("p")?.let { path = it }
                val value = item.get("v")
                val response = value?.objectOrNull()?.obj("response")
                when {
                    response != null -> {
                        if (response.text("status") in setOf("FAILED", "ERROR", "CANCELLED")) throw AccountProviderException("upstream_error", "DeepSeek completion failed")
                        fragments(response.array("fragments"))
                        if (response.text("status") == "FINISHED") { end(); return }
                    }
                    path == "response/fragments" && value?.isJsonArray == true -> fragments(value.asJsonArray)
                    path.startsWith("response/fragments/") && path.endsWith("/content") && value?.isJsonPrimitive == true -> text(value.asString)
                    path == "response/status" && value?.isJsonPrimitive == true -> {
                        if (value.asString == "FINISHED") { end(); return }
                        if (value.asString in setOf("FAILED", "ERROR", "CANCELLED")) throw AccountProviderException("upstream_error", "DeepSeek completion failed")
                    }
                    path == "response" && value?.isJsonArray == true -> {
                        if (value.asJsonArray.any { it.objectOrNull()?.let { field -> field.text("p") in setOf("status", "quasi_status") && field.text("v") in setOf("FAILED", "ERROR", "CANCELLED") } == true }) throw AccountProviderException("upstream_error", "DeepSeek completion failed")
                        if (value.asJsonArray.any { it.objectOrNull()?.let { field -> field.text("p") == "quasi_status" && field.text("v") == "FINISHED" } == true }) { end(); return }
                    }
                }
            }
            if (event == "close") end()
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
