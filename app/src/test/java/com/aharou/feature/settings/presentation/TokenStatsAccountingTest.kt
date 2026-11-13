package com.aharou.feature.settings.presentation

import com.aharou.feature.settings.domain.service.pricedTokenCost
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenStatsAccountingTest {
    @Test fun missingOutputPriceIsNotFree() {
        assertNull(pricedTokenCost(100, 0, 20, 0, 1.0, null, null, null))
    }

    @Test fun missingUsedCachePriceIsUnknown() {
        assertNull(pricedTokenCost(100, 50, 20, 0, 1.0, 2.0, null, null))
        assertNull(pricedTokenCost(100, 0, 20, 30, 1.0, 2.0, null, null))
    }

    @Test fun unusedPricesAreNotRequiredAndExplicitZeroIsFree() {
        assertEquals(0.0001, pricedTokenCost(100, 0, 0, 0, 1.0, null, null, null)!!, 1e-12)
        assertEquals(0.0, pricedTokenCost(100, 30, 20, 10, 0.0, 0.0, 0.0, 0.0)!!, 0.0)
    }

    @Test fun cacheIsPartOfInputAndNeverDoubleCounted() {
        assertEquals(0.00018125, pricedTokenCost(100, 30, 20, 10, 2.0, 1.0, 0.25, 3.375)!!, 1e-12)
    }

    @Test fun invalidCountsAndPricesStayUnknown() {
        assertNull(pricedTokenCost(10, 11, 0, 0, 1.0, null, 1.0, null))
        assertNull(pricedTokenCost(-1, 0, 0, 0, 1.0, null, null, null))
        assertNull(pricedTokenCost(10, 0, 0, 0, Double.NaN, null, null, null))
        assertNull(pricedTokenCost(10, 0, 0, 0, -1.0, null, null, null))
    }

    @Test fun sevenDaysMeansSevenLocalDatesAcrossDst() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val now = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.NOVEMBER, 3, 12, 30) }.timeInMillis
        val start = Calendar.getInstance(zone).apply { timeInMillis = tokenStatsStartOfDay(now, 6, zone) }
        assertEquals(Calendar.OCTOBER, start.get(Calendar.MONTH))
        assertEquals(28, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, start.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, start.get(Calendar.MINUTE))
    }
}
