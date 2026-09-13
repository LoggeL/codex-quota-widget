package top.logge.codexquota

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageParserTest {
    private fun parse(raw: String) = UsageParser.parseUsage(JSONObject(raw), "codex")
    @Test fun parsesBothWindows() {
        val q = parse("""{"plan_type":"plus","rate_limit":{"primary_window":{"used_percent":42,"resets_in":"2h 30m"},"secondary_window":{"used_percent":61,"resets_in":"2d 0h"}},"credits":{"balance":"12"}}""")
        assertEquals(WindowQuota(42, "2h 30m"), q.primary)
        assertEquals(WindowQuota(61, "2d 0h"), q.weekly)
        assertEquals("plus", q.plan)
        assertEquals("12", q.creditsBalance)
    }
    @Test fun currentWeeklyOnlyPayloadRemainsWeeklyEvenNearReset() {
        val q = parse("""{"plan_type":"pro","rate_limit":{"primary_window":{"used_percent":25,"limit_window_seconds":604800,"reset_after_seconds":3600,"reset_at":2000000000},"secondary_window":null}}""")
        assertNull(q.primary)
        assertEquals(25, q.weekly!!.used)
        assertEquals(10080L, q.weekly!!.durationMinutes)
        assertEquals(2000000000000L, q.weekly!!.resetsAtMs)
    }
    @Test fun weeklyOnlySecondaryKey() {
        val q = parse("""{"rate_limit":{"secondary_window":{"usedPercent":35,"resets_in":"3d 4h"}}}""")
        assertNull(q.primary)
        assertEquals(35, q.weekly!!.used)
    }
    @Test fun durationWinsOverFieldName() {
        val q = parse("""{"primary":{"usedPercent":18,"windowDurationMins":10080,"resetsIn":"1h"},"secondary":{"usedPercent":42,"windowDurationMins":300,"resetsIn":"2h 30m"}}""")
        assertEquals(18, q.weekly!!.used)
        assertEquals(42, q.primary!!.used)
    }
    @Test fun identicalWindowsDoNotEraseEachOther() {
        val q = parse("""{"rate_limit":{"primary_window":{"used_percent":20,"resets_in":"1h"},"secondary_window":{"used_percent":20,"resets_in":"1h"}}}""")
        assertNotNull(q.primary)
        assertNotNull(q.weekly)
    }
    @Test fun multiDayResetClassifiesWeekly() {
        val q = parse("""{"rateLimits":{"primary":{"usedPercent":22,"resetsIn":"5d 2h"}}}""")
        assertNull(q.primary)
        assertEquals(22, q.weekly!!.used)
    }
    @Test fun malformedWindowsNeverBecomeFullQuota() {
        val q = parse("""{"rate_limit":{"primary_window":[],"secondary_window":{"used_percent":"not-a-number"}}}""")
        assertNull(q.primary); assertNull(q.weekly)
    }
    @Test fun missingPercentageRemainsUnavailable() {
        val q = parse("""{"rate_limit":{"primary_window":{"used_percent":null},"secondary_window":{}}}""")
        assertNull(q.primary); assertNull(q.weekly)
    }
}
