package top.logge.codexquota

import java.util.Locale
import kotlin.math.roundToInt

object QuotaPresentation {
    const val PRIMARY_WINDOW_MS: Long = 5 * 60 * 60 * 1000L
    const val WEEKLY_WINDOW_MS: Long = 7 * 24 * 60 * 60 * 1000L

    data class WindowPresentation(
        val text: String,
        val used: Int,
        val expected: Int?,
        val estimate: Int?,
        val available: Boolean,
    )

    data class Presentation(
        val liveText: String,
        val primary: WindowPresentation,
        val weekly: WindowPresentation,
    )

    fun fromQuota(quota: Quota, fallbackLiveText: String): Presentation {
        val primaryPace = quota.primary?.expectedUsedPercent(PRIMARY_WINDOW_MS)
        val weeklyPace = quota.weekly?.expectedUsedPercent(WEEKLY_WINDOW_MS)
        val primaryEstimate = quota.primary?.estimatedFinalUsedPercent(PRIMARY_WINDOW_MS)
        val weeklyEstimate = quota.weekly?.estimatedFinalUsedPercent(WEEKLY_WINDOW_MS)
        return Presentation(
            liveText = quota.paceLabel(fallbackLiveText, primaryPace, weeklyPace),
            primary = quota.primary?.let {
                WindowPresentation(
                    text = "5h ${it.used.estimateLabel(primaryEstimate)} · ${it.reset.remainingLabel()}",
                    used = it.used,
                    expected = primaryPace,
                    estimate = primaryEstimate,
                    available = true,
                )
            } ?: WindowPresentation(
                text = "5h unavailable",
                used = 0,
                expected = null,
                estimate = null,
                available = false,
            ),
            weekly = quota.weekly?.let {
                WindowPresentation(
                    text = "W ${it.used.estimateLabel(weeklyEstimate)} · ${it.reset.remainingLabel()}",
                    used = it.used,
                    expected = weeklyPace,
                    estimate = weeklyEstimate,
                    available = true,
                )
            } ?: WindowPresentation(
                text = "W unavailable",
                used = 0,
                expected = null,
                estimate = null,
                available = false,
            ),
        )
    }

    fun planLabel(plan: String): String {
        val normalized = plan.lowercase(Locale.ROOT).replace("-", "_").replace(" ", "_")
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

    fun String.remainingLabel(): String {
        val trimmed = trim()
        return if (trimmed.isBlank() || trimmed == "?") "rem ?" else "rem $trimmed"
    }

    fun WindowQuota.expectedUsedPercent(windowMs: Long): Int? {
        val remainingMs = reset.parseDurationMs() ?: return null
        val elapsedMs = (windowMs - remainingMs).coerceIn(0L, windowMs)
        return ((elapsedMs.toDouble() / windowMs.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    }

    fun WindowQuota.estimatedFinalUsedPercent(windowMs: Long): Int? {
        val elapsedPercent = expectedUsedPercent(windowMs)?.takeIf { it > 0 } ?: return null
        return ((used.toDouble() / elapsedPercent.toDouble()) * 100.0).roundToInt().coerceAtLeast(0)
    }

    fun Int.estimateLabel(estimate: Int?): String =
        if (estimate == null) "$this%" else "$this→$estimate%"

    fun Quota.paceLabel(fallback: String, primaryExpected: Int?, weeklyExpected: Int?): String {
        val worstDelta = listOfNotNull(
            primary?.let { window -> primaryExpected?.let { window.used - it } },
            weekly?.let { window -> weeklyExpected?.let { window.used - it } },
        ).maxOrNull() ?: return fallback
        return when {
            worstDelta >= 15 -> "over pace"
            worstDelta >= 7 -> "watch pace"
            worstDelta <= -20 -> "ahead"
            else -> "on track"
        }
    }

    fun String.parseDurationMs(): Long? {
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
}
