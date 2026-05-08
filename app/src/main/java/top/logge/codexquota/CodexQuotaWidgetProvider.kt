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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

class CodexQuotaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { renderLoading(context, manager, it) }
        fetchAndRender(context.applicationContext, animate = true, forceRefresh = false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) fetchAndRender(context.applicationContext, animate = true, forceRefresh = true)
    }

    private fun fetchAndRender(context: Context, animate: Boolean, forceRefresh: Boolean) = thread {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, CodexQuotaWidgetProvider::class.java))
        val cached = loadCachedQuota(context)

        // Widget updates are often fired while Android is restoring network/DNS.
        // Prefer the last good quota instead of flashing an empty error state.
        if (!forceRefresh && cached != null && !cached.isOlderThan(HARD_CACHE_MS)) {
            ids.forEach { id -> render(context, manager, id, Result.success(cached.quota), cachedAt = cached.savedAt, isStale = true) }
        }

        val result = runCatching { CodexAuth.fetchQuota(context) }
            .onSuccess { saveCachedQuota(context, it) }
            .recoverCatching { error ->
                val fallback = cached?.takeUnless { it.isOlderThan(MAX_STALE_MS) }
                if (fallback != null) fallback.quota else throw error
            }

        val cachedAt = if (result.isSuccess) loadCachedQuota(context)?.savedAt else cached?.savedAt
        val isStale = result.isSuccess && cachedAt != null && cachedAt < System.currentTimeMillis() - FRESH_WINDOW_MS

        if (animate && result.isSuccess && (!isStale || forceRefresh)) {
            animateBars(context, manager, ids, result.getOrThrow(), cachedAt = cachedAt, isStale = isStale)
        } else {
            ids.forEach { id -> render(context, manager, id, result, cachedAt = cachedAt, isStale = isStale) }
        }
    }

    private fun animateBars(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        quota: Quota,
        cachedAt: Long? = null,
        isStale: Boolean = false,
    ) {
        val primaryTarget = quota.primary.used.coerceIn(0, 100)
        val weeklyTarget = quota.weekly.used.coerceIn(0, 100)
        val steps = listOf(0.18, 0.42, 0.68, 0.86, 1.0)
        steps.forEachIndexed { index, fraction ->
            val animated = quota.copy(
                primary = quota.primary.copy(used = max(1, (primaryTarget * fraction).roundToInt()).coerceAtMost(primaryTarget)),
                weekly = quota.weekly.copy(used = max(1, (weeklyTarget * fraction).roundToInt()).coerceAtMost(weeklyTarget)),
            )
            ids.forEach { id -> render(context, manager, id, Result.success(animated), isAnimating = index < steps.lastIndex, cachedAt = cachedAt, isStale = isStale) }
            if (index < steps.lastIndex) Thread.sleep(130)
        }
    }

    private fun renderLoading(context: Context, manager: AppWidgetManager, id: Int) {
        val cached = loadCachedQuota(context)
        if (cached != null && !cached.isOlderThan(MAX_STALE_MS)) {
            render(context, manager, id, Result.success(cached.quota), cachedAt = cached.savedAt, isStale = true)
            return
        }

        val views = baseViews(context)
        views.setTextViewText(R.id.plan, "SYNC")
        views.setTextViewText(R.id.live_text, "fetching")
        views.setTextViewText(R.id.primary_text, "5h updating…")
        views.setTextViewText(R.id.weekly_text, "W updating…")
        views.setProgressBar(R.id.primary_bar, 100, 8, false)
        views.setProgressBar(R.id.weekly_bar, 100, 8, false)
        views.setTextViewText(R.id.footer, "sync\nnow")
        manager.updateAppWidget(id, views)
    }

    private fun render(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        result: Result<Quota>,
        isAnimating: Boolean = false,
        cachedAt: Long? = null,
        isStale: Boolean = false,
    ) {
        val views = baseViews(context)
        result.onSuccess { quota ->
            val liveLabel = when {
                isAnimating -> "animating"
                isStale -> "cached quota"
                else -> "live quota"
            }
            val footer = if (isStale && cachedAt != null) "cache ${formatTime(cachedAt)}\ntap" else "upd ${formatTime()}\ntap"
            views.setTextViewText(R.id.plan, quota.plan.widgetPlanLabel())
            views.setTextViewText(R.id.live_text, liveLabel)
            views.setTextViewText(R.id.primary_text, "5h ${quota.primary.used}% · ${quota.primary.reset.remainingLabel()}")
            views.setProgressBar(R.id.primary_bar, 100, quota.primary.used, false)
            views.setTextViewText(R.id.weekly_text, "W ${quota.weekly.used}% · ${quota.weekly.reset.remainingLabel()}")
            views.setProgressBar(R.id.weekly_bar, 100, quota.weekly.used, false)
            views.setTextViewText(R.id.footer, footer)
        }.onFailure { error ->
            views.setTextViewText(R.id.plan, "ERR")
            views.setTextViewText(R.id.live_text, "offline")
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

    private fun String.remainingLabel(): String {
        val trimmed = trim()
        return if (trimmed.isBlank() || trimmed == "?") "rem ?" else "rem $trimmed"
    }

    private fun String.widgetPlanLabel(): String {
        val normalized = lowercase(Locale.ROOT).replace("-", "_").replace(" ", "_")
        return when {
            normalized.isBlank() -> "CODEX"
            normalized.contains("pro_lit") || normalized.contains("prolit") -> "PRO"
            normalized.contains("pro") -> "PRO"
            normalized.contains("plus") -> "PLUS"
            normalized.contains("team") -> "TEAM"
            normalized.contains("business") -> "BIZ"
            normalized.contains("enterprise") -> "ENT"
            normalized.contains("edu") -> "EDU"
            normalized.contains("free") -> "FREE"
            else -> "CODEX"
        }
    }

    private fun formatTime(timestamp: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(timestamp))

    private fun loadCachedQuota(context: Context): CachedQuota? {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString("quota_json", null) ?: return null
        val savedAt = prefs.getLong("saved_at", 0L).takeIf { it > 0L } ?: return null
        return runCatching { CachedQuota(quotaFromJson(JSONObject(json)), savedAt) }.getOrNull()
    }

    private fun saveCachedQuota(context: Context, quota: Quota) {
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
            .putString("quota_json", quota.toJson().toString())
            .putLong("saved_at", System.currentTimeMillis())
            .apply()
    }

    private fun Quota.toJson(): JSONObject = JSONObject()
        .put("plan", plan)
        .put("primary", primary.toJson())
        .put("weekly", weekly.toJson())
        .put("creditsBalance", creditsBalance)

    private fun WindowQuota.toJson(): JSONObject = JSONObject()
        .put("used", used)
        .put("reset", reset)

    private fun quotaFromJson(json: JSONObject): Quota = Quota(
        plan = json.optString("plan", "codex"),
        primary = json.optJSONObject("primary")?.windowQuotaFromJson() ?: WindowQuota(0, "?"),
        weekly = json.optJSONObject("weekly")?.windowQuotaFromJson() ?: WindowQuota(0, "?"),
        creditsBalance = json.optString("creditsBalance").ifBlank { null },
    )

    private fun JSONObject.windowQuotaFromJson(): WindowQuota = WindowQuota(
        used = optInt("used", 0).coerceIn(0, 100),
        reset = optString("reset", "?"),
    )

    private fun CachedQuota.isOlderThan(maxAgeMs: Long): Boolean = System.currentTimeMillis() - savedAt > maxAgeMs

    private data class CachedQuota(val quota: Quota, val savedAt: Long)

    companion object {
        const val ACTION_REFRESH = "top.logge.codexquota.REFRESH"
        private const val CACHE_PREFS = "codex_quota_cache"
        private const val FRESH_WINDOW_MS = 5 * 60 * 1000L
        private const val HARD_CACHE_MS = 30 * 60 * 1000L
        private const val MAX_STALE_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

data class WindowQuota(val used: Int, val reset: String)
data class Quota(
    val plan: String,
    val primary: WindowQuota,
    val weekly: WindowQuota,
    val creditsBalance: String? = null,
)
