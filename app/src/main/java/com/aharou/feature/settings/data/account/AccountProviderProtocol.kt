package com.aharou.feature.settings.data.account

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.Base64

enum class AccountProviderKind(val wireId: String) {
    TRAE("trae"), WORKBUDDY("workbuddy"), RACCOON("raccoon");

    companion object {
        fun fromWireId(value: String): AccountProviderKind? = entries.firstOrNull { it.wireId == value }
    }
}

data class AccountProviderModel(
    val id: String,
    val name: String,
    val multiplier: String? = null,
    internal val function: String? = null,
    internal val configName: String? = null,
    internal val modelName: String? = null,
)

data class AccountSnapshot(val models: List<AccountProviderModel>, val balance: String?, val fetchedAt: Long, val balanceError: String? = null)

class AccountProviderException(val errorCode: String, message: String, val httpStatus: Int = 502) : IOException(message)

internal data class AccountEndpoints(
    val raccoon: String = "https://xiaohuanxiong.com",
    val workbuddy: String = "https://copilot.tencent.com",
    val workbuddyBilling: String = "https://www.codebuddy.cn",
    val trae: String = "https://trae-api-cn.mchost.guru",
    val traeAccount: String = "https://api.trae.cn",
)

internal fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
internal fun JsonElement.objectOrNull(): JsonObject? = takeIf { isJsonObject }?.asJsonObject
internal fun embedded(value: JsonElement?): JsonObject? {
    if (value == null || value.isJsonNull) return null
    return if (value.isJsonObject) value.asJsonObject else if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        try { JsonParser.parseString(value.asString).objectOrNull() } catch (_: RuntimeException) { null }
    } else null
}
internal fun number(value: JsonElement?): String? {
    if (value == null || !value.isJsonPrimitive) return null
    return try { value.asString.toBigDecimal().takeIf { it.signum() >= 0 }?.stripTrailingZeros()?.toPlainString() } catch (_: NumberFormatException) { null }
}

internal object AccountProviderProtocol {
    fun check(value: JsonObject) {
        val code = value.text("code") ?: value.obj("error")?.text("code")
        when (code) {
            "11140" -> throw AccountProviderException("account_restricted", "Account restricted by the provider; signing in again will not remove the restriction", 403)
            "11128" -> throw AccountProviderException("channel_rejected", "Provider rejected this client channel", 403)
            "14018" -> throw AccountProviderException("quota_exhausted", "Provider account quota is exhausted", 402)
            "14003", "6004" -> throw AccountProviderException("rate_limited", "Provider is rate limiting this account or model", 429)
            "12153" -> throw AccountProviderException("login_required", "Provider login session is no longer valid", 401)
        }
        val error = value.get("error")
        if ((code != null && code != "0" && code != "200") || (error != null && !error.isJsonNull)) {
            // Do not include upstream messages: they may echo request credentials.
            throw AccountProviderException("upstream_error", "Account provider rejected the request${code?.takeIf { it.matches(Regex("[0-9]{1,8}")) }?.let { " (code $it)" } ?: ""}")
        }
        val responseError = value.obj("Response")?.obj("Error")
        if (responseError != null) throw AccountProviderException("upstream_error", "Account provider returned a service error")
    }

