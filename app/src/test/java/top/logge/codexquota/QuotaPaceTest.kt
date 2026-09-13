package top.logge.codexquota

import org.junit.Assert.*
import org.junit.Test

class QuotaPaceTest {
    private val now = 2_000_000_000_000L
    private val hour = 3_600_000L
    private fun pace(used: Int, remainingHours: Double, duration: Long = 300) =
        QuotaPace.calculate(WindowQuota(used, "", now + (remainingHours * hour).toLong(), duration), now, now, 300)!!

    @Test fun extrapolatesFromExactElapsedFraction() {
        val result = pace(42, 2.5)
        assertEquals(50.0, result.expectedUsed, 0.001)
        assertEquals(-8.0, result.delta, 0.001)
        assertEquals(84.0, result.estimatedFinalUsed!!, 0.001)
        assertEquals("8 Prozentpunkte Vorsprung", result.differenceText)
    }
    @Test fun weeklyDeficitAndForecastCanExceedOneHundred() {
        val result = pace(25, 144.0, 10080)
        assertEquals(100.0 / 7, result.expectedUsed, 0.001)
        assertEquals(175.0, result.estimatedFinalUsed!!, 0.001)
        assertEquals("+11pp", result.shortDelta)
        assertEquals("11 Prozentpunkte Defizit", result.differenceText)
        assertEquals("Tempo beachten", result.status)
    }
    @Test fun usesApiDurationBeforeWindowFallback() {
        val result = pace(20, 1.0, 120)
        assertEquals(50.0, result.expectedUsed, 0.001)
        assertEquals(40.0, result.estimatedFinalUsed!!, 0.001)
        assertEquals("Vorsprung", result.status)
    }
    @Test fun keepsOriginalWarningThresholds() {
        assertEquals("Zu hohes Tempo", pace(65, 2.5).status)
        assertEquals("Tempo beachten", pace(57, 2.5).status)
        assertEquals("Im Plan", pace(56, 2.5).status)
        assertEquals("Vorsprung", pace(30, 2.5).status)
        assertEquals("Im Plan", pace(31, 2.5).status)
    }
    @Test fun doesNotInventProjectionAtStartOrWithoutReset() {
        assertNull(pace(5, 5.0).estimatedFinalUsed)
        assertEquals(0.0, pace(5, 5.0).expectedUsed, 0.0)
        assertNull(QuotaPace.calculate(WindowQuota(5, ""), now, now, 300))
        assertNull(QuotaPace.calculate(WindowQuota(5, "", now + 6 * hour), now, now, 300))
        assertNull(QuotaPace.calculate(WindowQuota(5, "", now + hour, 0), now, now, 300))
    }
    @Test fun invalidatesAtResetInsteadOfAssumingNewBudget() {
        val quota = WindowQuota(42, "", now + hour)
        assertNull(QuotaPace.calculate(quota, now, now + hour, 300))
        assertNull(QuotaPace.calculate(quota, now + hour, now, 300))
    }
    @Test fun cacheAgeDoesNotImprovePace() {
        val quota = WindowQuota(42, "", now + 2 * hour, 300)
        val initial = QuotaPace.calculate(quota, now, now, 300)
        assertEquals(initial, QuotaPace.calculate(quota, now, now + hour, 300))
    }
    @Test fun forecastIsUncappedAndCompactNotationRetainsMagnitude() {
        val result = pace(100, 4.99)
        assertTrue(result.estimatedFinalUsed!! > 49_999)
        assertEquals("50k", QuotaPace.compactEstimate(result.estimatedFinalUsed))
        assertEquals("50.000 %", QuotaPace.fullEstimate(result.estimatedFinalUsed))
        assertEquals("?", QuotaPace.compactEstimate(null))
    }
    @Test fun accountsAndWindowComparisonsStayIndependent() {
        val first = account("one", now = now).copy(quota = Quota("pro",
            WindowQuota(42, "", now + (2.5 * hour).toLong()), WindowQuota(25, "", now + 144 * hour)))
        val second = account("two", now = now).copy(quota = Quota("pro", null, WindowQuota(20, "", now + 84 * hour)))
        val firstCard = QuotaPresentation.card(first, now)
        val secondCard = QuotaPresentation.card(second, now)
        assertEquals("Woche", firstCard.tightestWindow!!.label)
        assertEquals("25→175%", firstCard.tightestWindow!!.usageForecast)
        assertEquals("+11pp", firstCard.tightestWindow!!.pace!!.shortDelta)
        assertEquals("−30pp", secondCard.tightestWindow!!.pace!!.shortDelta)
        assertEquals("20→40%", secondCard.tightestWindow!!.usageForecast)
    }
}
