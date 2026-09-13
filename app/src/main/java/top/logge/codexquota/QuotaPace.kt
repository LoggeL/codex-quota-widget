package top.logge.codexquota

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Linear extrapolation from window start, evaluated at the quota snapshot's timestamp. */
object QuotaPace {
    data class Result(val expectedUsed: Double, val delta: Double, val estimatedFinalUsed: Double?) {
        val deltaPoints: Long get() = delta.roundToLong()
        val status: String get() = when {
            delta >= 15 -> "Zu hohes Tempo"
            delta >= 7 -> "Tempo beachten"
            delta <= -20 -> "Vorsprung"
            else -> "Im Plan"
        }
        val differenceText: String get() = when {
            deltaPoints > 0 -> "$deltaPoints Prozentpunkte Defizit"
            deltaPoints < 0 -> "${abs(deltaPoints)} Prozentpunkte Vorsprung"
            else -> "Im Soll (0 Prozentpunkte)"
        }
        val shortDelta: String get() = when {
            deltaPoints > 0 -> "+${deltaPoints}pp"
            deltaPoints < 0 -> "−${abs(deltaPoints)}pp"
            else -> "±0pp"
        }
    }

    fun calculate(quota: WindowQuota, sampledAt: Long, now: Long, fallbackMinutes: Long): Result? {
        val resetAt = quota.resetsAtMs ?: return null
        val durationMinutes = quota.durationMinutes ?: fallbackMinutes
        if (durationMinutes <= 0 || sampledAt <= 0 || now >= resetAt) return null
        val durationMs = durationMinutes.toDouble() * 60_000.0
        val elapsed = sampledAt.toDouble() - (resetAt.toDouble() - durationMs)
        if (elapsed < 0 || elapsed >= durationMs) return null
        val expected = (elapsed / durationMs * 100.0).coerceIn(0.0, 100.0)
        val used = quota.used.coerceIn(0, 100)
        return Result(expected, used - expected,
            if (elapsed > 0) (used * durationMs / elapsed).takeIf { it.isFinite() } else null)
    }

    fun compactEstimate(value: Double?): String = when {
        value == null -> "?"
        value < 999.5 -> value.roundToLong().toString()
        value < 999_500 -> scaled(value, 1000.0, "k")
        value < 999_500_000 -> scaled(value, 1_000_000.0, "M")
        else -> scaled(value, 1_000_000_000.0, "G")
    }
    fun fullEstimate(value: Double?): String = value?.let {
        String.format(Locale.GERMANY, "%,d %%", it.roundToLong())
    } ?: "offen"
    private fun scaled(value: Double, divisor: Double, unit: String): String =
        String.format(Locale.ROOT, "%.1f", value / divisor).removeSuffix(".0") + unit
}
