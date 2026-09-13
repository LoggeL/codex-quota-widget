package top.logge.codexquota

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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
        assertEquals(75, rows.getChildAt(0).findViewById<ProgressBar>(R.id.window_progress).progress)
        assertEquals(81, rows.getChildAt(1).findViewById<ProgressBar>(R.id.window_progress).progress)
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
        val root = CodexQuotaWidgetProvider.buildViews(context, listOf(account().copy(error = "Erneut anmelden")), 140, now)
            .apply(context, FrameLayout(context))
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.account_status).visibility)
        assertTrue(root.findViewById<TextView>(R.id.account_status).text.contains("Erneut anmelden"))
    }
}
