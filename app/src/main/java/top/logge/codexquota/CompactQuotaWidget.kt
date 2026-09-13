package top.logge.codexquota

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/** A single launcher row: two account columns, with no separate title or plan row. */
internal object CompactQuotaWidget {
    fun build(context: Context, accounts: List<Account>, now: Long, storageError: Boolean, width: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.codex_quota_widget_compact)
        views.removeAllViews(R.id.account_rows)
        views.setViewVisibility(R.id.account_rows, if (accounts.isEmpty()) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.empty_state, if (accounts.isEmpty()) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.refresh, if (accounts.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.empty_state, if (storageError) "Codex Quota · Speicher nicht lesbar. App öffnen."
            else "Codex Quota · Accounts verbinden")
        accounts.take(2).forEach { account ->
            val card = QuotaPresentation.card(account, now)
            val row = RemoteViews(context.packageName, R.layout.widget_account_compact)
            row.setTextViewText(R.id.account_name, card.name)
            if (width < 300 && accounts.size > 1) {
                row.setTextViewTextSize(R.id.account_name, TypedValue.COMPLEX_UNIT_SP, 10f)
                row.setTextViewTextSize(R.id.compact_status, TypedValue.COMPLEX_UNIT_SP, 8f)
            }
            val old = card.stale || card.windows.any { it.expired }
            row.setViewVisibility(R.id.compact_status, View.VISIBLE)
            val tightest = card.tightestWindow
            val delta = tightest?.let {
                val label = if (card.windows.size > 1) if (it.label == "Woche") "W " else "5h " else ""
                label + it.pace!!.shortDelta
            } ?: "?"
            row.setTextViewText(R.id.compact_status, when { account.error != null -> "!"; old -> "alt"; else -> delta })
            row.setTextColor(R.id.compact_status, if (old) Color.rgb(240, 191, 118) else QuotaUsageBar.color(tightest?.pace))
            row.setContentDescription(R.id.compact_status, "${card.status}. ${tightest?.let { "${it.label}: ${it.paceText}" }.orEmpty()}")
            row.setContentDescription(R.id.compact_account, "${card.name}, ${card.plan}. ${card.status}")
            row.setTextViewText(R.id.compact_error, card.status)
            row.setViewVisibility(R.id.compact_error, if (card.windows.isEmpty()) View.VISIBLE else View.GONE)
            row.removeAllViews(R.id.window_rows)
            card.windows.forEach { window ->
                val quota = RemoteViews(context.packageName, R.layout.widget_window_compact)
                val label = if (window.label == "Woche") "W" else "5h"
                val valueText = "$label ${window.usageForecast}"
                val resetText = shortReset(window)
                quota.setTextViewText(R.id.window_value, valueText)
                quota.setTextViewText(R.id.window_reset, resetText)
                fitWindowText(context, quota, width, minOf(accounts.size, 2), valueText, resetText)
                quota.setImageViewBitmap(R.id.window_progress, QuotaUsageBar.bitmap(window))
                quota.setContentDescription(R.id.window_progress, "${QuotaUsageBar.description(card.name, window)}. ${card.status}")
                row.addView(R.id.window_rows, quota)
            }
            views.addView(R.id.account_rows, row)
        }
        views.setViewVisibility(R.id.widget_more, if (accounts.size > 2) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_more, "+${accounts.size - 2}")
        views.setContentDescription(R.id.widget_more, "${accounts.size - 2} weitere Accounts in der App")
        return views
    }

    /** Keep both complete values readable, including large forecasts and enlarged system text. */
    private fun fitWindowText(context: Context, views: RemoteViews, width: Int, columns: Int, value: String, reset: String) {
        val metrics = context.resources.displayMetrics
        // Root inset + refresh is 40dp; each column has 10dp padding and a 3dp label gap.
        val available = ((width - 40f) / columns - 15f) * metrics.density
        var valueSize = if (width < 300 && columns > 1) 9f else 11f
        var resetSize = if (width < 300 && columns > 1) 8f else 9f
        val paint = Paint()
        fun measured(text: String, size: Float): Float {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)
            return paint.measureText(text)
        }
        while (valueSize > 7f && measured(value, valueSize) + measured(reset, resetSize) > available) {
            valueSize -= .5f
            resetSize = (resetSize - .5f).coerceAtLeast(7f)
        }
        views.setTextViewTextSize(R.id.window_value, TypedValue.COMPLEX_UNIT_SP, valueSize)
        views.setTextViewTextSize(R.id.window_reset, TypedValue.COMPLEX_UNIT_SP, resetSize)
    }

    fun shortReset(window: QuotaPresentation.Window): String = when {
        window.expired -> "fällig"
        window.resetText == "Reset unbekannt" -> "?"
        else -> window.resetText.removePrefix("Reset in ").replace(" ", "")
            .replace(Regex("^(\\d+d)0h$"), "$1").replace(Regex("^(\\d+h)0m$"), "$1")
    }
}
