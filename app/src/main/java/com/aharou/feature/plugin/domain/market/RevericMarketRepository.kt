package com.aharou.feature.plugin.domain.market

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.aharou.core.net.AppProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class RevericVersion(
    val version: String, val pluginApiVersion: String, val platforms: List<String>,
    val url: String, val fallbackUrl: String, val size: Long, val sha256: String,
    val releaseUrl: String, val sourceCommit: String,
)

internal data class RevericEntry(
    val id: String, val name: Map<String, String>, val description: Map<String, String>,
    val author: String, val repository: String, val versions: List<RevericVersion>,
) {
    fun title(language: String): String = localized(name, language)
    fun summary(language: String): String = localized(description, language)
    private fun localized(values: Map<String, String>, language: String): String =
        values[language] ?: values[language.substringBefore('-')] ?: values["en"] ?: values.values.first()
}

internal enum class RevericFailure { INVALID_CATALOG, UNKNOWN_SCHEMA, TOO_LARGE, SIZE_MISMATCH, HASH_MISMATCH, UNSAFE_URL, REDIRECT, HTTP, DOCUMENT }
internal class RevericMarketException(val reason: RevericFailure) : Exception(reason.name)

/** Public catalog metadata conveys no Aharou host capabilities or installation authority. */
internal object RevericCatalog {
    const val URL = "https://downloads.reveric.app/plugins/index.json"
    const val MAX_CATALOG = 1024L * 1024
    const val MAX_DOWNLOAD = 32L * 1024 * 1024

    fun parse(text: String): List<RevericEntry> {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_CATALOG) fail(RevericFailure.TOO_LARGE)
        checkDepth(text)
        try {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: fail(RevericFailure.INVALID_CATALOG)
            val schema = root["schemaVersion"] as? JsonPrimitive ?: fail(RevericFailure.INVALID_CATALOG)
            if (schema.isString || schema.content != "1") fail(RevericFailure.UNKNOWN_SCHEMA)
            val plugins = root["plugins"] as? JsonArray ?: fail(RevericFailure.INVALID_CATALOG)
            val entries = plugins.map { element ->
                val item = element as? JsonObject ?: fail(RevericFailure.INVALID_CATALOG)
                val versions = item["versions"] as? JsonArray ?: fail(RevericFailure.INVALID_CATALOG)
                require(versions.isNotEmpty())
                RevericEntry(item.text("id"), item.translations("name"), item.translations("description"),
                    item.text("author"), item.text("repository"), versions.map { version ->
                        val value = version as? JsonObject ?: fail(RevericFailure.INVALID_CATALOG)
                        val size = value["size"] as? JsonPrimitive ?: fail(RevericFailure.INVALID_CATALOG)
                        require(!size.isString)
                        val bytes = size.longOrNull ?: fail(RevericFailure.INVALID_CATALOG)
                        require(bytes > 0)
                        val hash = value.text("sha256")
                        require(Regex("[a-fA-F0-9]{64}").matches(hash))
                        val platforms = value["platforms"] as? JsonArray ?: fail(RevericFailure.INVALID_CATALOG)
                        require(platforms.isNotEmpty())
                        RevericVersion(value.text("version"), value.text("pluginApiVersion"), platforms.map {
                            val platform = it as? JsonPrimitive ?: fail(RevericFailure.INVALID_CATALOG)
                            require(platform.isString && platform.content.isNotBlank())
                            platform.content
                        }, value.text("url"), value.text("fallbackUrl"), bytes, hash,
                            value.text("releaseUrl"), value.text("sourceCommit"))
                    })
            }
            require(entries.map { it.id }.distinct().size == entries.size)
            require(entries.all { entry -> entry.versions.map { it.version }.distinct().size == entry.versions.size })
            return entries
        } catch (failure: RevericMarketException) {
            throw failure
        } catch (_: Exception) {
            fail(RevericFailure.INVALID_CATALOG)
        }
    }

    private fun JsonObject.text(key: String): String {
        val value = this[key] as? JsonPrimitive ?: fail(RevericFailure.INVALID_CATALOG)
        require(value.isString && value.content.isNotBlank())
        return value.content
    }

    private fun JsonObject.translations(key: String): Map<String, String> {
        val values = this[key] as? JsonObject ?: fail(RevericFailure.INVALID_CATALOG)
        require(values.isNotEmpty())
        return values.keys.associateWith { values.text(it) }
    }

    private fun checkDepth(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 128) fail(RevericFailure.INVALID_CATALOG) }
                '}', ']' -> { depth--; if (depth < 0) fail(RevericFailure.INVALID_CATALOG) }
            }
        }
        if (quoted || depth != 0) fail(RevericFailure.INVALID_CATALOG)
    }

    fun checkDownload(version: RevericVersion) {
        if (version.size !in 1..MAX_DOWNLOAD) fail(RevericFailure.TOO_LARGE)
        if (!Regex("[a-fA-F0-9]{64}").matches(version.sha256)) fail(RevericFailure.INVALID_CATALOG)
        val url = try { version.url.toHttpUrl() } catch (_: Exception) { fail(RevericFailure.UNSAFE_URL) }
        if (url.scheme != "https" || url.host != "downloads.reveric.app" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            !url.encodedPath.startsWith("/plugins/") || !url.encodedPath.endsWith(".zip") ||
            url.encodedPath.contains('%') || url.encodedPath.contains('\\') ||
            version.url.contains('\\') || version.url.substringBefore('?').split('/').any { it == "." || it == ".." }) {
            fail(RevericFailure.UNSAFE_URL)
        }
    }

    fun canDownload(version: RevericVersion): Boolean = runCatching { checkDownload(version) }.isSuccess

    suspend fun copyVerified(input: InputStream, output: OutputStream, version: RevericVersion) {
        checkDownload(version)
        val digest = MessageDigest.getInstance("SHA-256")
        copyBounded(input, output, minOf(MAX_DOWNLOAD, version.size), digest)
            .also { if (it != version.size) fail(RevericFailure.SIZE_MISMATCH) }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(version.sha256, true)) fail(RevericFailure.HASH_MISMATCH)
    }

    suspend fun copyBounded(input: InputStream, output: OutputStream, limit: Long, digest: MessageDigest? = null): Long {
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val requested = minOf(buffer.size.toLong(), limit - count + 1).toInt()
            val read = input.read(buffer, 0, requested)
            if (read < 0) break
            count += read
            if (count > limit) fail(RevericFailure.TOO_LARGE)
            digest?.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        return count
    }

    fun fail(reason: RevericFailure): Nothing = throw RevericMarketException(reason)
}

