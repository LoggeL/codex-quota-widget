package top.logge.codexquota

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/** A single launcher row: two account columns, with no separate title or plan row. */
internal object CompactQuotaWidget {
    fun build(context: Context, accounts: List<Account>, now: Long, storageError: Boolean): RemoteViews {
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
            val old = card.stale || card.windows.any { it.expired }
            row.setViewVisibility(R.id.compact_status, View.VISIBLE)
            row.setTextViewText(R.id.compact_status, when { account.error != null -> "!"; old -> "alt"; else -> "frei" })
            row.setTextColor(R.id.compact_status, if (old) Color.rgb(240, 191, 118) else Color.rgb(157, 174, 189))
            row.setContentDescription(R.id.compact_status, card.status)
            row.setContentDescription(R.id.compact_account, "${card.name}, ${card.plan}. ${card.status}")
            row.setTextViewText(R.id.compact_error, card.status)
            row.setViewVisibility(R.id.compact_error, if (card.windows.isEmpty()) View.VISIBLE else View.GONE)
            row.removeAllViews(R.id.window_rows)
            card.windows.forEach { window ->
                val quota = RemoteViews(context.packageName, R.layout.widget_window_compact)
                val label = if (window.label == "Woche") "W" else "5h"
                quota.setTextViewText(R.id.window_value, "$label ${window.remaining}%")
                quota.setTextViewText(R.id.window_reset, shortReset(window))
                if (card.windows.size == 1) {
                    quota.setTextViewTextSize(R.id.window_value, TypedValue.COMPLEX_UNIT_SP, 12f)
                    quota.setTextViewTextSize(R.id.window_reset, TypedValue.COMPLEX_UNIT_SP, 10f)
                }
                quota.setProgressBar(R.id.window_progress, 100, window.remaining, false)
                quota.setContentDescription(R.id.window_progress,
                    "${card.name}: ${window.label}, ${window.text}, ${window.resetText}. ${card.status}")
                row.addView(R.id.window_rows, quota)
            }
            views.addView(R.id.account_rows, row)
        }
        views.setViewVisibility(R.id.widget_more, if (accounts.size > 2) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_more, "+${accounts.size - 2}")
        views.setContentDescription(R.id.widget_more, "${accounts.size - 2} weitere Accounts in der App")
        return views
    }

    private fun shortReset(window: QuotaPresentation.Window): String = when {
        window.expired -> "fällig"
        window.resetText == "Reset unbekannt" -> "?"
        else -> window.resetText.removePrefix("Reset in ").replace(" ", "")
            .replace(Regex("^(\\d+d)0h$"), "$1").replace(Regex("^(\\d+h)0m$"), "$1")
    }
}