    fun models(kind: AccountProviderKind, payload: JsonObject): List<AccountProviderModel> {
        check(payload)
        if (kind == AccountProviderKind.TRAE) return TraeAccountProtocol.models(payload)
        val data = payload.obj("data") ?: payload
        val rows = when (kind) {
            AccountProviderKind.RACCOON -> data.array("categories").flatMap { category ->
                val group = category.objectOrNull() ?: return@flatMap emptyList()
                if (group.text("type") != null && group.text("type") != "chat") emptyList() else group.array("models").toList()
            }
            AccountProviderKind.WORKBUDDY -> {
                val agents = data.array("agents").toList().ifEmpty { data.obj("agent")?.array("agents")?.toList().orEmpty() }
                val cli = agents.mapNotNull { it.objectOrNull() }.firstOrNull { it.text("name") == "cli" }
                val allowed = cli?.array("models")?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString }?.toSet()
                data.array("models").filter { allowed == null || it.objectOrNull()?.text("id") in allowed }
            }
            else -> emptyList()
        }
        return rows.mapNotNull { element ->
            val entry = element.objectOrNull() ?: return@mapNotNull null
            if (entry.get("visible")?.toString() == "false" || entry.get("enabled")?.toString() == "false" || entry.get("disabled")?.toString() == "true") return@mapNotNull null
            val id = if (kind == AccountProviderKind.RACCOON) entry.text("name") ?: entry.text("model_name") else entry.text("id") ?: entry.text("modelId")
            if (id.isNullOrBlank()) return@mapNotNull null
            val multiplier = when (kind) {
                AccountProviderKind.RACCOON -> number(entry.get("billing_effective_multiplier")) ?: number(entry.get("billing_multiplier"))
                else -> number(entry.get("creditMultiplier")) ?: entry.text("credits")?.let { Regex("^x([0-9]+(?:\\.[0-9]+)?)(?: credits)?$").matchEntire(it)?.groupValues?.get(1)?.toBigDecimal()?.stripTrailingZeros()?.toPlainString() }
            }
            AccountProviderModel(id, entry.text("display_name") ?: entry.text("displayName") ?: entry.text("name") ?: id, multiplier)
        }.distinctBy { it.id }
    }

    fun balance(kind: AccountProviderKind, payload: JsonObject): String? {
        check(payload)
        val data = payload.obj("data") ?: payload
        return when (kind) {
            AccountProviderKind.RACCOON -> (number(data.get("total_chargeable_points")) ?: number(data.get("available_points")))?.let { "$it points" }
            AccountProviderKind.TRAE -> {
                val summary = data.obj("usage_summary") ?: return null
                val total = number(summary.get("total_amount"))?.toBigDecimal() ?: return null
                val consumed = number(summary.get("consumed_amount"))?.toBigDecimal() ?: return null
                "${total.subtract(consumed).stripTrailingZeros().toPlainString()} credits (official summary)"
            }
            AccountProviderKind.WORKBUDDY -> {
                val resourceData = data.obj("Response")?.obj("Data") ?: data
                if (!resourceData.has("Accounts")) return null
                val resources = resourceData.array("Accounts").toList()
                if (resources.isEmpty()) return null
                var total = java.math.BigDecimal.ZERO
                var remaining = java.math.BigDecimal.ZERO
                for (element in resources) {
                    val resource = element.objectOrNull() ?: return null
                    val size = (number(resource.get("CycleCapacitySizePrecise")) ?: number(resource.get("CycleCapacitySize")))?.toBigDecimal() ?: return null
                    val left = (number(resource.get("CycleCapacityRemainPrecise")) ?: number(resource.get("CycleCapacityRemain")))?.toBigDecimal() ?: return null
                    total += size
                    remaining += left.min(size)
                }
                "${remaining.stripTrailingZeros().toPlainString()} / ${total.stripTrailingZeros().toPlainString()} cycle credits"
            }
        }
    }

    fun expiry(token: String): Long? = try {
        val part = token.split('.').getOrNull(1)
        if (part == null) null else JsonParser.parseString(String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)).asJsonObject.text("exp")?.toLongOrNull()?.let { Math.multiplyExact(it, 1000) }
    } catch (_: RuntimeException) { null }
}

internal class AccountCredential(
    val kind: AccountProviderKind,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long?,
    val deviceId: String,
    val userId: String?,
    val domain: String? = null,
    val enterpriseId: String? = null,
) {
    fun json(): JsonObject = JsonObject().apply {
        addProperty("kind", kind.wireId)
        addProperty("accessToken", accessToken)
        addProperty("refreshToken", refreshToken)
        addProperty("expiresAt", expiresAt)
        addProperty("deviceId", deviceId)
        addProperty("userId", userId)
        addProperty("domain", domain)
        addProperty("enterpriseId", enterpriseId)
    }

    override fun toString(): String = "AccountCredential(${kind.wireId}, redacted)"
}
