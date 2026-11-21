package com.aharou.feature.plugin.domain.npm

import java.io.IOException

/** Untrusted display text only; these fields confer no installation or execution eligibility. */
data class NpmPackageMetadata(
    val name: String?,
    val version: String?,
    val description: String?,
    val license: String?,
    val repository: String?,
    val deprecated: String?,
    val integrity: String?,
)

data class NpmSearchPage(
    val packages: List<NpmPackageMetadata>,
    val total: Int?,
    val from: Int,
    val size: Int,
    val nextFrom: Int?,
)

enum class NpmRegistryError {
    INVALID_INPUT,
    REDIRECT_REJECTED,
    RATE_LIMITED,
    HTTP_ERROR,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
    TIMEOUT,
    NETWORK,
}

/** Deliberately excludes response bodies and URLs from error messages. */
class NpmRegistryException(
    val error: NpmRegistryError,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : IOException(error.name.lowercase(), cause)
