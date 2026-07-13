package top.logge.codexquota

import org.json.JSONObject

object UsageParser {
    fun parseUsage(json: JSONObject, planType: String): Quota {
        val rateLimit = json.firstObject(
            "rate_limit",
            "rateLimit",
            "rate_limit_status",
            "rateLimitStatus",
            "usage",
        ) ?: JSONObject()

        val primary = rateLimit.firstWindow(
            "primary_window",
            "primaryWindow",
            "primary",
        )

        val weekly = rateLimit.firstWindow(
            "secondary_window",
            "secondaryWindow",
            "secondary",
        )

        val credits = json.optJSONObject("credits")
        return Quota(
            plan = json.optString("plan_type", planType),
            primary = primary,
            weekly = weekly,
            creditsBalance = credits?.optString("balance")?.ifBlank { null },
        )
    }

    private fun JSONObject.firstObject(vararg keys: String): JSONObject? {
        for (key in keys) {
            val value = optJSONObject(key)
            if (value != null) return value
        }
        return null
    }

    private fun JSONObject.firstWindow(vararg keys: String): WindowQuota? {
        for (key in keys) {
            optJSONObject(key)?.windowOrNull()?.let { return it }
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
}
