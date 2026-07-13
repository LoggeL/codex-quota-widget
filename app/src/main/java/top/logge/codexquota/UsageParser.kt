package top.logge.codexquota

import org.json.JSONObject

object UsageParser {
    fun parseUsage(json: JSONObject, planType: String): Quota {
        val rateLimit = json.firstObject(
            "rate_limit",
            "rateLimit",
            "rate_limit_status",
            "rateLimitStatus",
            "rateLimits",
            "rate_limits",
            "usage",
        )

        val candidates = buildList {
            rateLimit?.collectWindowCandidates(this)
            json.collectWindowCandidates(this)
        }.distinctBy { it.window }
        val classified = classifyWindows(candidates)

        val credits = json.optJSONObject("credits")
        return Quota(
            plan = json.firstNonBlank("plan_type", "planType") ?: planType,
            primary = classified.primary,
            weekly = classified.weekly,
            creditsBalance = credits?.optString("balance")?.ifBlank { null },
        )
    }

    private data class Candidate(
        val key: String,
        val json: JSONObject,
        val window: WindowQuota,
    )

    private data class ClassifiedWindows(
        val primary: WindowQuota?,
        val weekly: WindowQuota?,
    )

    private enum class WindowKind { Primary, Weekly }

    private fun JSONObject.collectWindowCandidates(out: MutableList<Candidate>) {
        for (key in keys()) {
            val child = optJSONObject(key) ?: continue
            child.windowOrNull()?.let { out += Candidate(key, child, it) }
        }
    }

    private fun classifyWindows(candidates: List<Candidate>): ClassifiedWindows {
        val primary = candidates.firstOrNull { it.classify() == WindowKind.Primary }?.window
        val weekly = candidates.firstOrNull { it.classify() == WindowKind.Weekly }?.window
        return ClassifiedWindows(primary = primary, weekly = weekly)
    }

    private fun Candidate.classify(): WindowKind? {
        val semantic = json.semanticWindowKind()
        if (semantic != null) return semantic

        val normalizedKey = key.lowercase().replace("_", "")
        return when {
            "weekly" in normalizedKey || "week" in normalizedKey -> WindowKind.Weekly
            "secondary" in normalizedKey -> WindowKind.Weekly
            "primary" in normalizedKey -> WindowKind.Primary
            else -> null
        }
    }

    private fun JSONObject.semanticWindowKind(): WindowKind? {
        windowDurationMinutes()?.let { minutes ->
            return if (minutes >= WEEKLY_MINUTES / 2L) WindowKind.Weekly else WindowKind.Primary
        }

        remainingDurationMinutes()?.let { minutes ->
            // A 5h window can never have days remaining. If a field named `primary`
            // carries a multi-day reset, it is the weekly Codex window in current API
            // responses (for example codex app-server account/rateLimits/read).
            if (minutes > PRIMARY_MAX_MINUTES) return WindowKind.Weekly
        }

        return null
    }

    private fun JSONObject.firstObject(vararg keys: String): JSONObject? {
        for (key in keys) {
            val value = optJSONObject(key)
            if (value != null) return value
        }
        return null
    }

    private fun JSONObject.windowOrNull(): WindowQuota? {
        val used = usedPercentOrNull() ?: return null
        return WindowQuota(
            used = used,
            reset = remainingTimeLabel(),
        )
    }

    private fun JSONObject.usedPercentOrNull(): Int? {
        val raw = when {
            has("used_percent") -> opt("used_percent")
            has("usedPercent") -> opt("usedPercent")
            else -> return null
        }
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        } ?: return null
        if (!value.isFinite()) return null
        return value.toInt().coerceIn(0, 100)
    }

    private fun JSONObject.remainingTimeLabel(): String {
        firstNonBlank(
            "resets_in",
            "resetsIn",
            "reset_in",
            "resetIn",
            "remaining_time",
            "remainingTime",
            "time_remaining",
            "timeRemaining",
        )?.let { return it }

        firstPositiveLong("reset_after_seconds", "resetAfterSeconds", "reset_after", "resetAfter")
            ?.let { return fmtDurationMins((it / 60L).coerceAtLeast(0)) }

        firstPositiveLong("resets_at", "resetsAt", "reset_at", "resetAt")
            ?.let { return fmtResetsAt(it) }

        return "?"
    }

    private fun JSONObject.windowDurationMinutes(): Long? = firstPositiveLong(
        "window_duration_mins",
        "windowDurationMins",
        "window_duration_minutes",
        "windowDurationMinutes",
        "duration_mins",
        "durationMins",
    ) ?: firstPositiveLong(
        "window_duration_seconds",
        "windowDurationSeconds",
        "duration_seconds",
        "durationSeconds",
    )?.let { it / 60L }

    private fun JSONObject.remainingDurationMinutes(): Long? {
        firstNonBlank(
            "resets_in",
            "resetsIn",
            "reset_in",
            "resetIn",
            "remaining_time",
            "remainingTime",
            "time_remaining",
            "timeRemaining",
        )?.parseDurationMinutes()?.let { return it }

        firstPositiveLong("reset_after_seconds", "resetAfterSeconds", "reset_after", "resetAfter")
            ?.let { return it / 60L }

        firstPositiveLong("resets_at", "resetsAt", "reset_at", "resetAt")
            ?.let { timestamp ->
                val millis = if (timestamp > 10_000_000_000L) timestamp else timestamp * 1000L
                return ((millis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
            }

        return null
    }

    private fun JSONObject.firstNonBlank(vararg keys: String): String? {
        for (key in keys) {
            val value = optString(key, "").trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return null
    }

    private fun JSONObject.firstPositiveLong(vararg keys: String): Long? {
        for (key in keys) {
            if (!has(key)) continue
            val value = optLong(key, 0L)
            if (value > 0L) return value
        }
        return null
    }

    private fun fmtResetsAt(timestamp: Long): String {
        if (timestamp <= 0) return "?"
        val millis = if (timestamp > 10_000_000_000L) timestamp else timestamp * 1000L
        val mins = ((millis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        return fmtDurationMins(mins)
    }

    private fun fmtDurationMins(mins: Long): String {
        val hours = mins / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${mins % 60}m"
            else -> "${mins}m"
        }
    }

    private fun String.parseDurationMinutes(): Long? {
        val normalized = trim().lowercase()
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
        if (totalMinutes > 0L) return totalMinutes

        val colonParts = normalized.split(":")
        if (colonParts.size == 2) {
            val hours = colonParts[0].toLongOrNull()
            val minutes = colonParts[1].toLongOrNull()
            if (hours != null && minutes != null) return hours * 60L + minutes
        }

        return normalized.toLongOrNull()
    }

    private const val PRIMARY_MAX_MINUTES = 8L * 60L
    private const val WEEKLY_MINUTES = 7L * 24L * 60L
}
