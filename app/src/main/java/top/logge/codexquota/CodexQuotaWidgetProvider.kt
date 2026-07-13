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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
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
        val networkReady = hasValidatedNetwork(context)
        CodexQuotaLog.append(
            context,
            "fetch start ids=${ids.size} force=$forceRefresh cached=${cached != null} cachedAge=${cached?.ageLabel() ?: "-"} network=$networkReady",
        )

        if (!networkReady && cached != null && !cached.isOlderThan(MAX_STALE_MS)) {
            CodexQuotaLog.append(context, "network not validated; using stale cache age=${cached.ageLabel()}")
            ids.forEach { id -> render(context, manager, id, Result.success(cached.quota), cachedAt = cached.savedAt, isStale = true) }
            return@thread
        }

        // Widget updates are often fired while Android is restoring network/DNS.
        // Prefer the last good quota instead of flashing an empty error state.
        if (!forceRefresh && cached != null && !cached.isOlderThan(HARD_CACHE_MS)) {
            CodexQuotaLog.append(context, "render hard-cache first age=${cached.ageLabel()}")
            ids.forEach { id -> render(context, manager, id, Result.success(cached.quota), cachedAt = cached.savedAt, isStale = true) }
        }

        val result = runCatching { CodexAuth.fetchQuota(context) }
            .onSuccess {
                saveCachedQuota(context, it)
                CodexQuotaLog.append(context, "fetch success plan=${it.plan} primary=${it.primary?.used?.let { used -> "$used%" } ?: "unavailable"} weekly=${it.weekly?.used?.let { used -> "$used%" } ?: "unavailable"}")
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

    private fun hasValidatedNetwork(context: Context): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun animateBars(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        quota: Quota,
        cachedAt: Long? = null,
        isStale: Boolean = false,
    ) {
        val primaryTarget = quota.primary?.used?.coerceIn(0, 100) ?: 0
        val weeklyTarget = quota.weekly?.used?.coerceIn(0, 100) ?: 0
        val steps = listOf(0.18, 0.42, 0.68, 0.86, 1.0)
        steps.forEachIndexed { index, fraction ->
            val animated = quota.copy(
                primary = quota.primary?.copy(used = animatedValue(primaryTarget, fraction)),
                weekly = quota.weekly?.copy(used = animatedValue(weeklyTarget, fraction)),
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
        val views = result.fold(
            onSuccess = { quota ->
                val liveLabel = when {
                    isAnimating -> "animating"
                    isStale -> "cached quota"
                    else -> "live quota"
                }
                val footer = if (isStale && cachedAt != null) "cache ${formatTime(cachedAt)}\ntap" else "upd ${formatTime()}\ntap"
                buildQuotaViews(context, quota, liveLabel, footer)
            },
            onFailure = { error ->
                baseViews(context).apply {
                    setTextViewText(R.id.plan, "ERR")
                    setTextViewText(R.id.live_text, "offline")
                    setTextViewText(R.id.primary_text, "Quota unavailable")
                    setImageViewBitmap(R.id.primary_bar, usageBarBitmap(0, null, BarPalette.Primary))
                    setTextViewText(R.id.weekly_text, error.message?.take(24) ?: "Check endpoint")
                    setImageViewBitmap(R.id.weekly_bar, usageBarBitmap(0, null, BarPalette.Weekly))
                    setTextViewText(R.id.footer, "tap\nretry")
                }
            },
        )
        manager.updateAppWidget(id, views)
    }

    internal fun buildQuotaViews(
        context: Context,
        quota: Quota,
        liveLabel: String = "live quota",
        footer: String = "tap\nrefresh",
    ): RemoteViews {
        val presentation = QuotaPresentation.fromQuota(quota, liveLabel)
        val weeklyOnly = !presentation.primary.available && presentation.weekly.available
        return baseViews(context).apply {
            setTextViewText(R.id.plan, QuotaPresentation.planLabel(quota.plan))
            setTextViewText(R.id.live_text, presentation.liveText)
            setViewVisibility(R.id.primary_row, if (weeklyOnly) View.GONE else View.VISIBLE)
            setTextViewText(R.id.primary_text, presentation.primary.text)
            setViewVisibility(R.id.primary_bar, if (presentation.primary.available) View.VISIBLE else View.INVISIBLE)
            setImageViewBitmap(R.id.primary_bar, usageBarBitmap(presentation.primary.used, presentation.primary.estimate, BarPalette.Primary))
            setViewVisibility(R.id.weekly_row, View.VISIBLE)
            setTextViewText(R.id.weekly_text, presentation.weekly.text)
            setViewVisibility(R.id.weekly_bar, if (presentation.weekly.available) View.VISIBLE else View.INVISIBLE)
            setImageViewBitmap(R.id.weekly_bar, usageBarBitmap(presentation.weekly.used, presentation.weekly.estimate, BarPalette.Weekly))
            setTextViewText(R.id.footer, footer)
        }
    }

    private fun baseViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.codex_quota_widget)
        val intent = Intent(context, CodexQuotaWidgetProvider::class.java).setAction(ACTION_REFRESH)
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
        views.setOnClickPendingIntent(R.id.widget_root, pending)
        views.setViewVisibility(R.id.primary_row, View.VISIBLE)
        views.setViewVisibility(R.id.primary_bar, View.VISIBLE)
        views.setViewVisibility(R.id.weekly_row, View.VISIBLE)
        views.setViewVisibility(R.id.weekly_bar, View.VISIBLE)
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

    private fun animatedValue(target: Int, fraction: Double): Int =
        if (target <= 0) 0 else kotlin.math.max(1, (target * fraction).roundToInt()).coerceAtMost(target)

    private fun formatTime(timestamp: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(timestamp))

    private fun loadCachedQuota(context: Context): CachedQuota? {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString("quota_json", null) ?: return null
        val savedAt = prefs.getLong("saved_at", 0L).takeIf { it > 0L } ?: return null
        return runCatching { CachedQuota(QuotaCacheCodec.decode(JSONObject(json)), savedAt) }.getOrNull()
    }

    private fun saveCachedQuota(context: Context, quota: Quota) {
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
            .putString("quota_json", QuotaCacheCodec.encode(quota).toString())
            .putLong("saved_at", System.currentTimeMillis())
            .apply()
    }

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
    }
}
