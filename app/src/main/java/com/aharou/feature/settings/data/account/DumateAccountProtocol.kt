package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant

/** Website cookies and gateway tickets are intentionally separate credentials. */
internal object DumateAccountProtocol {
    const val WEB = "https://www.dumate.cn"
    const val GATEWAY = "https://dumate-svc.baidu.com/gateway/apis/v1"
    const val BALANCE_UNSUPPORTED = "balance_unsupported"

    fun isWebOrigin(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.host == "www.dumate.cn" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() &&
            try { java.net.URI(value).rawUserInfo == null } catch (_: java.net.URISyntaxException) { false }
    }

    fun requireGateway(value: String) {
        val url = value.toHttpUrlOrNull()
        val hasUserInfo = try { java.net.URI(value).rawUserInfo != null } catch (_: java.net.URISyntaxException) { true }
        if (hasUserInfo || url == null || url.scheme != "https" || url.host != "dumate-svc.baidu.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            url.encodedPath !in setOf("/gateway/apis/v1/models", "/gateway/apis/v1/chat/completions")) {
            throw AccountProviderException("invalid_gateway", "Unexpected Dumate gateway", 400)
        }
    }

    fun requireCookie(cookie: String): String {
        if (cookie.isBlank() || cookie.length > 32768 || cookie.any { it.code !in 32..126 } || !cookie.contains('=')) {
            throw AccountProviderException("invalid_credentials", "Invalid Dumate website session", 400)
        }
        return cookie
    }

    fun models(payload: JsonObject): List<AccountProviderModel> {
        AccountProviderProtocol.check(payload)
        return payload.array("data").mapNotNull { row ->
            val value = row.objectOrNull() ?: return@mapNotNull null
            val id = value.text("id") ?: return@mapNotNull null
            AccountProviderModel(id, value.text("name") ?: id)
        }.distinctBy { it.id }
    }

    fun restore(value: JsonObject): AccountCredential {
        if (value.text("kind") != AccountProviderKind.DUMATE.wireId) {
            throw AccountProviderException("account_mismatch", "Dumate credentials require their provider kind", 400)
        }
        val cookie = requireCookie(value.text("webCookie") ?: throw AccountProviderException("login_required", "Sign in to Dumate again", 401))
        val token = value.text("accessToken")?.takeIf { it.length <= 32768 && it.all { char -> char.code in 33..126 } }
            ?: throw AccountProviderException("invalid_credentials", "Invalid Dumate gateway ticket", 400)
        val expiry = value.text("expiresAt")?.toLongOrNull()
            ?: throw AccountProviderException("invalid_credentials", "Dumate ticket expiry is missing", 400)
        val uid = value.text("userId") ?: throw AccountProviderException("invalid_credentials", "Dumate account identity is missing", 400)
        return AccountCredential(AccountProviderKind.DUMATE, token, null, expiry, value.text("deviceId").orEmpty(), uid, webCookie = cookie)
    }

    fun exchange(manager: AccountProviderManager, cookie: String, deviceId: String, previousUserId: String? = null): AccountCredential {
        val webHeaders = mapOf("Cookie" to requireCookie(cookie), "Referer" to "$WEB/", "Origin" to WEB)
        val ticket = manager.readJson(manager.request("$WEB/api/dumate/app/token/get", headers = webHeaders)).obj("result")
            ?: throw AccountProviderException("invalid_credentials", "Dumate did not return a gateway ticket", 401)
        val token = ticket.text("token")?.takeIf { it.length <= 32768 && it.all { char -> char.code in 33..126 } }
            ?: throw AccountProviderException("invalid_credentials", "Invalid Dumate gateway ticket", 401)
        val expiry = try { Instant.parse(ticket.text("expired_at")).toEpochMilli() } catch (_: RuntimeException) { null }
        if (expiry == null || expiry <= System.currentTimeMillis()) {
            throw AccountProviderException("invalid_credentials", "Dumate ticket has no valid expiry", 401)
        }
        val account = manager.readJson(manager.request("$WEB/api/dumate/user/info", headers = webHeaders))
        val user = account.obj("result") ?: throw AccountProviderException("invalid_credentials", "Dumate account identity is missing", 401)
        val uid = user.text("uid") ?: user.text("userId")
            ?: throw AccountProviderException("invalid_credentials", "Dumate account identity is missing", 401)
        if (previousUserId != null && uid != previousUserId) throw AccountProviderException("account_mismatch", "Dumate website account changed; sign in again", 401)
        return AccountCredential(AccountProviderKind.DUMATE, token, null, expiry, deviceId, uid, webCookie = cookie)
    }

    fun validateDirectory(manager: AccountProviderManager, credential: AccountCredential) {
        val payload = manager.readJson(manager.request("$GATEWAY/models", headers = manager.headers(credential)))
        if (models(payload).isEmpty()) throw AccountProviderException("empty_catalog", "Dumate returned no usable gateway models")
    }
}
