package com.aharou.feature.settings.data.account

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.UUID

/** Only local opaque IDs may leave the encrypted vault. */
data class AccountPoolEntry(val accountId: String, val position: Int)
data class AccountPoolIssue(val accountId: String, val errorCode: String)
internal data class PoolAccount(val accountId: String, val credential: AccountCredential)

internal class AccountPool(val kind: AccountProviderKind, val accounts: List<PoolAccount>) {
    fun json(): JsonObject = (accounts.firstOrNull()?.credential?.json() ?: JsonObject()).apply {
        // Keep the original fields readable by old single-account importers.
        addProperty("kind", kind.wireId)
        addProperty("poolVersion", 2)
        add("accounts", JsonArray().apply {
            accounts.forEach { slot -> add(slot.credential.json().apply { addProperty("accountId", slot.accountId) }) }
        })
    }

    fun append(credential: AccountCredential): AccountPool {
        if (credential.kind != kind) throw AccountProviderException("account_mismatch", "Account provider does not match", 400)
        val match = accounts.indexOfFirst { slot ->
            !credential.userId.isNullOrBlank() && slot.credential.userId == credential.userId &&
                slot.credential.domain == credential.domain && slot.credential.enterpriseId == credential.enterpriseId
        }
        val next = accounts.toMutableList()
        if (match >= 0) next[match] = PoolAccount(next[match].accountId, credential)
        else {
            if (next.size >= 100) throw AccountProviderException("account_limit", "Too many accounts", 400)
            next.add(PoolAccount(UUID.randomUUID().toString(), credential))
        }
        return AccountPool(kind, next)
    }

    companion object {
        fun decode(value: JsonObject, expected: AccountProviderKind, parse: (JsonObject) -> AccountCredential): AccountPool {
            if (value.text("kind")?.let { it != expected.wireId } == true) throw AccountProviderException("account_mismatch", "Imported account provider does not match", 400)
            if (!value.has("poolVersion")) {
                if (value.has("accounts")) throw AccountProviderException("invalid_credentials", "Pool version is missing", 400)
                return AccountPool(expected, listOf(PoolAccount("legacy", parse(value))))
            }
            if (value.text("poolVersion") != "2") throw AccountProviderException("unsupported_pool_version", "Unsupported account pool version", 400)
            val rows = value.get("accounts")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw AccountProviderException("invalid_credentials", "Account pool requires an array", 400)
            if (rows.size() > 100) throw AccountProviderException("invalid_credentials", "Too many accounts", 400)
            val slots = rows.map { row ->
                val entry = row.objectOrNull() ?: throw AccountProviderException("invalid_credentials", "Invalid account slot", 400)
                if (entry.text("kind") != expected.wireId) throw AccountProviderException("account_mismatch", "Account pool contains another provider or region", 400)
                val id = entry.text("accountId")?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,256}")) }
                    ?: throw AccountProviderException("invalid_credentials", "Invalid account slot ID", 400)
                PoolAccount(id, parse(entry))
            }
            if (slots.map { it.accountId }.distinct().size != slots.size) throw AccountProviderException("invalid_credentials", "Duplicate account slot ID", 400)
            return AccountPool(expected, slots)
        }
    }
}

internal data class AccountCatalog(val models: List<AccountProviderModel>, val issues: List<AccountPoolIssue>)
