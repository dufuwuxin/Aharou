package com.aharou.feature.plugin.domain.market

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class OperitPage(
    val ok: Boolean = false,
    val marketVersion: Int = 0,
    val generatedAt: String = "",
    val sort: String = "",
    val page: Int = 0,
    val pageSize: Int = 0,
    val total: Int = 0,
    val items: List<OperitEntry> = emptyList(),
) {
    val hasNext: Boolean get() = page.toLong() * pageSize < total
}

@Serializable
internal data class OperitPerson(val login: String = "")
@Serializable
internal data class OperitSource(val kind: String = "", val url: String = "")
@Serializable
internal data class OperitVersion(val id: String = "", val version: String = "", val formatVer: String = "")
@Serializable
internal data class OperitAsset(
    val id: String = "", val versionId: String = "", val kind: String = "",
    val url: String = "", val sha256: String? = null, val assetName: String = "",
)
@Serializable
internal data class OperitEntry(
    val id: String, val type: String, val title: String = "", val description: String = "",
    val detail: String = "", val source: OperitSource? = null,
    val latestVersion: OperitVersion? = null, val assets: List<OperitAsset> = emptyList(),
    val author: OperitPerson? = null,
) {
    val latestAssets: List<OperitAsset> get() = latestVersion?.id?.takeIf { it.isNotBlank() }
        ?.let { version -> assets.filter { it.versionId == version && it.url.isNotBlank() } }.orEmpty()
}

internal object OperitCatalog {
    const val BASE = "https://static.operit.app/market/v2"
    val types = listOf("all", "skill", "script", "package", "mcp")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    fun path(type: String, page: Int): String {
        require(type in types && page > 0)
        return if (type == "all") "lists/all/updated/page-$page.json"
        else "lists/type/$type/updated/page-$page.json"
    }
    fun parse(text: String, type: String, page: Int): OperitPage {
        path(type, page)
        val result = json.decodeFromString<OperitPage>(text)
        require(result.ok && result.marketVersion == 2 && result.page == page &&
            result.sort == "updated" && result.pageSize in 1..100 && result.total >= 0 &&
            result.items.size <= result.pageSize && result.generatedAt.isNotBlank())
        require(result.items.all { it.id.isNotBlank() && it.type in types.drop(1) && (type == "all" || it.type == type) })
        require(result.items.map { it.id }.distinct().size == result.items.size)
        return result
    }
    fun filter(items: List<OperitEntry>, query: String): List<OperitEntry> = items.filter {
        val key = query.trim()
        key.isEmpty() || listOf(it.title, it.description, it.author?.login.orEmpty()).any { value -> value.contains(key, true) }
    }
}

internal data class OperitGitHub(val owner: String, val repo: String, val ref: String?, val subPath: String) {
    companion object {
        fun parse(url: String): OperitGitHub {
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.query == null && uri.fragment == null)
            val parts = uri.rawPath.trim('/').split('/').map {
                java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
            }
            require(parts.size >= 2 && parts.all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it && ':' !in it })
            require(parts.filterIndexed { index, _ -> index != 3 }.none { '/' in it })
            require(parts.take(2).all { Regex("[A-Za-z0-9_.-]+").matches(it) })
            require(parts.size == 2 || (parts.size >= 4 && parts[2] == "tree"))
            return OperitGitHub(parts[0], parts[1].removeSuffix(".git"), parts.getOrNull(3), parts.drop(4).joinToString("/"))
        }
    }
}
