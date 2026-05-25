package top.logge.codexquota

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
        CodexQuotaLog.append(context, "widget onUpdate ids=${ids.size}")
        ids.forEach { renderLoading(context, manager, it) }
        fetchAndRender(context.applicationContext, animate = true, forceRefresh = false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        CodexQuotaLog.append(context, "widget onReceive action=${intent.action ?: "<none>"}")
        if (intent.action == ACTION_REFRESH) fetchAndRender(context.applicationContext, animate = true, forceRefresh = true)
    }

    private fun fetchAndRender(context: Context, animate: Boolean, forceRefresh: Boolean) = thread {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, CodexQuotaWidgetProvider::class.java))
        val cached = loadCachedQuota(context)
        CodexQuotaLog.append(
            context,
            "fetch start ids=${ids.size} force=$forceRefresh cached=${cached != null} cachedAge=${cached?.ageLabel() ?: "-"}",
        )

        // Widget updates are often fired while Android is restoring network/DNS.
        // Prefer the last good quota instead of flashing an empty error state.
        if (!forceRefresh && cached != null && !cached.isOlderThan(HARD_CACHE_MS)) {
            CodexQuotaLog.append(context, "render hard-cache first age=${cached.ageLabel()}")
            ids.forEach { id -> render(context, manager, id, Result.success(cached.quota), cachedAt = cached.savedAt, isStale = true) }
        }

        val result = runCatching { CodexAuth.fetchQuota(context) }
            .onSuccess {
                saveCachedQuota(context, it)
                CodexQuotaLog.append(context, "fetch success plan=${it.plan} primary=${it.primary.used}% weekly=${it.weekly.used}%")
            }
            .recoverCatching { error ->
                val fallback = cached?.takeUnless { it.isOlderThan(MAX_STALE_MS) }
                if (fallback != null) {
                    CodexQuotaLog.append(context, "fetch failed; using stale cache age=${fallback.ageLabel()} error=${error.shortLog()}")
                    fallback.quota
                } else {
                    CodexQuotaLog.append(context, "fetch failed; no usable cache error=${error.shortLog()}")
                    throw error
                }
            }

        val cachedAt = if (result.isSuccess) loadCachedQuota(context)?.savedAt else cached?.savedAt
        val isStale = result.isSuccess && cachedAt != null && cachedAt < System.currentTimeMillis() - FRESH_WINDOW_MS

        if (animate && result.isSuccess && (!isStale || forceRefresh)) {
            animateBars(context, manager, ids, result.getOrThrow(), cachedAt = cachedAt, isStale = isStale)
        } else {
            ids.forEach { id -> render(context, manager, id, result, cachedAt = cachedAt, isStale = isStale) }
        }
        CodexQuotaLog.append(context, "render done ids=${ids.size} success=${result.isSuccess} stale=$isStale")
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
        views.setImageViewBitmap(R.id.primary_bar, usageBarBitmap(8, null, BarPalette.Primary))
        views.setImageViewBitmap(R.id.weekly_bar, usageBarBitmap(8, null, BarPalette.Weekly))
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
            val primaryPace = quota.primary.expectedUsedPercent(PRIMARY_WINDOW_MS)
            val weeklyPace = quota.weekly.expectedUsedPercent(WEEKLY_WINDOW_MS)
            val primaryEstimate = quota.primary.estimatedFinalUsedPercent(PRIMARY_WINDOW_MS)
            val weeklyEstimate = quota.weekly.estimatedFinalUsedPercent(WEEKLY_WINDOW_MS)
            views.setTextViewText(R.id.plan, quota.plan.widgetPlanLabel())
            views.setTextViewText(R.id.live_text, quota.paceLabel(liveLabel, primaryPace, weeklyPace))
            views.setTextViewText(R.id.primary_text, "5h ${quota.primary.used.estimateLabel(primaryEstimate)} · ${quota.primary.reset.remainingLabel()}")
            views.setImageViewBitmap(R.id.primary_bar, usageBarBitmap(quota.primary.used, primaryEstimate, BarPalette.Primary))
            views.setTextViewText(R.id.weekly_text, "W ${quota.weekly.used.estimateLabel(weeklyEstimate)} · ${quota.weekly.reset.remainingLabel()}")
            views.setImageViewBitmap(R.id.weekly_bar, usageBarBitmap(quota.weekly.used, weeklyEstimate, BarPalette.Weekly))
            views.setTextViewText(R.id.footer, footer)
        }.onFailure { error ->
            views.setTextViewText(R.id.plan, "ERR")
            views.setTextViewText(R.id.live_text, "offline")
            views.setTextViewText(R.id.primary_text, "Quota unavailable")
            views.setImageViewBitmap(R.id.primary_bar, usageBarBitmap(0, null, BarPalette.Primary))
            views.setTextViewText(R.id.weekly_text, error.message?.take(24) ?: "Check endpoint")
            views.setImageViewBitmap(R.id.weekly_bar, usageBarBitmap(0, null, BarPalette.Weekly))
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

    private fun usageBarBitmap(used: Int, estimate: Int?, palette: BarPalette): Bitmap {
        val width = 360
        val height = 24
        val radius = height / 2f
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.rgb(39, 50, 65)
        canvas.drawRoundRect(0f, 4f, width.toFloat(), 20f, radius, radius, paint)

        val usedWidth = (width * used.coerceIn(0, 100) / 100f).coerceAtLeast(if (used > 0) 6f else 0f)
        if (usedWidth > 0f) {
            paint.shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                0f,
                intArrayOf(palette.start, palette.center, palette.end),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(0f, 4f, usedWidth, 20f, radius, radius, paint)
            paint.shader = null
        }

        estimate?.let {
            val x = (width * it.coerceIn(0, 100) / 100f).coerceIn(3f, width - 3f)
            paint.color = Color.WHITE
            canvas.drawRoundRect(x - 2f, 1f, x + 2f, 23f, 2f, 2f, paint)
            paint.color = Color.argb(120, 11, 13, 16)
            canvas.drawRoundRect(x - 1f, 3f, x + 1f, 21f, 1f, 1f, paint)
        }

        return bitmap
    }

    private enum class BarPalette(val start: Int, val center: Int, val end: Int) {
        Primary(Color.rgb(110, 231, 249), Color.rgb(116, 167, 255), Color.rgb(167, 139, 250)),
        Weekly(Color.rgb(52, 211, 153), Color.rgb(250, 204, 21), Color.rgb(251, 113, 133)),
    }

    private fun String.remainingLabel(): String {
        val trimmed = trim()
        return if (trimmed.isBlank() || trimmed == "?") "rem ?" else "rem $trimmed"
    }

    private fun WindowQuota.expectedUsedPercent(windowMs: Long): Int? {
        val remainingMs = reset.parseDurationMs() ?: return null
        val elapsedMs = (windowMs - remainingMs).coerceIn(0L, windowMs)
        return ((elapsedMs.toDouble() / windowMs.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    }

    private fun WindowQuota.estimatedFinalUsedPercent(windowMs: Long): Int? {
        val elapsedPercent = expectedUsedPercent(windowMs)?.takeIf { it > 0 } ?: return null
        return ((used.toDouble() / elapsedPercent.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    }

    private fun Int.estimateLabel(estimate: Int?): String =
        if (estimate == null) "$this%" else "$this→$estimate%"

    private fun Quota.paceLabel(fallback: String, primaryExpected: Int?, weeklyExpected: Int?): String {
        if (primaryExpected == null && weeklyExpected == null) return fallback
        val primaryDelta = primaryExpected?.let { primary.used - it } ?: Int.MIN_VALUE
        val weeklyDelta = weeklyExpected?.let { weekly.used - it } ?: Int.MIN_VALUE
        val worstDelta = max(primaryDelta, weeklyDelta)
        return when {
            worstDelta >= 15 -> "over pace"
            worstDelta >= 7 -> "watch pace"
            worstDelta <= -20 -> "ahead"
            else -> "on track"
        }
    }

    private fun String.parseDurationMs(): Long? {
        val normalized = trim().lowercase(Locale.ROOT)
        if (normalized.isBlank() || normalized == "?") return null

        var totalMinutes = 0L
        Regex("""(\d+)\s*([dhm])""").findAll(normalized).forEach { match ->
            val value = match.groupValues[1].toLongOrNull() ?: return@forEach
            totalMinutes += when (match.groupValues[2]) {
                "d" -> value * 24L * 60L
                "h" -> value * 60L
                else -> value
            }
        }
        if (totalMinutes > 0L) return totalMinutes * 60_000L

        val colonParts = normalized.split(":")
        if (colonParts.size == 2) {
            val hours = colonParts[0].toLongOrNull()
            val minutes = colonParts[1].toLongOrNull()
            if (hours != null && minutes != null) return (hours * 60L + minutes) * 60_000L
        }

        return normalized.toLongOrNull()?.let { it.coerceAtLeast(0L) * 60_000L }
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
    private fun CachedQuota.ageLabel(): String = "${((System.currentTimeMillis() - savedAt) / 60_000L).coerceAtLeast(0)}m"
    private fun Throwable.shortLog(): String = "${javaClass.simpleName}: ${message ?: "no message"}".take(160)

    private data class CachedQuota(val quota: Quota, val savedAt: Long)

    companion object {
        const val ACTION_REFRESH = "top.logge.codexquota.REFRESH"
        private const val CACHE_PREFS = "codex_quota_cache"
        private const val FRESH_WINDOW_MS = 5 * 60 * 1000L
        private const val HARD_CACHE_MS = 30 * 60 * 1000L
        private const val MAX_STALE_MS = 7 * 24 * 60 * 60 * 1000L
        private const val PRIMARY_WINDOW_MS = 5 * 60 * 60 * 1000L
        private const val WEEKLY_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

data class WindowQuota(val used: Int, val reset: String)
data class Quota(
    val plan: String,
    val primary: WindowQuota,
    val weekly: WindowQuota,
    val creditsBalance: String? = null,
)
