package com.aharou.feature.agent.domain.provider

/** Explicit opt-in stored in the existing custom header map; no provider/schema migration. */
object OpenAIServiceTier {
    const val HEADER = "OpenAI-Service-Tier"

    private fun canonical(value: String): String = when (value.trim().lowercase()) {
        "priority", "fast" -> "fast"
        "ultrafast" -> "ultrafast"
        else -> value.trim() // Preserve vendor values; never silently upgrade or downgrade billing.
    }

    fun value(headers: Map<String, String>): String? {
        val values = headers.filterKeys { it.equals(HEADER, ignoreCase = true) }
            .values.map(::canonical).distinct()
        require(values.size <= 1) { "Conflicting OpenAI-Service-Tier headers" }
        return values.firstOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun requiresResponses(headers: Map<String, String>): Boolean = value(headers) == "ultrafast"

    fun normalizeHeaders(headers: Map<String, String>): Map<String, String> {
        val tier = value(headers)
        val other = headers.filterKeys { !it.equals(HEADER, ignoreCase = true) }
        return if (tier == null) other else other + (HEADER to tier)
    }

    /** null means the user explicitly cleared the override (not service_tier=auto). */
    fun select(headers: Map<String, String>, tier: String?): Map<String, String> {
        require(tier == null || tier == "fast" || tier == "ultrafast") { "Unsupported service tier selection" }
        val other = headers.filterKeys { !it.equals(HEADER, ignoreCase = true) }
        return if (tier == null) other else other + (HEADER to tier)
    }
}
