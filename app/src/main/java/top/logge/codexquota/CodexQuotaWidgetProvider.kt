package top.logge.codexquota

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class CodexQuotaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { renderLoading(context, manager, it) }
        fetchAndRender(context.applicationContext)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) fetchAndRender(context.applicationContext)
    }

    private fun fetchAndRender(context: Context) = thread {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, CodexQuotaWidgetProvider::class.java))
        val result = runCatching { QuotaClient.fetch() }
        ids.forEach { id -> render(context, manager, id, result) }
    }

    private fun renderLoading(context: Context, manager: AppWidgetManager, id: Int) {
        val views = baseViews(context)
        views.setTextViewText(R.id.primary_text, "5h updating…")
        views.setTextViewText(R.id.weekly_text, "W updating…")
        views.setTextViewText(R.id.footer, "fetching")
        manager.updateAppWidget(id, views)
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int, result: Result<Quota>) {
        val views = baseViews(context)
        result.onSuccess { quota ->
            views.setTextViewText(R.id.plan, quota.plan.uppercase(Locale.ROOT).ifBlank { "CODEX" })
            views.setTextViewText(R.id.primary_text, "5h ${quota.primary.used}% · ${quota.primary.reset}")
            views.setProgressBar(R.id.primary_bar, 100, quota.primary.used, false)
            views.setTextViewText(R.id.weekly_text, "W ${quota.weekly.used}% · ${quota.weekly.reset}")
            views.setProgressBar(R.id.weekly_bar, 100, quota.weekly.used, false)
            views.setTextViewText(R.id.footer, "upd ${SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())}\ntap")
        }.onFailure { error ->
            views.setTextViewText(R.id.plan, "ERR")
            views.setTextViewText(R.id.primary_text, "Quota unavailable")
            views.setProgressBar(R.id.primary_bar, 100, 0, false)
            views.setTextViewText(R.id.weekly_text, error.message?.take(24) ?: "Check endpoint")
            views.setProgressBar(R.id.weekly_bar, 100, 0, false)
            views.setTextViewText(R.id.footer, "tap\nretry")
        }
        manager.updateAppWidget(id, views)
    }

    private fun baseViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.codex_quota_widget)
        val intent = Intent(context, CodexQuotaWidgetProvider::class.java).setAction(ACTION_REFRESH)
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
        views.setOnClickPendingIntent(R.id.widget_root, pending)
        return views
    }

    companion object { const val ACTION_REFRESH = "top.logge.codexquota.REFRESH" }
}

data class WindowQuota(val used: Int, val reset: String)
data class Quota(val plan: String, val primary: WindowQuota, val weekly: WindowQuota)

object QuotaClient {
    private const val ENDPOINT = "https://codex-quota.logge.top/status.json"

    fun fetch(): Quota {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        val json = JSONObject(body)
        return Quota(
            plan = json.optString("planType", "codex"),
            primary = json.window("primary"),
            weekly = json.window("secondary"),
        )
    }

    private fun JSONObject.window(name: String): WindowQuota {
        val o = optJSONObject(name) ?: JSONObject()
        return WindowQuota(
            used = o.optDouble("usedPercent", 0.0).roundToInt().coerceIn(0, 100),
            reset = o.optString("resetsIn", "?")
        )
    }
}
