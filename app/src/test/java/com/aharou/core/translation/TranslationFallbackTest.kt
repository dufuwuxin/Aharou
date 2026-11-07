package com.aharou.core.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class TranslationFallbackTest {
    @Test fun classInitializationFailurePreservesOriginal() = runTest {
        val result = translationOrOriginal("original") {
            throw ExceptionInInitializerError(IllegalArgumentException("invalid pattern"))
        }
        assertEquals("original", result)
    }

    @Test fun failedClassAndOrdinaryExceptionsPreserveOriginal() = runTest {
        assertEquals("original", translationOrOriginal("original") { throw NoClassDefFoundError("translator") })
        assertEquals("original", translationOrOriginal("original") { throw IllegalStateException("unavailable") })
    }

    @Test fun cancellationIsNotConvertedIntoSuccess() = runTest {
        val cancellation = CancellationException("page closed")
        try {
            translationOrOriginal("original") { throw cancellation }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test fun fatalVmErrorsAreNotSwallowed() = runTest {
        val error = OutOfMemoryError("fixture")
        try {
            translationOrOriginal("original") { throw error }
            fail("VM failure must propagate")
        } catch (actual: OutOfMemoryError) {
            assertSame(error, actual)
        }
    }
}
