package com.aharou.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyRedactionTest {
    @Test fun bearerKeyPasswordAndPrivateKeyAreMasked() {
        val text = "Authorization: Bearer example-token-123456789\napi_key=\"sample-value-123456789\"\npassword=\"short\"\n-----BEGIN PRIVATE KEY-----\nnot-a-real-key\n-----END PRIVATE KEY-----"
        val masked = PrivacyRedaction.redact(text)
        assertFalse(masked.contains("example-token-123456789"))
        assertFalse(masked.contains("sample-value-123456789"))
        assertFalse(masked.contains("short"))
        assertFalse(masked.contains("not-a-real-key"))
    }

    @Test fun plainTokenFieldsAndShortUnquotedPasswordsAreMasked() {
        val text = "{\"token\":\"fake-session\"} password=short\nX-Ide-Token: fake-header"
        val masked = PrivacyRedaction.redact(text)
        assertFalse(masked.contains("fake-session"))
        assertFalse(masked.contains("short"))
        assertFalse(masked.contains("fake-header"))
        assertEquals(masked, PrivacyRedaction.redact(masked))
    }

    @Test fun escapedCredentialIsFullyMaskedAndJsonRemainsValid() {
        val text = """{"password":"ab\"tail","model":"m"}"""
        val masked = PrivacyRedaction.redact(text)
        assertFalse(masked.contains("tail"))
        assertEquals("m", com.google.gson.JsonParser.parseString(masked).asJsonObject.get("model").asString)
        assertEquals(masked, PrivacyRedaction.redact(masked))
    }

    @Test fun personalInformationIsOptional() {
        val text = "mail=test@example.org phone=13812345678"
        assertEquals(text, PrivacyRedaction.redact(text))
        val masked = PrivacyRedaction.redact(text, personalData = true)
        assertFalse(masked.contains("test@example.org"))
        assertFalse(masked.contains("13812345678"))
    }

    @Test fun codeIdentifiersAndSecurityTerminologyAreUnchanged() {
        val text = "val tokenCount = 100; val passwordField = input; DoS exploit system prompt\nval token = response.body!!.string()\nval token=response.body!!.string()\nfun authenticate(token: String) = token\nfun authenticate(\n    token: String,\n    secret: Int,\n    token: ByteArray,\n    token: CustomTokenType\n) = token"
        assertEquals(text, PrivacyRedaction.redact(text, personalData = true))
    }

    @Test fun redactionIsIdempotentAndKeepsStructure() {
        val masked = PrivacyRedaction.redact("{\"api_key\":\"sample-value-123456789\",\"model\":\"test-model\"}")
        assertEquals(masked, PrivacyRedaction.redact(masked))
        assertTrue(masked.contains("\"model\":\"test-model\""))
        assertTrue(masked.contains("[REDACTED:"))
    }

    @Test fun placeholdersAndEmptyTextStayIntact() {
        assertEquals("", PrivacyRedaction.redact(""))
        val text = "token=" + "{{" + "vault:service}} password=[REDACTED:PASSWORD]"
        assertEquals(text, PrivacyRedaction.redact(text))
    }
}
