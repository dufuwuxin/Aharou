package com.aharou.feature.settings.presentation

import com.aharou.feature.settings.domain.model.PROVIDER_MAX_CONCURRENT_REQUESTS
import com.aharou.feature.settings.presentation.component.parseProviderConcurrencyLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderConcurrencyLimitParserTest {
    @Test fun acceptsOnlyTheSupportedPositiveRange() {
        assertNull(parseProviderConcurrencyLimit("0"))
        assertNull(parseProviderConcurrencyLimit("33"))
        assertNull(parseProviderConcurrencyLimit(""))
        assertNull(parseProviderConcurrencyLimit("not-a-number"))
        assertEquals(1, parseProviderConcurrencyLimit("1"))
        assertEquals(PROVIDER_MAX_CONCURRENT_REQUESTS, parseProviderConcurrencyLimit("32"))
        assertEquals(4, parseProviderConcurrencyLimit(" 4 "))
    }
}
