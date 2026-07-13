package top.logge.codexquota

import android.content.Context
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CodexQuotaWidgetRemoteViewsTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val provider = CodexQuotaWidgetProvider()

    @Test
    fun weeklyOnlyHidesPrimaryRowAndCentersWeeklyRow() {
        val root = applyViews(
            Quota(
                plan = "pro",
                primary = null,
                weekly = WindowQuota(35, "3d 4h"),
            ),
        )

        val quotaRows = root.findViewById<View>(R.id.quota_rows)
        val primaryRow = root.findViewById<View>(R.id.primary_row)
        val weeklyRow = root.findViewById<View>(R.id.weekly_row)

        assertEquals(View.GONE, primaryRow.visibility)
        assertEquals(View.VISIBLE, weeklyRow.visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.weekly_bar).visibility)
        assertEquals("W 35→64% · rem 3d 4h", root.findViewById<TextView>(R.id.weekly_text).text.toString())

        measureAndLayout(root)
        val containerCenter = quotaRows.height / 2
        val weeklyCenter = (weeklyRow.top + weeklyRow.bottom) / 2
        assertTrue("weekly row should remain vertically centered", kotlin.math.abs(containerCenter - weeklyCenter) <= 1)
    }

    @Test
    fun bothWindowsRestorePrimaryRowOnRemoteViewsReapply() {
        val root = applyViews(
            Quota(
                plan = "pro",
                primary = null,
                weekly = WindowQuota(35, "3d 4h"),
            ),
        )
        assertEquals(View.GONE, root.findViewById<View>(R.id.primary_row).visibility)

        provider.buildQuotaViews(
            context = context,
            quota = Quota(
                plan = "plus",
                primary = WindowQuota(42, "2h 30m"),
                weekly = WindowQuota(61, "2d 0h"),
            ),
        ).reapply(context, root)

        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.primary_row).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.primary_bar).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.weekly_row).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.weekly_bar).visibility)
        assertEquals("5h 42→84% · rem 2h 30m", root.findViewById<TextView>(R.id.primary_text).text.toString())
        assertEquals("W 61→86% · rem 2d 0h", root.findViewById<TextView>(R.id.weekly_text).text.toString())
    }

    @Test
    fun noWindowsKeepClearUnavailableStateVisible() {
        val root = applyViews(
            Quota(
                plan = "codex",
                primary = null,
                weekly = null,
            ),
        )

        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.primary_row).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.weekly_row).visibility)
        assertEquals("5h unavailable", root.findViewById<TextView>(R.id.primary_text).text.toString())
        assertEquals("W unavailable", root.findViewById<TextView>(R.id.weekly_text).text.toString())
    }

    private fun applyViews(quota: Quota): View {
        val host = FrameLayout(context)
        return provider.buildQuotaViews(context, quota).apply(context, host).also(host::addView)
    }

    private fun measureAndLayout(root: View) {
        val width = MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY)
        val height = MeasureSpec.makeMeasureSpec(160, MeasureSpec.EXACTLY)
        root.measure(width, height)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }
}
