package top.logge.codexquota

import org.json.JSONArray
import org.json.JSONObject

internal object AccountCodec {
    fun encode(state: AccountState): String = JSONObject().put("schema", 1)
        .put("accounts", JSONArray().apply { state.accounts.forEach { a -> put(JSONObject()
            .put("id", a.id).put("name", a.name).put("auth", authJson(a.auth))
            .put("quota", a.quota?.let(QuotaCacheCodec::encode)).put("fetchedAt", a.fetchedAt)
            .put("error", a.error)) } })
        .put("pending", state.pendingLogin?.let { p -> JSONObject()
            .put("code", p.login.userCode).put("deviceId", p.login.deviceAuthId)
            .put("interval", p.login.intervalSeconds).put("expiresAt", p.expiresAt) }).toString()

    fun decode(raw: String): AccountState {
        val json = JSONObject(raw)
        check(json.getInt("schema") == 1) { "Unbekanntes Account-Format" }
        val array = json.getJSONArray("accounts")
        return AccountState((0 until array.length()).map { i ->
            val a = array.getJSONObject(i)
            Account(a.getString("id"), a.getString("name"), readAuth(a.getJSONObject("auth")),
                a.optJSONObject("quota")?.let(QuotaCacheCodec::decode), a.optLong("fetchedAt"), a.stringOrNull("error"))
        }, json.optJSONObject("pending")?.let { p -> PendingLogin(CodexAuth.DeviceLogin(
            "https://auth.openai.com/codex/device", p.getString("code"), p.getString("deviceId"),
            p.getLong("interval")), p.getLong("expiresAt")) })
    }

    private fun authJson(a: CodexAuth.AuthState): JSONObject = JSONObject()
        .put("access", a.accessToken).put("refresh", a.refreshToken).put("idToken", a.idToken)
        .put("accountId", a.accountId).put("plan", a.planType).put("fedramp", a.isFedramp)
        .put("email", a.email).put("subject", a.subject)

    private fun readAuth(a: JSONObject) = CodexAuth.AuthState(a.getString("access"), a.getString("refresh"),
        a.getString("idToken"), a.stringOrNull("accountId"), a.stringOrNull("plan"), a.optBoolean("fedramp"),
        a.stringOrNull("email"), a.stringOrNull("subject"))
}
