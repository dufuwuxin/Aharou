package com.aharou.feature.plugin.runtime

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer

/** Applied only to model-shaped JSON requests; authentication and unrelated APIs are untouched. */
class HostPluginHttpInterceptor(
    private val requestHook: (String) -> String,
    private val eventHook: (String) -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath.trimEnd('/').lowercase()
        if (listOf("/login", "/signin", "/sign-in", "/oauth/token", "/oauth2/token", "/oauth/authorize",
                "/oauth2/authorize", "/auth/token", "/auth/refresh").any(path::endsWith)) return chain.proceed(original)
        val body = original.body ?: return chain.proceed(original)
        if (body.isDuplex() || body.isOneShot() || body.contentType()?.subtype?.contains("json") != true) {
            return chain.proceed(original)
        }
        val encoded = Buffer().also { body.writeTo(it) }.readUtf8()
        val modelRequest = try {
            val obj = Json.parseToJsonElement(encoded).jsonObject
            (obj.containsKey("model") && listOf("messages", "input", "system", "contents").any { it in obj }) ||
                ("contents" in obj && original.url.encodedPath.contains("/models/") &&
                    (original.url.encodedPath.endsWith(":generateContent") || original.url.encodedPath.endsWith(":streamGenerateContent")))
        } catch (_: Exception) { false }
        if (!modelRequest) return chain.proceed(original)
        val changed = applyHook(encoded, requestHook)
        val request = if (changed == encoded) original else original.newBuilder()
            .method(original.method, changed.toRequestBody(body.contentType())).build()
        val response = chain.proceed(request)
        val responseBody = response.body ?: return response
        val type = responseBody.contentType()
        return when {
            type?.subtype == "event-stream" -> response.newBuilder().body(object : ResponseBody() {
                private val transformed = EventSource(responseBody.source(), eventHook).buffer()
                override fun contentType() = type
                override fun contentLength() = -1L
                override fun source() = transformed
            }).build()
            type?.subtype?.contains("json") == true -> {
                val text = responseBody.use { it.string() }
                response.newBuilder().body(applyHook(text, eventHook).toResponseBody(type)).build()
            }
            else -> response
        }
    }

    companion object {
        internal fun applyHook(original: String, hook: (String) -> String): String = try {
            val result = hook(original)
            require(Json.parseToJsonElement(result) is JsonObject)
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { original }
    }

    internal class EventSource(
        private val upstream: BufferedSource,
        private val hook: (String) -> String,
    ) : Source {
        private val pending = Buffer()
        private var exhausted = false
        override fun read(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0)
            if (byteCount == 0L) return 0
            if (pending.size == 0L && !exhausted) fillFrame()
            if (pending.size == 0L) return -1
            return pending.read(sink, minOf(byteCount, pending.size))
        }
        private fun fillFrame() {
            val lines = mutableListOf<String>()
            var terminated = false
            while (true) {
                val line = upstream.readUtf8Line()
                if (line == null) { exhausted = true; break }
                if (line.isEmpty()) { terminated = true; break }
                lines.add(line)
            }
            if (lines.isEmpty() && !terminated) return
            val data = lines.filter { it.startsWith("data:") }.map { it.substring(5).removePrefix(" ") }
            val payload = data.joinToString("\n")
            val replacement = if (data.isNotEmpty() && payload.trim() != "[DONE]") applyHook(payload, hook) else payload
            var emitted = false
            lines.forEach { line ->
                if (line.startsWith("data:") && replacement != payload) {
                    if (!emitted) { pending.writeUtf8("data: ${Json.parseToJsonElement(replacement)}\n"); emitted = true }
                } else pending.writeUtf8(line + "\n")
            }
            if (terminated) pending.writeUtf8("\n")
        }
        override fun timeout(): Timeout = upstream.timeout()
        override fun close() { exhausted = true; pending.clear(); upstream.close() }
    }
}
