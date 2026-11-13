package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import java.util.UUID

internal object LoomyAccountProtocol {
    fun models(payload: JsonObject): List<AccountProviderModel> {
        AccountProviderProtocol.check(payload)
        return payload.array("data").mapNotNull { row ->
            val entry = row.objectOrNull() ?: return@mapNotNull null
            val id = entry.text("id") ?: return@mapNotNull null
            AccountProviderModel(id, entry.text("name") ?: entry.text("displayName") ?: id)
        }.distinctBy { it.id }
    }

    fun balance(payload: JsonObject): String? = number((payload.obj("data") ?: payload).get("balance"))?.let { "$it points" }

    fun headers(session: String): Map<String, String> = mapOf(
        "traceparent" to "00-${UUID.randomUUID().toString().replace("-", "")}-${UUID.randomUUID().toString().replace("-", "").take(16)}-01",
        "token" to session, "Authorization" to "Bearer $session", "loomy-version" to "0.9.38",
        "invokeorigin" to "user", "chatid" to "chat-${UUID.randomUUID()}", "msgid" to "msg-${UUID.randomUUID()}",
    )

    fun request(source: JsonObject): JsonObject = source.deepCopy().apply {
        addProperty("stream", true)
        remove("stream_options")
        remove("reasoning_effort")
    }
}
