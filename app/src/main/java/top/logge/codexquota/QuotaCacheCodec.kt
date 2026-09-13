package top.logge.codexquota

import org.json.JSONObject

internal object QuotaCacheCodec {
    private const val SCHEMA = 3

    fun encode(quota: Quota): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("plan", quota.plan)
        .apply { quota.primary?.let { put("primary", it.toJson()) } }
        .apply { quota.weekly?.let { put("weekly", it.toJson()) } }
        .put("creditsBalance", quota.creditsBalance)

    fun decode(json: JSONObject): Quota {
        val schema = json.optInt("schema", 1)
        val cachedPrimary = json.optJSONObject("primary")?.windowQuotaFromJson()
        return Quota(
            plan = json.optString("plan", "codex"),
            primary = cachedPrimary?.takeUnless {
                schema < 2 && it.used == 0 && it.reset == "?"
            },
            weekly = json.optJSONObject("weekly")?.windowQuotaFromJson(),
            creditsBalance = json.stringOrNull("creditsBalance"),
        )
    }

    private fun WindowQuota.toJson(): JSONObject = JSONObject()
        .put("used", used)
        .put("reset", reset)
        .put("resetsAtMs", resetsAtMs).put("durationMinutes", durationMinutes)

    private fun JSONObject.windowQuotaFromJson(): WindowQuota = WindowQuota(
        used = optInt("used", 0).coerceIn(0, 100),
        reset = optString("reset", "?"),
        resetsAtMs = optLong("resetsAtMs").takeIf { it > 0 },
        durationMinutes = optLong("durationMinutes").takeIf { it > 0 },
    )
}
