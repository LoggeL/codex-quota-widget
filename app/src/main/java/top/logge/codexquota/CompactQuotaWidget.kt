package top.logge.codexquota

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/** A single launcher row: two account columns, with no separate title or plan row. */
internal object CompactQuotaWidget {
    private data class WindowSlot(val row: Int, val value: Int, val reset: Int, val bar: Int)
    private data class AccountSlot(val root: Int, val name: Int, val status: Int, val error: Int,
        val primary: WindowSlot, val weekly: WindowSlot)
    private val slots = listOf(
        AccountSlot(R.id.first_account, R.id.first_name, R.id.first_status, R.id.first_error,
            WindowSlot(R.id.first_primary_row, R.id.first_primary_value, R.id.first_primary_reset, R.id.first_primary_bar),
            WindowSlot(R.id.first_weekly_row, R.id.first_weekly_value, R.id.first_weekly_reset, R.id.first_weekly_bar)),
        AccountSlot(R.id.second_account, R.id.second_name, R.id.second_status, R.id.second_error,
            WindowSlot(R.id.second_primary_row, R.id.second_primary_value, R.id.second_primary_reset, R.id.second_primary_bar),
            WindowSlot(R.id.second_weekly_row, R.id.second_weekly_value, R.id.second_weekly_reset, R.id.second_weekly_bar)),
    )

    fun build(context: Context, accounts: List<Account>, now: Long, storageError: Boolean, width: Int): RemoteViews {
        // Fixed slots avoid nested add/remove actions and repeated IDs during launcher reuse.
        val views = RemoteViews(context.packageName, R.layout.codex_quota_widget_fixed)
        views.setViewVisibility(R.id.account_rows, if (accounts.isEmpty()) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.empty_state, if (accounts.isEmpty()) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.refresh, if (accounts.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.empty_state, if (storageError) "Codex Quota · Speicher nicht lesbar. App öffnen."
            else "Codex Quota · Accounts verbinden")
        slots.forEachIndexed { index, slot ->
            // Reset every optional field on every update, including accounts and windows removed.
            views.setViewVisibility(slot.root, View.GONE)
            views.setViewVisibility(slot.primary.row, View.GONE)
            views.setViewVisibility(slot.weekly.row, View.GONE)
            views.setViewVisibility(slot.error, View.GONE)
            val account = accounts.getOrNull(index) ?: return@forEachIndexed
            val card = QuotaPresentation.card(account, now)
            views.setViewVisibility(slot.root, View.VISIBLE)
            views.setTextViewText(slot.name, card.name)
            val narrow = width < 300 && accounts.size > 1
            views.setTextViewTextSize(slot.name, TypedValue.COMPLEX_UNIT_SP, if (narrow) 10f else 11f)
            views.setTextViewTextSize(slot.status, TypedValue.COMPLEX_UNIT_SP, if (narrow) 8f else 9f)
            val old = card.stale || card.windows.any { it.expired }
            val tightest = card.tightestWindow
            val delta = tightest?.let {
                val label = if (card.windows.size > 1) if (it.label == "Woche") "W " else "5h " else ""
                label + it.pace!!.shortDelta
            } ?: "?"
            views.setTextViewText(slot.status, when { account.error != null -> "!"; old -> "alt"; else -> delta })
            views.setTextColor(slot.status, if (old) Color.rgb(240, 191, 118) else QuotaUsageBar.color(tightest?.pace))
            views.setContentDescription(slot.status, "${card.status}. ${tightest?.let { "${it.label}: ${it.paceText}" }.orEmpty()}")
            views.setContentDescription(slot.root, "${card.name}, ${card.plan}. ${card.status}")
            views.setTextViewText(slot.error, card.status)
            views.setViewVisibility(slot.error, if (card.windows.isEmpty()) View.VISIBLE else View.GONE)
            card.windows.forEach { window ->
                val target = if (window.label == "Woche") slot.weekly else slot.primary
                val label = if (window.label == "Woche") "W" else "5h"
                val valueText = "$label ${window.usageForecast}"
                val resetText = shortReset(window)
                views.setViewVisibility(target.row, View.VISIBLE)
                views.setTextViewText(target.value, valueText)
                views.setTextViewText(target.reset, resetText)
                fitWindowText(context, views, target, width, minOf(accounts.size, 2), valueText, resetText)
                views.setImageViewBitmap(target.bar, QuotaUsageBar.bitmap(window))
                views.setContentDescription(target.bar, "${QuotaUsageBar.description(card.name, window)}. ${card.status}")
            }
        }
        val more = (accounts.size - 2).coerceAtLeast(0)
        views.setViewVisibility(R.id.widget_more, if (more > 0) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_more, if (more > 0) "+$more" else "")
        views.setContentDescription(R.id.widget_more, if (more > 0) "$more weitere Accounts in der App" else "")
        return views
    }

    /** Keep both complete values readable, including large forecasts and enlarged system text. */
    private fun fitWindowText(context: Context, views: RemoteViews, slot: WindowSlot, width: Int, columns: Int, value: String, reset: String) {
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
        views.setTextViewTextSize(slot.value, TypedValue.COMPLEX_UNIT_SP, valueSize)
        views.setTextViewTextSize(slot.reset, TypedValue.COMPLEX_UNIT_SP, resetSize)
    }

    fun shortReset(window: QuotaPresentation.Window): String = when {
        window.expired -> "fällig"
        window.resetText == "Reset unbekannt" -> "?"
        else -> window.resetText.removePrefix("Reset in ").replace(" ", "")
            .replace(Regex("^(\\d+d)0h$"), "$1").replace(Regex("^(\\d+h)0m$"), "$1")
    }
}
