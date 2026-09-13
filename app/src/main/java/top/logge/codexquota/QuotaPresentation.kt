package top.logge.codexquota

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** Shared presentation for the app and RemoteViews. No aggregation across accounts. */
object QuotaPresentation {
    const val STALE_AFTER_MS = 60 * 60 * 1000L
    const val MAX_CACHE_MS = 7 * 24 * 60 * 60 * 1000L
    data class Window(
        val label: String, val remaining: Int, val text: String, val resetText: String, val expired: Boolean,
        val used: Int, val pace: QuotaPace.Result?,
    ) {
        val usageForecast: String get() = "$used→${QuotaPace.compactEstimate(pace?.estimatedFinalUsed)}%"
        val shortPaceText: String get() = pace?.let { "${it.shortDelta} · ${it.status}" } ?: "Prognose offen"
        val forecastText: String get() = if (pace?.estimatedFinalUsed != null)
            "Bis zum Reset: voraussichtlich ${QuotaPace.fullEstimate(pace.estimatedFinalUsed)} verbraucht"
            else if (expired) "Prognose abgelaufen: bitte aktualisieren"
            else "Prognose offen: Reset oder verstrichene Zeit fehlt"
        val paceText: String get() = pace?.let {
            "${it.differenceText} · Soll ${it.expectedUsed.roundToLong()} %"
        } ?: "Sollvergleich nicht verfügbar"
    }
    data class Card(val name: String, val plan: String, val status: String, val windows: List<Window>, val stale: Boolean) {
        val tightestWindow: Window? get() = windows.filter { it.pace != null }.maxByOrNull { it.pace!!.delta }
    }

    fun card(account: Account, now: Long = System.currentTimeMillis()): Card {
        val age = (now - account.fetchedAt).coerceAtLeast(0)
        val stale = account.error != null || age >= STALE_AFTER_MS
        val quota = account.quota?.takeIf { account.fetchedAt > 0 && age <= MAX_CACHE_MS }
        val windows = listOfNotNull(
            quota?.primary?.let { window("5 Std.", it, now, account.fetchedAt) },
            quota?.weekly?.let { window("Woche", it, now, account.fetchedAt) },
        )
        val stamp = if (age < 24 * 60 * 60 * 1000L) "HH:mm" else "dd.MM. HH:mm"
        val updated = SimpleDateFormat(stamp, Locale.GERMANY).format(Date(account.fetchedAt))
        val status = when {
            quota == null -> account.error ?: if (account.quota != null) "Daten veraltet · bitte aktualisieren" else "Noch keine Quota geladen"
            account.error != null -> "${account.error} · Stand $updated"
            stale -> "Gespeichert · $updated"
            windows.any { it.expired } -> "Reset fällig · bitte aktualisieren"
            else -> "Aktualisiert $updated"
        }
        return Card(account.name, planLabel(quota?.plan ?: account.auth.planType.orEmpty()), status, windows, stale)
    }

    fun window(label: String, quota: WindowQuota, now: Long, sampledAt: Long = now): Window {
        val expired = quota.resetsAtMs?.let { now >= it } ?: false
        val remaining = 100 - quota.used.coerceIn(0, 100)
        val reset = quota.resetsAtMs?.let { at ->
            if (expired) "Reset fällig" else "Reset in ${duration(at - now)}"
        } ?: "Reset unbekannt"
        return Window(label, remaining, if (expired) "zuletzt $remaining % frei" else "$remaining % frei", reset, expired,
            quota.used.coerceIn(0, 100), QuotaPace.calculate(quota, sampledAt, now, if (label == "Woche") 10080 else 300))
    }

    fun planLabel(plan: String): String {
        val normalized = plan.lowercase(Locale.ROOT)
        return when {
            "pro" in normalized -> "PRO"
            "plus" in normalized -> "PLUS"
            "business" in normalized || "team" in normalized -> "BUSINESS"
            "enterprise" in normalized -> "ENTERPRISE"
            "edu" in normalized -> "EDU"
            "free" in normalized -> "FREE"
            else -> "CODEX"
        }
    }

    fun duration(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0) + 59_999) / 60_000
        val hours = minutes / 60
        return when {
            hours >= 24 -> "${hours / 24}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            else -> "${minutes}m"
        }
    }
}

internal fun WindowQuota.anchoredAt(fetchedAt: Long): WindowQuota {
    if (resetsAtMs != null || fetchedAt <= 0) return this
    val parts = Regex("(\\d+)\\s*([dhm])").findAll(reset.lowercase(Locale.ROOT)).toList()
    val minutes = if (parts.isEmpty()) reset.toLongOrNull() else parts.sumOf {
        it.groupValues[1].toLong() * when (it.groupValues[2]) { "d" -> 1440L; "h" -> 60L; else -> 1L }
    }
    return copy(resetsAtMs = minutes?.let { fetchedAt + it * 60_000 })
}
