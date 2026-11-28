package com.aharou.feature.agent.domain.skill.market

import com.aharou.core.net.AppProxy
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request

/** Read-only adapter for ClawHub's public v1 skill endpoints. */
class ClawHubClient internal constructor(
    private val client: OkHttpClient,
    baseUrl: String = API_BASE
) {
    private val baseUrl = baseUrl.trimEnd('/')

    @javax.inject.Inject
    constructor() : this(defaultClient(), API_BASE)

    fun search(query: String): List<ClawHubSkill>? {
        val body = get("/search?q=${encode(query)}") ?: return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["results"]?.jsonArray.orEmpty().mapNotNull { value ->
                val item = value.jsonObject
                val slug = item.string("slug")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                ClawHubSkill(
                    slug = slug,
                    displayName = item.string("displayName").orEmpty(),
                    summary = item.string("summary").orEmpty(),
                    version = item.string("version").orEmpty(),
                    updatedAt = item.long("updatedAt"),
                    owner = item.string("ownerHandle")
                        ?: item.obj("owner")?.string("handle").orEmpty()
                )
            }
        }.getOrNull()
    }

    fun detail(slug: String): ClawHubDetail? {
        val body = get("/skills/${encodePath(slug)}") ?: return null
        return parseDetail(body)
    }

    fun scan(slug: String, version: String? = null): ClawHubModeration? {
        val suffix = version?.takeIf(String::isNotBlank)?.let { "?version=${encode(it)}" }.orEmpty()
        val body = get("/skills/${encodePath(slug)}/scan$suffix") ?: return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val moderation = parseModeration(root.obj("moderation"))
            val versionMatches = moderation?.matchesRequestedVersion == true
            val sha256hash = root.obj("security")?.string("sha256hash")
                ?.takeIf { versionMatches && it.isNotBlank() }
            if (moderation == null && sha256hash == null) null
            else (moderation ?: ClawHubModeration()).copy(securitySha256 = sha256hash)
        }.getOrNull()
    }

    /**
     * Fetch only a ZIP whose bytes can be checked against ClawHub's contentHash.
     * A hosted ZIP response without a published digest is deliberately not installable.
     */
    fun download(
        slug: String,
        version: String? = null,
        expectedHostedZipSha256: String? = null
    ): VerifiedPackage? {
        val versionQuery = version?.takeIf(String::isNotBlank)?.let { "&version=${encode(it)}" }.orEmpty()
        val response = getResponse("/download?slug=${encode(slug)}$versionQuery") ?: return null
        val mediaType = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
        val responseBytes = response.bytes ?: return null
        if (mediaType == "application/zip") {
            val expectedHash = expectedHostedZipSha256?.takeIf(String::isNotBlank) ?: return null
            if (!matchesSha256(responseBytes, expectedHash)) return null
            return VerifiedPackage(responseBytes, expectedHash)
        }
        if (mediaType != "application/json") return null
        val handoff = runCatching { json.parseToJsonElement(responseBytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
            ?: return null
        if (handoff.string("sourceRef") != "public-github") return null
        val expectedHash = handoff.string("contentHash")?.takeIf(String::isNotBlank) ?: return null
        val archiveUrl = handoff.string("archiveUrl")?.takeIf(::isAllowedArchiveUrl) ?: return null
        val packageBytes = getBytes(archiveUrl) ?: return null
        if (!matchesSha256(packageBytes, expectedHash)) return null
        return VerifiedPackage(packageBytes, expectedHash)
    }

    private fun parseDetail(body: String): ClawHubDetail? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val skill = root.obj("skill") ?: return null
        val slug = skill.string("slug")?.takeIf(String::isNotBlank) ?: return null
        val latest = root.obj("latestVersion")
        ClawHubDetail(
            skill = ClawHubSkill(
                slug = slug,
                displayName = skill.string("displayName").orEmpty(),
                summary = skill.string("summary").orEmpty(),
                version = latest?.string("version").orEmpty(),
                updatedAt = skill.long("updatedAt"),
                owner = root.obj("owner")?.string("handle").orEmpty()
            ),
            moderation = parseModeration(root.obj("moderation"))
        )
    }.getOrNull()

    private fun parseModeration(value: JsonObject?): ClawHubModeration? = value?.let {
        ClawHubModeration(
            pending = it.boolean("isPendingScan"),
            suspicious = it.boolean("isSuspicious"),
            malwareBlocked = it.boolean("isMalwareBlocked"),
            hidden = it.boolean("isHiddenByMod"),
            removed = it.boolean("isRemoved"),
            matchesRequestedVersion = it.booleanOrNull("matchesRequestedVersion"),
            verdict = it.string("verdict").orEmpty(),
            reasonCodes = it["reasonCodes"]?.let { reasons -> runCatching { reasons.jsonArray.map { it.jsonPrimitive.content } }.getOrNull() }.orEmpty()
        )
    }

    private fun get(path: String): String? = getResponse(path)?.bytes?.toString(Charsets.UTF_8)

    private fun getResponse(path: String): HttpPayload? = request("$baseUrl$path", ::isAllowedApiUrl)

    private fun getBytes(url: String): ByteArray? = request(url, ::isAllowedArchiveUrl)?.bytes

    private fun request(
        url: String,
        allowedRedirect: (String) -> Boolean
    ): HttpPayload? = runCatching {
        val requestClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var currentUrl = url
        for (redirectCount in 0..MAX_REDIRECTS) {
            if (!allowedRedirect(currentUrl)) return null
            val request = Request.Builder().url(currentUrl).header("User-Agent", "aharou-android").get().build()
            val redirectedUrl = requestClient.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    val location = response.header("Location") ?: return null
                    if (redirectCount == MAX_REDIRECTS) return null
                    val nextUrl = java.net.URI(currentUrl).resolve(location).toString()
                    if (!allowedRedirect(nextUrl)) return null
                    nextUrl
                } else {
                    if (!response.isSuccessful) return null
                    return HttpPayload(response.header("Content-Type"), response.body?.bytes())
                }
            }
            currentUrl = redirectedUrl
        }
        null
    }.getOrNull()

    internal fun isAllowedApiUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        val api = java.net.URI(baseUrl)
        uri.isAbsolute && uri.userInfo == null && uri.host.equals(api.host, true) &&
            uri.scheme.equals(api.scheme, true) && effectivePort(uri) == effectivePort(api)
    }.getOrDefault(false)

    internal fun isAllowedArchiveUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        val api = java.net.URI(baseUrl)
        val sameApiOrigin = uri.host.equals(api.host, true) && uri.scheme.equals(api.scheme, true) &&
            effectivePort(uri) == effectivePort(api)
        val allowedGithubHost = uri.scheme.equals("https", true) && (uri.port == -1 || uri.port == 443) &&
            (uri.host.equals("github.com", true) || uri.host.endsWith(".github.com", true))
        uri.isAbsolute && uri.userInfo == null && uri.host != null && (sameApiOrigin || allowedGithubHost)
    }.getOrDefault(false)

    private fun effectivePort(uri: java.net.URI): Int = uri.port.takeIf { it >= 0 }
        ?: if (uri.scheme == "https") 443 else 80

    private fun matchesSha256(bytes: ByteArray, expected: String): Boolean {
        val normalized = expected.trim().removePrefix("sha256:").removePrefix("sha256-")
        if (!normalized.matches(Regex("(?i)[0-9a-f]{64}"))) return false
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return actual.equals(normalized, ignoreCase = true)
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun encodePath(value: String) = encode(value).replace("+", "%20")

    private fun JsonObject.obj(key: String): JsonObject? = runCatching { this[key]?.jsonObject }.getOrNull()
    private fun JsonObject.string(key: String): String? = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
    private fun JsonObject.long(key: String): Long? = runCatching { this[key]?.jsonPrimitive?.long }.getOrNull()
    private fun JsonObject.boolean(key: String): Boolean = booleanOrNull(key) ?: false
    private fun JsonObject.booleanOrNull(key: String): Boolean? = runCatching { this[key]?.jsonPrimitive?.content?.toBooleanStrict() }.getOrNull()

    data class ClawHubSkill(
        val slug: String,
        val displayName: String,
        val summary: String,
        val version: String,
        val updatedAt: Long?,
        val owner: String
    )

    data class ClawHubDetail(val skill: ClawHubSkill, val moderation: ClawHubModeration?)

    data class ClawHubModeration(
        val pending: Boolean = false,
        val suspicious: Boolean = false,
        val malwareBlocked: Boolean = false,
        val hidden: Boolean = false,
        val removed: Boolean = false,
        val matchesRequestedVersion: Boolean? = null,
        val verdict: String = "",
        val reasonCodes: List<String> = emptyList(),
        val securitySha256: String? = null
    )

    data class VerifiedPackage(val bytes: ByteArray, val contentHash: String)

    private data class HttpPayload(val contentType: String?, val bytes: ByteArray?) {
        fun header(name: String): String? = if (name == "Content-Type") contentType else null
    }

    private companion object {
        const val API_BASE = "https://clawhub.ai/api/v1"
        const val MAX_REDIRECTS = 5
        val json = Json { ignoreUnknownKeys = true }

        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .build()
    }
}
