package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Keys remain inside the login attempt or the existing encrypted account vault. */
internal class OfficeAceDpop(private val encodedPrivateKey: String, private val publicJwk: JsonObject) {
    fun json(): JsonObject = JsonObject().apply {
        addProperty("privateKey", encodedPrivateKey)
        add("publicJwk", publicJwk.deepCopy())
    }

    fun proof(url: String, now: Long): String {
        val header = JsonObject().apply {
            addProperty("typ", "dpop+jwt"); addProperty("alg", "ES256"); add("jwk", publicJwk.deepCopy())
        }
        val payload = JsonObject().apply {
            addProperty("htm", "POST"); addProperty("htu", url)
            addProperty("iat", now / 1000); addProperty("jti", UUID.randomUUID().toString().replace("-", ""))
        }
        val input = "${encode(header.toString().toByteArray())}.${encode(payload.toString().toByteArray())}"
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getUrlDecoder().decode(encodedPrivateKey)))
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(input.toByteArray(Charsets.US_ASCII)); sign() }
        return "$input.${encode(joseSignature(der))}"
    }

    override fun toString(): String = "OfficeAceDpop(redacted)"

    companion object {
        fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        fun create(): OfficeAceDpop {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val key = pair.public as ECPublicKey
            fun coordinate(value: java.math.BigInteger): String = encode(value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it })
            return OfficeAceDpop(encode(pair.private.encoded), JsonObject().apply {
                addProperty("kty", "EC"); addProperty("crv", "P-256")
                addProperty("x", coordinate(key.w.affineX)); addProperty("y", coordinate(key.w.affineY))
            })
        }
        fun restore(value: JsonObject): OfficeAceDpop {
            val privateKey = value.text("privateKey") ?: throw AccountProviderException("invalid_credentials", "Missing OfficeAce DPoP key", 400)
            val jwk = value.obj("publicJwk") ?: throw AccountProviderException("invalid_credentials", "Missing OfficeAce DPoP public key", 400)
            require(jwk.text("kty") == "EC" && jwk.text("crv") == "P-256" && !jwk.has("d"))
            return OfficeAceDpop(privateKey, jwk.deepCopy())
        }
        // Android's ECDSA signer returns ASN.1 DER; JOSE requires fixed-width r || s.
        internal fun joseSignature(der: ByteArray): ByteArray {
            require(der.size in 8..72 && der[0] == 0x30.toByte() && der[2] == 0x02.toByte())
            val rSize = der[3].toInt() and 255
            val sStart = 4 + rSize
            require(sStart + 2 < der.size && der[sStart] == 0x02.toByte())
            val sSize = der[sStart + 1].toInt() and 255
            require(sStart + 2 + sSize == der.size)
            fun scalar(start: Int, size: Int): ByteArray {
                val bytes = der.copyOfRange(start, start + size).dropWhile { it == 0.toByte() }.toByteArray()
                require(bytes.size <= 32)
                return ByteArray(32 - bytes.size) + bytes
            }
            return scalar(4, rSize) + scalar(sStart + 2, sSize)
        }

        internal fun signIam(url: String, credentials: JsonObject, now: Long): Map<String, String> {
            val target = url.toHttpUrl()
            fun field(name: String) = credentials.text(name) ?: throw AccountProviderException("invalid_credentials", "Incomplete OfficeAce IAM credentials", 401)
            val headers = sortedMapOf(
                "host" to target.toUri().rawAuthority,
                "x-sdk-date" to DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(now)),
                "x-security-token" to field("security_token"),
                "x-subscription-type" to "v2",
            )
            credentials.text("project_id")?.let { headers["x-project-id"] = it }
            val signed = headers.keys.joinToString(";")
            val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }
            val path = target.encodedPath.let { if (it.endsWith('/')) it else "$it/" }
            val canonical = "GET\n$path\n${target.encodedQuery.orEmpty()}\n$canonicalHeaders\n$signed\n${sha256("")}"
            val input = "SDK-HMAC-SHA256\n${headers["x-sdk-date"]}\n${sha256(canonical)}"
            val signature = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(field("secret_access_key").toByteArray(Charsets.UTF_8), "HmacSHA256"))
                doFinal(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            }
            headers["Authorization"] = "SDK-HMAC-SHA256 Access=${field("access_key_id")}, SignedHeaders=$signed, Signature=$signature"
            return headers
        }
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
