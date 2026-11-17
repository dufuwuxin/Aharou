package com.aharou.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiteralMaskingTest {
    @Test fun shortWordsAndRegexCharactersAreLiteral() {
        val masker = OutboundMasker.create(MaskingRules.literalRules(listOf("甲", "a.b", "[x]")))
        val input = "甲 a.b [x] aZb"
        val masked = masker.mask(input)
        assertFalse(masked.contains("甲"))
        assertFalse(masked.contains("a.b"))
        assertFalse(masked.contains("[x]"))
        assertEquals(input, masker.restoreComplete(masked))
        assertEquals(3, masker.maskedCount())
    }

    @Test fun longerMatchesWinWithoutRemaskingGeneratedTokens() {
        val masker = OutboundMasker.create(MaskingRules.literalRules(listOf("SECRET", "SECRET项目", "项目")))
        val masked = masker.mask("SECRET项目 / SECRET / 项目")
        assertEquals(3, masker.maskedCount())
        assertEquals("SECRET项目 / SECRET / 项目", masker.restoreComplete(masked))
    }

    @Test fun repeatedValuesHaveStableDistinctPlaceholdersAndRestoreAcrossChunks() {
        val masker = OutboundMasker.create(MaskingRules.literalRules(listOf("内部甲", "内部乙", "内部甲")))
        val first = masker.mask("内部甲")
        val second = masker.mask("内部乙")
        assertEquals(first, masker.mask("内部甲"))
        assertNotEquals(first, second)
        assertEquals(2, masker.maskedCount())
        val stream = first + second
        val split = stream.length / 3
        assertEquals("内部甲内部乙", masker.restore(stream.take(split)) + masker.restore(stream.drop(split)) + masker.flush())
    }

    @Test fun separateRequestsDoNotShareRestorationValues() {
        val one = OutboundMasker.create(MaskingRules.literalRules(listOf("内部甲")))
        val two = OutboundMasker.create(MaskingRules.literalRules(listOf("内部乙")))
        val placeholder = one.mask("内部甲")
        assertEquals(placeholder, two.restoreComplete(placeholder))
        assertEquals("内部甲", one.restoreComplete(placeholder))
    }

    @Test fun generatedPlaceholdersRemainStableOnRepeatedMasking() {
        for (term in listOf("SECRET", "{", "}", "0")) {
            val masker = OutboundMasker.create(MaskingRules.literalRules(listOf(term)))
            val first = masker.mask(term)
            assertEquals(first, masker.mask(first))
            assertEquals(term, masker.restoreComplete(first))
        }
    }

    @Test fun shortKeywordsCannotDisableDefaultCredentialRecognition() {
        val previous = MaskingRules.literalValues
        try {
            MaskingRules.literalValues = { listOf("token", "sk-", "SECRET") }
            val masker = OutboundMasker.create()
            val raw = "token=abcdefghijklmno sk-abcdefghijklmnopqrstuv SECRET"
            val masked = masker.mask(raw)
            assertFalse(masked.contains("abcdefghijklmno"))
            assertFalse(masked.contains("abcdefghijklmnopqrstuv"))
            assertEquals(masked, masker.mask(masked))
            assertEquals(raw, masker.restoreComplete(masked))
        } finally { MaskingRules.literalValues = previous }
    }

    @Test fun emptySelectionDoesNotChangeText() {
        val masker = OutboundMasker.create(MaskingRules.literalRules(listOf("", "")))
        assertEquals("内部甲", masker.mask("内部甲"))
        assertEquals(0, masker.maskedCount())
    }
}
