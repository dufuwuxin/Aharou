package com.aharou.core.privacy

import org.junit.Assert.*
import org.junit.Test

class MaskingRoundTripRegressionTest {
    @Test fun onceDetectedASecretIsAlsoMaskedWithoutItsAssignmentLabel() {
        val masker = OutboundMasker.create()
        val assigned = masker.mask("password=AbCdEf987654321")
        val placeholder = assigned.substringAfter('=')
        assertEquals("Use $placeholder again", masker.mask("Use AbCdEf987654321 again"))
    }

    @Test fun repeatedValuesAreMatchedLiterallyLongestFirst() {
        val masker = OutboundMasker.create()
        val shorter = masker.mask("password=AbCdEf987654").substringAfter('=')
        val longer = masker.mask("password=AbCdEf987654321").substringAfter('=')
        assertNotEquals(shorter, longer)
        assertEquals("$longer / $shorter", masker.mask("AbCdEf987654321 / AbCdEf987654"))
        assertEquals("AbCdEf987654321 / AbCdEf987654", masker.restore(masker.mask("AbCdEf987654321 / AbCdEf987654")))
    }

    @Test fun alreadyMaskedPlaceholdersAreNotRemaskedAsKnownValues() {
        val masker = OutboundMasker.create()
        val masked = masker.mask("password=AbCdEf987654321")
        repeat(3) { assertEquals(masked, masker.mask(masked)) }
    }
}
