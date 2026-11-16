package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import okhttp3.Response

/** Public anonymous Kilo lane, adapted from dsh-our-free-model (MIT; bundled license). */
internal object OurFreeModelProtocol {
    const val BASE = "https://ai-gateway.kilo.ai/api/v1"
    private const val LOCAL_CONNECTION = "anonymous-public-connection"
    private val headers = mapOf("HTTP-Referer" to "https://kilo.ai/", "X-Title" to "Aharou Our Free Model")

    // This is a local connection marker, never an upstream credential or remote user identity.
    fun connection() = AccountCredential(
        AccountProviderKind.OUR_FREE_MODEL, LOCAL_CONNECTION, null, null,
        "our-free-model", "local-anonymous", BASE,
    )

    fun restore(value: JsonObject): AccountCredential {
        if (value.text("kind") != AccountProviderKind.OUR_FREE_MODEL.wireId ||
            value.text("accessToken") != LOCAL_CONNECTION || value.text("domain") != BASE) {
            throw AccountProviderException("invalid_credentials", "Invalid anonymous connection record", 400)
        }
        return connection()
    }

    fun models(payload: JsonObject): List<AccountProviderModel> {
        AccountProviderProtocol.check(payload)
        return payload.array("data").mapNotNull { element ->
            val row = element.objectOrNull() ?: return@mapNotNull null
            val free = row.get("isFree")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            if (free?.isBoolean != true || !free.asBoolean) return@mapNotNull null
            val id = row.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AccountProviderModel(id, "Kilo ${row.text("name") ?: id}")
        }.distinctBy { it.id }
    }

    fun catalog(manager: AccountProviderManager): List<AccountProviderModel> {
        val models = models(manager.readJson(request(manager, "/models")))
        if (models.isEmpty()) throw AccountProviderException("empty_catalog", "Public service returned no free models")
        return models
    }

    fun request(manager: AccountProviderManager, path: String, body: JsonObject? = null): Response {
        if (path != "/models" && path != "/chat/completions") {
            throw AccountProviderException("invalid_gateway", "Unsupported anonymous service endpoint", 400)
        }
        val response = manager.request(BASE + path, if (body == null) "GET" else "POST", body,
            headers + (if (body?.get("stream")?.toString() == "true") mapOf("Accept" to "text/event-stream") else emptyMap()))
        if (!response.isSuccessful) {
            val status = response.code
            response.close()
            throw AccountProviderException("upstream_http", "Public free service returned HTTP $status", if (status in 400..499) status else 502)
        }
        return response
    }
}
