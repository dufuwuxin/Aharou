package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class AccountCheckInStatus { CLAIMED, ALREADY_CHECKED, UNKNOWN, UNSUPPORTED }
data class AccountCheckInResult(val status: AccountCheckInStatus, val awardedPoints: String? = null, val accountIssues: List<AccountPoolIssue> = emptyList())

internal object AccountCheckIn {
    fun perform(manager: AccountProviderManager, id: String, kind: AccountProviderKind): AccountCheckInResult {
        val credential = manager.credential(id, kind)
        if (kind == AccountProviderKind.WORKBUDDY_GLOBAL || kind == AccountProviderKind.TRAE_GLOBAL || kind == AccountProviderKind.LOOMY ||
            !credential.enterpriseId.isNullOrBlank()) return AccountCheckInResult(AccountCheckInStatus.UNSUPPORTED)
        return when (kind) {
            AccountProviderKind.WORKBUDDY -> {
                val payload = read(manager, id, kind, manager.endpoints.billing(kind) + "/v2/billing/meter/daily-checkin", "POST")
                if (already(payload, kind)) AccountCheckInResult(AccountCheckInStatus.ALREADY_CHECKED)
                else {
                    AccountProviderProtocol.check(payload)
                    if (payload.text("code") != "0") throw AccountProviderException("invalid_checkin", "Provider did not confirm check-in")
                    AccountCheckInResult(AccountCheckInStatus.CLAIMED)
                }
            }
            AccountProviderKind.RACCOON -> {
                val balanceUrl = manager.endpoints.raccoon + "/api/web/points/v1/balance"
                fun balance(): java.math.BigDecimal? {
                    val value = manager.readJson(manager.authorized(id, kind, balanceUrl))
                    val data = value.obj("data") ?: value
                    return (number(data.get("total_chargeable_points")) ?: number(data.get("available_points")))?.toBigDecimal()
                }
                val before = balance()
                val payload = read(manager, id, kind, manager.endpoints.raccoon + "/api/web/office/v3/setting_info", "GET",
                    mapOf("X-Client-Platform" to "desktop-windows", "X-Client-Version" to "v1.0.0"))
                AccountProviderProtocol.check(payload)
                val after = balance()
                // A popup alone is not evidence of credit; never synthesize a reward amount.
                val increased = before != null && after != null && after > before
                AccountCheckInResult(if (increased) AccountCheckInStatus.CLAIMED else AccountCheckInStatus.UNKNOWN)
            }
            AccountProviderKind.TRAE -> {
                val base = manager.endpoints.usage(kind) + "/trae/api/v2/ug/checkin_credits/"
                val status = data(read(manager, id, kind, base + "status", "POST").also { AccountProviderProtocol.check(it) })
                if (status.text("checked_in") == "true" || status.text("checkedIn") == "true") return AccountCheckInResult(AccountCheckInStatus.ALREADY_CHECKED)
                if (status.text("enable") == "false" || status.text("enabled") == "false") return AccountCheckInResult(AccountCheckInStatus.UNSUPPORTED)
                if (status.text("checked_in") != "false" && status.text("checkedIn") != "false") throw AccountProviderException("invalid_checkin", "Provider did not return check-in status")
                val claim = read(manager, id, kind, base + "claim", "POST")
                if (already(claim, kind)) return AccountCheckInResult(AccountCheckInStatus.ALREADY_CHECKED)
                AccountProviderProtocol.check(claim)
                val confirmed = data(read(manager, id, kind, base + "status", "POST").also { AccountProviderProtocol.check(it) })
                AccountCheckInResult(if (confirmed.text("checked_in") == "true" || confirmed.text("checkedIn") == "true") AccountCheckInStatus.CLAIMED else AccountCheckInStatus.UNKNOWN)
            }
            else -> AccountCheckInResult(AccountCheckInStatus.UNSUPPORTED)
        }
    }

    private fun data(payload: JsonObject): JsonObject = payload.obj("data") ?: payload.obj("result") ?: payload.obj("Result") ?: payload

    private fun already(payload: JsonObject, kind: AccountProviderKind): Boolean {
        val code = payload.text("code")
        if (code in setOf("11140", "11128", "14018", "14003", "6004", "12153")) return false
        if (payload.get("error")?.isJsonNull == false || payload.obj("Response")?.obj("Error") != null) return false
        if (kind == AccountProviderKind.TRAE && code == "1001") return true
        val message = (payload.text("message") ?: payload.text("msg")).orEmpty().trim().trimEnd('。', '.', '!').lowercase(java.util.Locale.ROOT)
        return message in setOf("已签到", "已经签到", "今日已签到", "今天已签到", "明天再来", "明日再来",
            "already checked in", "already checked-in", "already checked in today", "already checked in for today")
    }

    private fun read(manager: AccountProviderManager, id: String, kind: AccountProviderKind, url: String, method: String, extraHeaders: Map<String, String> = emptyMap()): JsonObject {
        return manager.authorized(id, kind, url, method, if (method == "POST") JsonObject() else null, extraHeaders).use { response ->
            if (!response.isSuccessful) throw AccountProviderException("checkin_failed", "Provider check-in request failed", response.code)
            val raw = response.body?.source()?.let { source ->
                source.request(AccountProviderManager.MAX_JSON.toLong() + 1)
                if (source.buffer.size > AccountProviderManager.MAX_JSON) throw AccountProviderException("response_too_large", "Account response too large")
                source.readUtf8()
            } ?: throw AccountProviderException("invalid_checkin", "Empty provider response")
            try { JsonParser.parseString(raw).objectOrNull() ?: throw IllegalArgumentException() }
            catch (_: RuntimeException) { throw AccountProviderException("invalid_checkin", "Invalid provider check-in response") }
        }
    }
}
