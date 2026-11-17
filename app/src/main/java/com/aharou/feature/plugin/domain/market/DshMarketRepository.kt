package com.aharou.feature.plugin.domain.market

import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup

/** Parses public server-rendered data only. Scripts and marketplace instructions are never executed. */
internal class DshMarketRepository(
    private val hostPage: suspend (Int) -> String,
    private val containerPage: (suspend (Int) -> String)? = null,
) {
    constructor(transport: OperitMarketRepository, containerPage: (suspend (Int) -> String)? = null) :
        this({ page -> transport.text("$HOME?page=$page", HOME) }, containerPage)

    data class PageResult(val entries: List<JsonObject>, val transport: String)

    suspend fun page(page: Int): List<JsonObject> = pageResult(page).entries

    suspend fun pageResult(page: Int): PageResult {
        require(page in 1..10000)
        var route = "host"
        val html = try {
            hostPage(page)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val code = when (failure) {
                is javax.net.ssl.SSLException -> "tls"
                is java.net.UnknownHostException -> "dns"
                is java.net.SocketTimeoutException -> "timeout"
                is IOException -> "network"
                else -> "response"
            }
            val original = "dsh_catalog_transport:$code:${failure.message.orEmpty().take(120)}"
            if (!isHandshakeClosed(failure)) throw IllegalStateException(original, failure)
            val fallback = containerPage
                ?: throw IllegalStateException("$original:dsh_container_unavailable", failure)
            try {
                fallback(page).also { route = "container" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (backup: Exception) {
                throw IllegalStateException("$original:dsh_container_unavailable:${backup.message.orEmpty().take(120)}", failure)
                    .also { it.addSuppressed(backup) }
            }
        }
        val entries = try { parse(html) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw IllegalStateException("dsh_catalog_parse:${failure.message.orEmpty().take(120)}", failure) }
        return PageResult(entries, route)
    }

    companion object {
        const val HOME = "https://dpharness.com/plugins"

        internal fun isHandshakeClosed(failure: Throwable): Boolean {
            val chain = generateSequence(failure) { it.cause }.take(12).toList()
            if (chain.any { it is javax.net.ssl.SSLPeerUnverifiedException ||
                    it is java.security.cert.CertificateException || it is java.security.cert.CertPathValidatorException }) return false
            if (chain.none { it is javax.net.ssl.SSLHandshakeException }) return false
            return chain.any { it is java.io.EOFException ||
                Regex("remote host terminated|connection (?:was )?closed|peer (?:shut down|closed)|unexpected (?:end|eof)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(it.message.orEmpty()) }
        }
        private val push = Regex("self\\.__next_f\\.push\\((\\[.*])\\)\\s*;?", RegexOption.DOT_MATCHES_ALL)

        fun parse(html: String): List<JsonObject> {
            val streams = Jsoup.parse(html).select("script").mapNotNull { script ->
                val encoded = push.matchEntire(script.data().trim())?.groupValues?.get(1) ?: return@mapNotNull null
                val array = Json.parseToJsonElement(encoded) as? JsonArray ?: return@mapNotNull null
                (array.getOrNull(1) as? JsonPrimitive)?.contentOrNull
            }.joinToString("")
            val found = linkedMapOf<String, JsonObject>()
            fun visit(value: JsonElement) {
                when (value) {
                    is JsonObject -> {
                        val plugin = value["plugin"] as? JsonObject
                        if (plugin != null) {
                            val id = (plugin["fullName"] as? JsonPrimitive)?.contentOrNull ?: error("dsh_missing_identity")
                            val url = (plugin["htmlUrl"] as? JsonPrimitive)?.contentOrNull ?: error("dsh_missing_source")
                            val repo = OperitGitHub.parse(url)
                            require("${repo.owner}/${repo.repo}" == id) { "dsh_source_identity_mismatch" }
                            found[id] = plugin
                        }
                        value.values.forEach(::visit)
                    }
                    is JsonArray -> value.forEach(::visit)
                    else -> Unit
                }
            }
            streams.lineSequence().forEach { line ->
                val data = line.substringAfter(':', "")
                if (data.startsWith('[') || data.startsWith('{')) {
                    // Flight also contains non-JSON module metadata. Only valid JSON records are data.
                    val parsed = runCatching { Json.parseToJsonElement(data) }.getOrNull()
                    if (parsed != null) visit(parsed)
                }
            }
            check(found.isNotEmpty()) { "dsh_catalog_unavailable_or_changed" }
            return found.values.toList()
        }
    }
}
