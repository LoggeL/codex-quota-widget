package top.logge.codexquota

import org.json.JSONObject
import java.util.Base64

/** Stateless OAuth client. AccountStore owns all persisted credentials. */
object CodexAuth {
    private const val ISSUER = "https://auth.openai.com"
    private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

    data class DeviceLogin(
        val verificationUrl: String,
        val userCode: String,
        val deviceAuthId: String,
        val intervalSeconds: Long,
    )
    data class AuthState(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val accountId: String?,
        val planType: String?,
        val isFedramp: Boolean,
        val email: String? = null,
        val subject: String? = null,
    ) {
        // An account can have multiple members. Never merge distinct subjects in one workspace.
        val identity: String get() = listOf(accountId.orEmpty(), subject.orEmpty()).joinToString(":")
    }

    fun startDeviceLogin(): DeviceLogin {
        val response = CodexHttp.request("$ISSUER/api/accounts/deviceauth/usercode",
            JSONObject().put("client_id", CLIENT_ID).toString()).json()
        return DeviceLogin(
            "$ISSUER/codex/device",
            response.optString("user_code", response.optString("usercode")).also { check(it.isNotBlank()) },
            response.optString("device_auth_id", response.optString("deviceAuthId")).also { check(it.isNotBlank()) },
            response.optString("interval", "5").toLongOrNull()?.coerceIn(1, 60) ?: 5,
        )
    }

    /** One polling step. The coordinator owns cancellation, lifetime and scheduling. */
    fun poll(login: DeviceLogin): AuthState? {
        val response = CodexHttp.request("$ISSUER/api/accounts/deviceauth/token", JSONObject()
            .put("device_auth_id", login.deviceAuthId).put("user_code", login.userCode).toString())
        if (response.status == 403 || response.status == 404) return null
        val code = response.json()
        val tokens = CodexHttp.request("$ISSUER/oauth/token", CodexHttp.form(
            "grant_type" to "authorization_code",
            "code" to code.getString("authorization_code"),
            "redirect_uri" to "$ISSUER/deviceauth/callback",
            "client_id" to CLIENT_ID,
            "code_verifier" to code.getString("code_verifier"),
        ), "application/x-www-form-urlencoded").json()
        return fromTokens(tokens)
    }

    fun refresh(auth: AuthState): AuthState = fromTokens(CodexHttp.request("$ISSUER/oauth/token",
        CodexHttp.form("grant_type" to "refresh_token", "client_id" to CLIENT_ID,
            "refresh_token" to auth.refreshToken), "application/x-www-form-urlencoded").json(), auth)

    fun fetchQuota(auth: AuthState): Quota {
        val headers = mutableMapOf("Authorization" to "Bearer ${auth.accessToken}")
        auth.accountId?.let { headers["ChatGPT-Account-Id"] = it }
        if (auth.isFedramp) headers["X-OpenAI-Fedramp"] = "true"
        return UsageParser.parseUsage(CodexHttp.request("https://chatgpt.com/backend-api/wham/usage",
            headers = headers).json(), auth.planType ?: "codex").also {
            check(it.primary != null || it.weekly != null) { "Keine Quota-Fenster in der Antwort" }
        }
    }

    internal fun fromTokens(json: JSONObject, fallback: AuthState? = null): AuthState {
        val idToken = json.optString("id_token").ifBlank { fallback?.idToken.orEmpty() }
        val claims = claims(idToken) ?: claims(json.optString("access_token"))
        val auth = claims?.optJSONObject("https://api.openai.com/auth")
        return AuthState(
            accessToken = json.getString("access_token").also { check(it.isNotBlank()) },
            refreshToken = json.optString("refresh_token").ifBlank { fallback?.refreshToken.orEmpty() }
                .also { check(it.isNotBlank()) },
            idToken = idToken,
            accountId = auth?.stringOrNull("chatgpt_account_id") ?: fallback?.accountId,
            planType = auth?.stringOrNull("chatgpt_plan_type") ?: fallback?.planType,
            isFedramp = auth?.optBoolean("chatgpt_account_is_fedramp", fallback?.isFedramp ?: false)
                ?: fallback?.isFedramp ?: false,
            email = claims?.stringOrNull("email") ?: fallback?.email,
            subject = claims?.stringOrNull("sub") ?: fallback?.subject,
        ).also { check(it.identity != ":") { "Account-Identität fehlt" } }
    }

    internal fun claims(jwt: String): JSONObject? = runCatching {
        JSONObject(String(Base64.getUrlDecoder().decode(jwt.split('.')[1]), Charsets.UTF_8))
    }.getOrNull()
}

internal fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