internal class RevericMarketRepository internal constructor(
    private val context: Context, private val client: OkHttpClient,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, newClient())

    companion object {
        internal fun newClient(): OkHttpClient = newClient(OkHttpClient.Builder())

        internal fun newClient(builder: OkHttpClient.Builder): OkHttpClient = builder
            .proxySelector(AppProxy.selector).proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .authenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    }

    suspend fun load(): List<RevericEntry> = withContext(Dispatchers.IO) {
        val output = ByteArrayOutputStream()
        request(RevericCatalog.URL) { input -> RevericCatalog.copyBounded(input, output, RevericCatalog.MAX_CATALOG) }
        RevericCatalog.parse(output.toString("UTF-8"))
    }

    /** A cancelled SAF selection never starts a request or opens a destination. */
    suspend fun save(version: RevericVersion, uri: Uri?): Boolean {
        if (uri == null) return false
        return withContext(Dispatchers.IO) {
            RevericCatalog.checkDownload(version)
            val staged = File.createTempFile("reveric-", ".part", context.cacheDir)
            var writing = false
            var completed = false
            try {
                request(version.url) { input ->
                    staged.outputStream().use { RevericCatalog.copyVerified(input, it, version) }
                }
                currentCoroutineContext().ensureActive()
                writing = true
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    staged.inputStream().use { RevericCatalog.copyBounded(it, output, version.size) }
                } ?: RevericCatalog.fail(RevericFailure.DOCUMENT)
                currentCoroutineContext().ensureActive()
                completed = true
                true
            } finally {
                staged.delete()
                if (writing && !completed) {
                    // SAF is not transactional; remove or truncate a failed destination on a best-effort basis.
                    runCatching { context.contentResolver.openOutputStream(uri, "wt")?.close() }
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
            }
        }
    }

    private suspend fun <T> request(url: String, consume: suspend (InputStream) -> T): T = coroutineScope {
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "Aharou-RevericMarket/1.0")
            .header("Referer", "https://reveric.app/").build())
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (response.code in 300..399) RevericCatalog.fail(RevericFailure.REDIRECT)
                if (!response.isSuccessful) RevericCatalog.fail(RevericFailure.HTTP)
                val body = response.body ?: RevericCatalog.fail(RevericFailure.HTTP)
                body.byteStream().use { consume(it) }
            }
        } finally { cancellation.cancel() }
    }
}
