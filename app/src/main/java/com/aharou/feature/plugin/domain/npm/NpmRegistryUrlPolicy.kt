package com.aharou.feature.plugin.domain.npm

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** There is no configurable registry, URL input, package document, or tarball endpoint. */
object NpmRegistryUrlPolicy {
    const val MAX_QUERY_LENGTH = 200
    const val MAX_PACKAGE_LENGTH = 214
    const val MAX_VERSION_LENGTH = 128
    const val MAX_PAGE_SIZE = 50
    const val MAX_FROM = 1000
    private val base = "https://registry.npmjs.org/".toHttpUrl()
    private val part = Regex("[a-z0-9][a-z0-9._-]*")
    private val version = Regex(
        "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
            "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
            "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?",
    )

    fun search(query: String, from: Int = 0, size: Int = 20): HttpUrl {
        valid(query.isNotBlank() && query.length <= MAX_QUERY_LENGTH && query.none { it.isISOControl() })
        valid(from in 0..MAX_FROM && size in 1..MAX_PAGE_SIZE)
        return base.newBuilder().addPathSegments("-/v1/search")
            .addQueryParameter("text", query).addQueryParameter("from", from.toString())
            .addQueryParameter("size", size.toString()).build()
    }

    fun metadata(packageName: String, exactVersion: String): HttpUrl {
        valid(packageName.length in 1..MAX_PACKAGE_LENGTH)
        val parts = if (packageName.startsWith('@')) packageName.drop(1).split('/') else listOf(packageName)
        valid(parts.size == (if (packageName.startsWith('@')) 2 else 1) && parts.all { part.matches(it) })
        valid(exactVersion.length in 1..MAX_VERSION_LENGTH)
        val match = version.matchEntire(exactVersion)
            ?: throw NpmRegistryException(NpmRegistryError.INVALID_INPUT)
        // Numeric prerelease identifiers may not have leading zeroes (SemVer 2.0).
        valid(match.groupValues[4].split('.').all { id ->
            id.length <= 1 || !id.all { it in '0'..'9' } || !id.startsWith('0')
        })
        return base.newBuilder().addPathSegment(packageName).addPathSegment(exactVersion).build()
    }

    internal fun isRegistry(url: HttpUrl): Boolean = url.scheme == "https" &&
        url.host == base.host && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()

    private fun valid(condition: Boolean) {
        if (!condition) throw NpmRegistryException(NpmRegistryError.INVALID_INPUT)
    }
}
