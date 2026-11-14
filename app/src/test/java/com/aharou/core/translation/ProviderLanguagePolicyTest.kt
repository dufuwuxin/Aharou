package com.aharou.core.translation

import org.junit.Assert.*
import org.junit.Test

class ProviderLanguagePolicyTest {
    @Test fun wireValuesAreExplicitAndNativeIsSupported() {
        assertEquals(ProviderLanguagePolicy.NATIVE, ProviderLanguagePolicy.fromWire("native"))
        assertEquals(ProviderLanguagePolicy.CHINESE, ProviderLanguagePolicy.fromWire("zh"))
        assertEquals(ProviderLanguagePolicy.ENGLISH, ProviderLanguagePolicy.fromWire("en"))
    }
    @Test(expected = IllegalArgumentException::class) fun unknownLanguageCannotSilentlyRoute() {
        ProviderLanguagePolicy.fromWire("fr")
    }
    @Test fun jsonEnvelopesAndJsonStringsAreNeverProse() {
        assertTrue(isStructuredTranslationPayload("{\"message\":\"Hello\"}"))
        assertTrue(isStructuredTranslationPayload("\"Hello\""))
        assertFalse(isStructuredTranslationPayload("Hello world"))
    }
}
