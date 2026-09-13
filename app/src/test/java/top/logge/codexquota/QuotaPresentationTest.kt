package top.logge.codexquota

import org.junit.Assert.*
import org.junit.Test

internal fun auth(id: String = "one", access: String = "access-$id") =
    CodexAuth.AuthState(access, "refresh-$id", "", id, "pro", false, "$id@example.test", "user-$id")
internal fun account(id: String = "one", used: Int = 25, now: Long = 2_000_000_000_000L) =
    Account(id, "Account $id", auth(id), Quota("pro", null, WindowQuota(used, "2h", now + 7_200_000)), now)

class QuotaPresentationTest {
    private val now = 2_000_000_000_000L
    @Test fun remainingQuotaIsPerAccount() {
        assertEquals(75, QuotaPresentation.card(account("one", 25), now).windows.single().remaining)
        assertEquals(81, QuotaPresentation.card(account("two", 19), now).windows.single().remaining)
    }
    @Test fun weeklyOnlyHasNoArtificialPrimaryWindow() {
        val card = QuotaPresentation.card(account(), now)
        assertEquals(listOf("Woche"), card.windows.map { it.label })
    }
    @Test fun cacheCountdownAdvances() {
        val a = account()
        assertEquals("Reset in 2h 0m", QuotaPresentation.card(a, now).windows.single().resetText)
        assertEquals("Reset in 1h 0m", QuotaPresentation.card(a, now + 3_600_000).windows.single().resetText)
    }
    @Test fun expiredResetNeverFabricatesRefilledQuota() {
        val card = QuotaPresentation.card(account(), now + 7_200_001)
        assertEquals("zuletzt 75 % frei", card.windows.single().text)
        assertEquals("Reset fällig", card.windows.single().resetText)
    }
    @Test fun freshCacheStillReportsFailedRefresh() {
        val card = QuotaPresentation.card(account().copy(error = "Erneut anmelden"), now)
        assertTrue(card.stale)
        assertTrue(card.status.contains("Erneut anmelden"))
    }
    @Test fun oldCacheIsUnavailableInsteadOfFull() {
        assertTrue(QuotaPresentation.card(account(), now + QuotaPresentation.MAX_CACHE_MS + 1).windows.isEmpty())
    }
    @Test fun legacyDurationGetsAnchoredOnce() {
        val window = WindowQuota(25, "2h 30m").anchoredAt(now)
        assertEquals(now + 9_000_000, window.resetsAtMs)
        assertEquals(window, window.anchoredAt(now + 9999))
    }
}
