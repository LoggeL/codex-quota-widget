package top.logge.codexquota

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CodexQuotaWidgetRemoteViewsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = 2_000_000_000_000L
    @Test fun rendersTwoIndependentWeeklyBarsInActualRemoteViews() {
        val root = CodexQuotaWidgetProvider.buildViews(context, listOf(account("one", 25), account("two", 19)), now = now)
            .apply(context, FrameLayout(context))
        val rows = root.findViewById<LinearLayout>(R.id.account_rows)
        assertEquals(2, rows.childCount)
        assertTrue(rows.getChildAt(0).findViewById<ImageView>(R.id.window_progress).contentDescription.contains("25 % verbraucht"))
        assertTrue(rows.getChildAt(1).findViewById<ImageView>(R.id.window_progress).contentDescription.contains("19 % verbraucht"))
        assertEquals("Woche", rows.getChildAt(0).findViewById<TextView>(R.id.window_label).text.toString())
        assertEquals(View.GONE, root.findViewById<View>(R.id.empty_state).visibility)
    }
    @Test fun reapplyAfterRemovingAccountClearsItsViews() {
        val root = CodexQuotaWidgetProvider.buildViews(context, listOf(account(), account("two")), now = now)
            .apply(context, FrameLayout(context))
        CodexQuotaWidgetProvider.buildViews(context, listOf(account()), now = now).reapply(context, root)
        assertEquals(1, root.findViewById<LinearLayout>(R.id.account_rows).childCount)
    }
    @Test fun noAccountsShowsLoginInsteadOfFakeZero() {
        val root = CodexQuotaWidgetProvider.buildViews(context, emptyList()).apply(context, FrameLayout(context))
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.empty_state).visibility)
        assertEquals(0, root.findViewById<LinearLayout>(R.id.account_rows).childCount)
    }
    @Test fun compactWidgetKeepsAccountFailureVisible() {
        val root = CodexQuotaWidgetProvider.buildViews(context, listOf(account().copy(error = "Erneut anmelden")), 200, now)
            .apply(context, FrameLayout(context))
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.account_status).visibility)
        assertTrue(root.findViewById<TextView>(R.id.account_status).text.contains("Erneut anmelden"))
    }
    @Test fun oneRowUsesTwoColumnsWithSeparateQuotaAndReset() {
        val root = CodexQuotaWidgetProvider.buildViews(context, listOf(account("one", 25), account("two", 19)), 56, now)
            .apply(context, FrameLayout(context))
        val rows = root.findViewById<LinearLayout>(R.id.account_rows)
        assertEquals(LinearLayout.HORIZONTAL, rows.orientation)
        assertEquals(2, rows.childCount)
        assertTrue(rows.getChildAt(0).findViewById<ImageView>(R.id.window_progress).contentDescription.contains("25 % verbraucht"))
        assertTrue(rows.getChildAt(1).findViewById<ImageView>(R.id.window_progress).contentDescription.contains("19 % verbraucht"))
        assertEquals("W 25→25%", rows.getChildAt(0).findViewById<TextView>(R.id.window_value).text.toString())
        assertEquals("2h", rows.getChildAt(0).findViewById<TextView>(R.id.window_reset).text.toString())
    }
    @Test fun oneRowPreservesBothWindowTypes() {
        val first = account().copy(quota = Quota("pro", WindowQuota(72, "1h", now + 3600000), account().quota!!.weekly))
        val root = CodexQuotaWidgetProvider.buildViews(context, listOf(first, account("two")), 56, now)
            .apply(context, FrameLayout(context))
        val windows = root.findViewById<LinearLayout>(R.id.window_rows)
        assertEquals(2, windows.childCount)
        assertEquals("5h 72→90%", windows.getChildAt(0).findViewById<TextView>(R.id.window_value).text.toString())
        assertEquals("W 25→25%", windows.getChildAt(1).findViewById<TextView>(R.id.window_value).text.toString())
    }
    @Test fun oneRowReapplyClearsRemovedAccountAndShowsLoginWhenEmpty() {
        val root = CodexQuotaWidgetProvider.buildViews(context, listOf(account(), account("two")), 56, now)
            .apply(context, FrameLayout(context))
        CodexQuotaWidgetProvider.buildViews(context, listOf(account()), 56, now).reapply(context, root)
        assertEquals(1, root.findViewById<LinearLayout>(R.id.account_rows).childCount)
        CodexQuotaWidgetProvider.buildViews(context, emptyList(), 56, now).reapply(context, root)
        assertEquals(0, root.findViewById<LinearLayout>(R.id.account_rows).childCount)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.empty_state).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.account_rows).visibility)
    }
    @Test fun oneRowKeepsErrorsAndExpiredSnapshotsExplicit() {
        val root = CodexQuotaWidgetProvider.buildViews(context,
            listOf(account().copy(error = "Erneut anmelden"), account("two")), 56, now + 7200001)
            .apply(context, FrameLayout(context))
        val rows = root.findViewById<LinearLayout>(R.id.account_rows)
        assertEquals("!", rows.getChildAt(0).findViewById<TextView>(R.id.compact_status).text.toString())
        assertEquals("alt", rows.getChildAt(1).findViewById<TextView>(R.id.compact_status).text.toString())
        assertEquals("fällig", rows.getChildAt(1).findViewById<TextView>(R.id.window_reset).text.toString())
    }

}
