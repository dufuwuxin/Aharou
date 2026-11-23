package com.aharou.feature.plugin.domain.market

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class MagpieListing(
    @SerialName("package") val packageName: String,
    val name: String,
    val kind: String = "provider",
    val summary: Map<String, String> = emptyMap(),
    val providers: List<String> = emptyList(),
) {
    val description: String get() = summary["zh"].orEmpty().ifBlank { summary["en"].orEmpty() }

    fun matches(query: String): Boolean = (listOf(name, packageName, kind, description) + providers)
        .any { it.contains(query, ignoreCase = true) }
}

internal object MagpieCatalog {
    const val URL = "https://raw.githubusercontent.com/magpie-community/plugins/main/registry.json"
    const val ASSET = "magpie-plugin-registry.json"
    private val packageName = Regex("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Registry(val version: Int, val plugins: List<MagpieListing>)

    fun parse(text: String): List<MagpieListing> {
        val registry = json.decodeFromString<Registry>(text)
        require(registry.version == 1)
        require(registry.plugins.isNotEmpty())
        val seen = HashSet<String>()
        require(registry.plugins.all {
            it.name.isNotBlank() && it.packageName.length <= 214 && packageName.matches(it.packageName) && seen.add(it.packageName)
        })
        return registry.plugins
    }
}
