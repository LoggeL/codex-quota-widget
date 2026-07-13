package top.logge.codexquota

import org.json.JSONObject

internal object QuotaCacheCodec {
    private const val SCHEMA = 2

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
                schema < SCHEMA && it.used == 0 && it.reset == "?"
            },
            weekly = json.optJSONObject("weekly")?.windowQuotaFromJson(),
            creditsBalance = json.optString("creditsBalance").ifBlank { null },
        )
    }

    private fun WindowQuota.toJson(): JSONObject = JSONObject()
        .put("used", used)
        .put("reset", reset)

    private fun JSONObject.windowQuotaFromJson(): WindowQuota = WindowQuota(
        used = optInt("used", 0).coerceIn(0, 100),
        reset = optString("reset", "?"),
    )
}
