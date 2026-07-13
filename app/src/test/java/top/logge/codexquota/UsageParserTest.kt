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
                    "primary_window": { "used_percent": 40, "resets_in": "2h 30m" },
                    "secondary_window": { "used_percent": 75, "resets_in": "5d 1h" }
                  },
                  "credits": { "balance": "12" }
                }
                """.trimIndent(),
            ),
            planType = "codex",
        )

        assertEquals("plus", quota.plan)
        assertEquals(WindowQuota(40, "2h 30m"), quota.primary)
        assertEquals(WindowQuota(75, "5d 1h"), quota.weekly)
        assertEquals("12", quota.creditsBalance)

        val presentation = QuotaPresentation.fromQuota(quota, "live quota")
        assertTrue(presentation.primary.available)
        assertTrue(presentation.primary.text.startsWith("5h 40"))
        assertTrue(presentation.weekly.available)
        assertTrue(presentation.weekly.text.startsWith("W 75"))
    }

    @Test
    fun parsesWeeklyOnlyWithoutMislabelingItAsPrimary() {
        val quota = UsageParser.parseUsage(
            JSONObject(
                """
                {
                  "rate_limit": {
                    "secondary_window": { "usedPercent": 61, "resetAfterSeconds": 345600 }
                  }
                }
                """.trimIndent(),
            ),
            planType = "pro",
        )

        assertEquals("pro", quota.plan)
        assertNull(quota.primary)
        assertEquals(61, quota.weekly?.used)
        assertEquals("4d 0h", quota.weekly?.reset)

        val presentation = QuotaPresentation.fromQuota(quota, "live quota")
        assertFalse(presentation.primary.available)
        assertEquals("5h unavailable", presentation.primary.text)
        assertEquals(0, presentation.primary.used)
        assertTrue(presentation.weekly.available)
        assertTrue(presentation.weekly.text.startsWith("W 61"))
    }

    @Test
    fun malformedOrNoWindowPayloadDoesNotCrashAndRendersUnavailableWindows() {
        val quota = UsageParser.parseUsage(JSONObject("""{ "rate_limit": { "primary_window": null }, "credits": {} }"""), "codex")

        assertNull(quota.primary)
        assertNull(quota.weekly)

        val presentation = QuotaPresentation.fromQuota(quota, "live quota")
        assertEquals("live quota", presentation.liveText)
        assertFalse(presentation.primary.available)
        assertEquals("5h unavailable", presentation.primary.text)
        assertFalse(presentation.weekly.available)
        assertEquals("W unavailable", presentation.weekly.text)
    }
}
