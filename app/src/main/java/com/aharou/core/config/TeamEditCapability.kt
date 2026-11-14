package com.aharou.core.config

import java.security.MessageDigest
import java.security.SecureRandom
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process capability, deliberately absent from config schemas and default model prompts. */
object TeamEditCapability {
    private data class Grant(val token: String, val workspace: String, val expires: Long)
    private var grant: Grant? = null
    private val expiryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var expiry: kotlinx.coroutines.Job? = null
    private val _address = MutableStateFlow<String?>(null)
    val address = _address.asStateFlow()
    private val fields = Regex("(?:teams\\.project:[0-9a-f]+\\.routes|team\\.stages\\.project:[0-9a-f]+:[0-9]+\\.(?:communication|message_limit|communication_guide))")
    const val PREFIX = "aharou-team://"

    @Synchronized fun issue(workspace: String): String {
        require(workspace.isNotBlank()) { "A workspace is required" }
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        grant = Grant(token, workspace, System.currentTimeMillis() + 60 * 60 * 1000)
        expiry?.cancel()
        expiry = expiryScope.launchExpiry(token)
        return "$PREFIX$token/".also { _address.value = it }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchExpiry(token: String) = launch {
        kotlinx.coroutines.delay(60 * 60 * 1000L)
        synchronized(this@TeamEditCapability) { if (grant?.token == token) { grant = null; _address.value = null } }
    }

    @Synchronized fun revoke() { expiry?.cancel(); expiry = null; grant = null; _address.value = null }
    fun allowed(path: String) = fields.matches(path)
    fun fingerprint(json: String): String = MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun childAddress(base: String, path: String): String = base + URLEncoder.encode(path, "UTF-8")

    @Synchronized fun <T> use(address: String, workspace: String?, action: (String) -> T): T {
        val current = grant ?: error("Team capability is disabled or revoked")
        if (current.expires <= System.currentTimeMillis()) { grant = null; _address.value = null; error("Team capability expired; enable again") }
        check(workspace == current.workspace) { "Team capability belongs to another workspace" }
        require(address.startsWith(PREFIX))
        val rest = address.removePrefix(PREFIX)
        val token = rest.substringBefore('/')
        check(MessageDigest.isEqual(current.token.toByteArray(), token.toByteArray())) { "Invalid team capability" }
        val path = URLDecoder.decode(rest.substringAfter('/', ""), "UTF-8")
        check(path.isEmpty() || allowed(path)) { "Team capability only allows workflow routes and stage communication" }
        return action(path)
    }
}
