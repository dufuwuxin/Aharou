package com.aharou.feature.plugin.domain.npm

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Supply an application client configured with AppProxy.selector / okHttpAuthenticator.
 * Only its connection/proxy settings are reused; origin credentials, cookies, caches and
 * interceptors are removed. Returned strings are untrusted text, never plugin inputs.
 */
class NpmRegistryRepository internal constructor(private val calls: Call.Factory) {
    constructor(client: OkHttpClient) : this(client.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
        authenticator(Authenticator.NONE)
        cookieJar(CookieJar.NO_COOKIES)
        cache(null)
        followRedirects(false)
        followSslRedirects(false)
        retryOnConnectionFailure(false)
        connectTimeout(10, TimeUnit.SECONDS)
        readTimeout(10, TimeUnit.SECONDS)
        callTimeout(15, TimeUnit.SECONDS)
    }.build() as Call.Factory)

    suspend fun search(query: String, from: Int = 0, size: Int = 20): NpmSearchPage {
        val root = get(NpmRegistryUrlPolicy.search(query, from, size))
        val objects = root["objects"] as? JsonArray ?: invalidResponse()
        if (objects.size > size) invalidResponse()
        val packages = objects.map { element ->
            val entry = element as? JsonObject ?: invalidResponse()
            metadata(entry["package"] as? JsonObject ?: invalidResponse())
        }
        val total = (root["total"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        val next = (from + size).takeIf {
            packages.size == size && it <= NpmRegistryUrlPolicy.MAX_FROM && (total == null || it < total)
        }
        return NpmSearchPage(packages, total, from, size, next)
    }

    suspend fun packageVersion(packageName: String, exactVersion: String): NpmPackageMetadata =
        metadata(get(NpmRegistryUrlPolicy.metadata(packageName, exactVersion)))

    private suspend fun get(url: HttpUrl): JsonObject = suspendCancellableCoroutine { continuation ->
        if (!NpmRegistryUrlPolicy.isRegistry(url)) {
            continuation.resumeWithException(NpmRegistryException(NpmRegistryError.INVALID_INPUT))
            return@suspendCancellableCoroutine
        }
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(classify(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { read(it, url) }
                    continuation.resume(result)
                } catch (e: IOException) {
                    continuation.resumeWithException(classify(e))
                } catch (e: SerializationException) {
                    continuation.resumeWithException(NpmRegistryException(NpmRegistryError.INVALID_RESPONSE, cause = e))
                } catch (e: IllegalArgumentException) {
                    continuation.resumeWithException(NpmRegistryException(NpmRegistryError.INVALID_RESPONSE, cause = e))
                }
            }
        })
    }

    private fun read(response: Response, requestedUrl: HttpUrl): JsonObject {
        // A same-URL retry can also populate priorResponse.
        if (generateSequence(response) { it.priorResponse }.any {
                it.request.url != requestedUrl || it.code in 300..399
            }
        ) throw NpmRegistryException(NpmRegistryError.REDIRECT_REJECTED, response.code)
        if (response.code == 429) throw NpmRegistryException(NpmRegistryError.RATE_LIMITED, response.code)
        if (!response.isSuccessful) throw NpmRegistryException(NpmRegistryError.HTTP_ERROR, response.code)
        val body = response.body ?: invalidResponse()
        if (body.contentLength() > MAX_RESPONSE_BYTES) tooLarge()
        val source = body.source()
        source.request(MAX_RESPONSE_BYTES + 1)
        if (source.buffer.size > MAX_RESPONSE_BYTES) tooLarge()
        val text = source.buffer.readByteArray().toString(Charsets.UTF_8)
        checkJsonDepth(text)
        val parsed = try {
            Json.parseToJsonElement(text)
        } catch (e: StackOverflowError) {
            throw NpmRegistryException(NpmRegistryError.INVALID_RESPONSE, cause = e)
        }
        return parsed as? JsonObject ?: invalidResponse()
    }

    private fun checkJsonDepth(text: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (char in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > MAX_JSON_DEPTH) invalidResponse()
                    '}', ']' -> if (depth > 0) depth--
                }
            }
        }
    }

    private fun metadata(value: JsonObject): NpmPackageMetadata {
        val repository = value["repository"]
        return NpmPackageMetadata(
            name = value.text("name"),
            version = value.text("version"),
            description = value.text("description"),
            license = value.text("license"),
            repository = when (repository) {
                is JsonObject -> repository.text("url")
                is JsonPrimitive -> repository.takeIf { it.isString }?.content
                else -> null
            },
            deprecated = value.text("deprecated"),
            integrity = (value["dist"] as? JsonObject)?.text("integrity"),
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun classify(error: IOException): NpmRegistryException = when (error) {
        is NpmRegistryException -> error
        is InterruptedIOException -> NpmRegistryException(NpmRegistryError.TIMEOUT, cause = error)
        else -> NpmRegistryException(NpmRegistryError.NETWORK, cause = error)
    }

    private fun invalidResponse(): Nothing = throw NpmRegistryException(NpmRegistryError.INVALID_RESPONSE)
    private fun tooLarge(): Nothing = throw NpmRegistryException(NpmRegistryError.RESPONSE_TOO_LARGE)

    companion object {
        const val MAX_RESPONSE_BYTES = 1024L * 1024
        private const val MAX_JSON_DEPTH = 128
    }
}
