package top.logge.codexquota

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageParserTest {
    @Test
    fun parsesBothWindowsAndPreservesPrimaryAndWeeklyLabels() {
        val quota = UsageParser.parseUsage(
            JSONObject(
                """
                {
                  "plan_type": "plus",
                  "rate_limit": {
                    "primary_window": { "used_percent": 42, "resets_in": "2h 30m" },
                    "secondary_window": { "used_percent": 61, "resets_in": "2d 0h" }
                  },
                  "credits": { "balance": "12" }
                }
                """.trimIndent(),
            ),
            planType = "codex",
        )

        assertEquals("plus", quota.plan)
        assertEquals(WindowQuota(42, "2h 30m"), quota.primary)
        assertEquals(WindowQuota(61, "2d 0h"), quota.weekly)
        assertEquals("12", quota.creditsBalance)

        val presentation = QuotaPresentation.fromQuota(quota, "live quota")
        assertTrue(presentation.primary.available)
        assertEquals(50, presentation.primary.expected)
        assertEquals(84, presentation.primary.estimate)
        assertEquals("5h 42→84% · rem 2h 30m", presentation.primary.text)
        assertTrue(presentation.weekly.available)
        assertEquals(71, presentation.weekly.expected)
        assertEquals(86, presentation.weekly.estimate)
        assertEquals("W 61→86% · rem 2d 0h", presentation.weekly.text)
        assertEquals("on track", presentation.liveText)
    }

    @Test
    fun parsesWeeklyOnlyWithoutMislabelingItAsPrimary() {
        val quota = UsageParser.parseUsage(
            JSONObject(
                """
                {
                  "rate_limit": {
                    "secondary_window": { "usedPercent": 35, "resets_in": "3d 4h" }
                  }
                }
                """.trimIndent(),
            ),
            planType = "pro",
        )

        assertEquals("pro", quota.plan)
        assertNull(quota.primary)
        assertEquals(WindowQuota(35, "3d 4h"), quota.weekly)

        val presentation = QuotaPresentation.fromQuota(quota, "live quota")
        assertFalse(presentation.primary.available)
        assertEquals("5h unavailable", presentation.primary.text)
        assertEquals(0, presentation.primary.used)
        assertNull(presentation.primary.expected)
        assertNull(presentation.primary.estimate)
        assertTrue(presentation.weekly.available)
        assertEquals(55, presentation.weekly.expected)
        assertEquals(64, presentation.weekly.estimate)
        assertEquals("W 35→64% · rem 3d 4h", presentation.weekly.text)
        assertEquals("ahead", presentation.liveText)
        assertFalse(presentation.primary.text.contains("35"))
        assertFalse(presentation.primary.text.contains("3d 4h"))
    }

    @Test
    fun malformedWindowPayloadDoesNotFabricateUsage() {
        val quota = UsageParser.parseUsage(
            JSONObject(
                """
                {
                  "rate_limit": {
                    "primary_window": [],
                    "secondary_window": { "used_percent": "not-a-number", "resets_in": {} }
                  }
                }
                """.trimIndent(),
            ),
            "codex",
        )

        assertNull(quota.primary)
        assertNull(quota.weekly)

        val presentation = QuotaPresentation.fromQuota(quota, "live quota")
        assertEquals("live quota", presentation.liveText)
        assertFalse(presentation.primary.available)
        assertEquals("5h unavailable", presentation.primary.text)
        assertFalse(presentation.weekly.available)
        assertEquals("W unavailable", presentation.weekly.text)
    }

    @Test
    fun noWindowPayloadDoesNotCrashAndRendersUnavailableWindows() {
        val quota = UsageParser.parseUsage(JSONObject("""{ "rate_limit": {}, "credits": {} }"""), "codex")

        assertNull(quota.primary)
        assertNull(quota.weekly)

        val presentation = QuotaPresentation.fromQuota(quota, "live quota")
        assertEquals("live quota", presentation.liveText)
        assertEquals("5h unavailable", presentation.primary.text)
        assertFalse(presentation.primary.available)
        assertEquals("W unavailable", presentation.weekly.text)
        assertFalse(presentation.weekly.available)
    }
}
