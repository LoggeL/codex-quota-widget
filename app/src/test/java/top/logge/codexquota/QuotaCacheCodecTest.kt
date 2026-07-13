package top.logge.codexquota

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class QuotaCacheCodecTest {
    @Test
    fun weeklyOnlyRoundTripKeepsPrimaryAbsent() {
        val quota = Quota(
            plan = "pro",
            primary = null,
            weekly = WindowQuota(35, "3d 4h"),
            creditsBalance = "12",
        )

        val encoded = QuotaCacheCodec.encode(quota)

        assertFalse(encoded.has("primary"))
        assertEquals(quota, QuotaCacheCodec.decode(encoded))
    }

    @Test
    fun legacyWeeklyOnlyCacheDropsFabricatedPrimaryWindow() {
        val legacy = JSONObject(
            """
            {
              "plan": "pro",
              "primary": { "used": 0, "reset": "?" },
              "weekly": { "used": 35, "reset": "3d 4h" }
            }
            """.trimIndent(),
        )

        val decoded = QuotaCacheCodec.decode(legacy)

        assertNull(decoded.primary)
        assertEquals(WindowQuota(35, "3d 4h"), decoded.weekly)
    }
}
