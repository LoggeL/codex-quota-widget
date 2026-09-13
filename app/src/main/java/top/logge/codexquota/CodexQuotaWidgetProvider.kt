package top.logge.codexquota

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/** Broadcast handlers only render cached state and enqueue Android-managed work. */
class CodexQuotaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAll(context)
        QuotaRefreshJob.schedule(context, immediately = true)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        renderAll(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            renderAll(context)
            QuotaRefreshJob.schedule(context, immediately = true)
        }
    }

    companion object {
        const val ACTION_REFRESH = "top.logge.codexquota.REFRESH"
        fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CodexQuotaWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val state = runCatching { QuotaRuntime.get(context).store.read() }
            ids.forEach { id ->
                val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
                manager.updateAppWidget(id, buildViews(context, state.getOrNull()?.accounts.orEmpty(), height,
                    storageError = state.isFailure))
            }
        }

        internal fun buildViews(context: Context, accounts: List<Account>, height: Int = 160,
            now: Long = System.currentTimeMillis(), storageError: Boolean = false): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.codex_quota_widget)
            val openIntent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val open = PendingIntent.getActivity(context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val refresh = PendingIntent.getBroadcast(context, 1, Intent(context, CodexQuotaWidgetProvider::class.java)
                .setAction(ACTION_REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.refresh, refresh)
            views.setTextViewText(R.id.widget_count, "${accounts.size} ${if (accounts.size == 1) "Account" else "Accounts"}")
            views.removeAllViews(R.id.account_rows)
            val compact = height < 240
            accounts.take(2).forEach { account ->
                val card = QuotaPresentation.card(account, now)
                val row = RemoteViews(context.packageName, R.layout.widget_account)
                row.setTextViewText(R.id.account_name, card.name)
                row.setTextViewText(R.id.account_plan, card.plan)
                row.setTextViewText(R.id.account_status, card.status)
                row.setViewVisibility(R.id.account_status, if (compact && !card.stale && card.windows.isNotEmpty()) View.GONE else View.VISIBLE)
                row.removeAllViews(R.id.window_rows)
                if (card.windows.size == 2) {
                    val pair = RemoteViews(context.packageName, R.layout.widget_window_pair)
                    card.windows.forEachIndexed { index, window ->
                        val textId = if (index == 0) R.id.pair_primary_text else R.id.pair_weekly_text
                        val barId = if (index == 0) R.id.pair_primary_bar else R.id.pair_weekly_bar
                        val resetId = if (index == 0) R.id.pair_primary_reset else R.id.pair_weekly_reset
                        pair.setTextViewText(textId, "${window.label} · ${window.text}")
                        pair.setTextViewText(resetId, window.resetText)
                        pair.setProgressBar(barId, 100, window.remaining, false)
                        pair.setContentDescription(barId, "${card.name}: ${window.label}, ${window.text}, ${window.resetText}")
                    }
                    row.addView(R.id.window_rows, pair)
                } else card.windows.forEach { window ->
                    val quota = RemoteViews(context.packageName, R.layout.widget_window)
                    quota.setTextViewText(R.id.window_label, window.label)
                    quota.setTextViewText(R.id.window_value, window.text)
                    quota.setTextViewText(R.id.window_reset, window.resetText)
                    quota.setProgressBar(R.id.window_progress, 100, window.remaining, false)
                    quota.setContentDescription(R.id.window_progress, "${card.name}: ${window.label}, ${window.text}, ${window.resetText}")
                    row.addView(R.id.window_rows, quota)
                }
                views.addView(R.id.account_rows, row)
            }
            views.setViewVisibility(R.id.empty_state, if (accounts.isEmpty()) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.empty_state, if (storageError) "Account-Speicher nicht lesbar. App öffnen."
                else "Accounts in der App verbinden")
            views.setViewVisibility(R.id.widget_more, if (accounts.size > 2) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_more, "+ ${accounts.size - 2} weitere in der App")
            return views
        }
    }
}
