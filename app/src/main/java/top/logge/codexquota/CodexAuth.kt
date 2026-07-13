package top.logge.codexquota

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLEncoder
import java.net.URL
import kotlin.math.max

object CodexAuth {
    private const val PREFS = "codex_auth"
    private const val ISSUER = "https://auth.openai.com"
    private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val CHATGPT_BASE = "https://chatgpt.com/backend-api"

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
    )

    fun isLoggedIn(context: Context): Boolean = loadAuth(context) != null

    fun logout(context: Context) {
        CodexQuotaLog.append(context, "logout")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun startDeviceLogin(): DeviceLogin {
        val response = httpJson(
            url = "$ISSUER/api/accounts/deviceauth/usercode",
            method = "POST",
            body = JSONObject().put("client_id", CLIENT_ID).toString(),
        )
        val userCode = response.optString("user_code", response.optString("usercode"))
        val deviceAuthId = response.optString("device_auth_id", response.optString("deviceAuthId"))
        if (userCode.isBlank() || deviceAuthId.isBlank()) {
            error("Device code response missing fields")
        }
        return DeviceLogin(
            verificationUrl = "$ISSUER/codex/device",
            userCode = userCode,
            deviceAuthId = deviceAuthId,
            intervalSeconds = response.optString("interval", "5").toLongOrNull()?.let { max(1, it) } ?: 5,
        )
    }

    fun completeDeviceLogin(context: Context, login: DeviceLogin, timeoutMs: Long = 15 * 60 * 1000L): AuthState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Exception? = null

        while (System.currentTimeMillis() < deadline) {
            try {
                val code = pollDeviceCode(login)
                val auth = exchangeCode(code)
                saveAuth(context, auth)
                return auth
            } catch (e: PendingAuthException) {
                Thread.sleep(login.intervalSeconds * 1000L)
            } catch (e: Exception) {
                lastError = e
                break
            }
        }
        throw lastError ?: IllegalStateException("Login timed out")
    }

    fun loadAuth(context: Context): AuthState? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val access = prefs.getString("access_token", null) ?: return null
        val refresh = prefs.getString("refresh_token", null) ?: return null
        val id = prefs.getString("id_token", "") ?: ""
        return AuthState(
            accessToken = access,
            refreshToken = refresh,
            idToken = id,
            accountId = prefs.getString("account_id", null),
            planType = prefs.getString("plan_type", null),
            isFedramp = prefs.getBoolean("is_fedramp", false),
        )
    }

    fun refreshAuth(context: Context, auth: AuthState): AuthState {
        CodexQuotaLog.append(context, "auth refresh start account=${auth.accountId != null}")
        val body = formEncode(
            "grant_type" to "refresh_token",
            "client_id" to CLIENT_ID,
            "refresh_token" to auth.refreshToken,
        )
        val json = httpJson(
            url = "$ISSUER/oauth/token",
            method = "POST",
            body = body,
            contentType = "application/x-www-form-urlencoded",
        )
        val refreshed = authFromTokenResponse(json, auth.refreshToken)
        saveAuth(context, refreshed)
        CodexQuotaLog.append(context, "auth refresh success account=${refreshed.accountId != null} plan=${refreshed.planType ?: "-"}")
        return refreshed
    }

    fun fetchQuota(context: Context): Quota {
        val initial = loadAuth(context) ?: run {
            CodexQuotaLog.append(context, "quota fetch blocked: login required")
            error("Login required")
        }
        return try {
            CodexQuotaLog.append(context, "quota fetch start account=${initial.accountId != null} plan=${initial.planType ?: "-"}")
            fetchQuotaWithAuth(context, initial)
        } catch (e: UnauthorizedException) {
            CodexQuotaLog.append(context, "quota fetch unauthorized; refreshing token")
            fetchQuotaWithAuth(context, refreshAuth(context, initial))
        }
    }

    private fun pollDeviceCode(login: DeviceLogin): JSONObject {
        val response = rawHttp(
            url = "$ISSUER/api/accounts/deviceauth/token",
            method = "POST",
            body = JSONObject()
                .put("device_auth_id", login.deviceAuthId)
                .put("user_code", login.userCode)
                .toString(),
            contentType = "application/json",
        )
        if (response.status == 403 || response.status == 404) throw PendingAuthException()
        if (response.status !in 200..299) error("Device auth HTTP ${response.status}: ${response.body.take(160)}")
        return JSONObject(response.body)
    }

    private fun exchangeCode(code: JSONObject): AuthState {
        val body = formEncode(
            "grant_type" to "authorization_code",
            "code" to code.getString("authorization_code"),
            "redirect_uri" to "$ISSUER/deviceauth/callback",
            "client_id" to CLIENT_ID,
            "code_verifier" to code.getString("code_verifier"),
        )
        val json = httpJson(
            url = "$ISSUER/oauth/token",
            method = "POST",
            body = body,
            contentType = "application/x-www-form-urlencoded",
        )
        return authFromTokenResponse(json, json.optString("refresh_token", ""))
    }

    private fun authFromTokenResponse(json: JSONObject, fallbackRefreshToken: String): AuthState {
        val idToken = json.optString("id_token", "")
        val claims = decodeJwtPayload(idToken)
        val authClaims = claims?.optJSONObject("https://api.openai.com/auth")
        return AuthState(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token", fallbackRefreshToken),
            idToken = idToken,
            accountId = authClaims?.optString("chatgpt_account_id")?.ifBlank { null },
            planType = authClaims?.optString("chatgpt_plan_type")?.ifBlank { null },
            isFedramp = authClaims?.optBoolean("chatgpt_account_is_fedramp", false) ?: false,
        )
    }

    private fun saveAuth(context: Context, auth: AuthState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("access_token", auth.accessToken)
            .putString("refresh_token", auth.refreshToken)
            .putString("id_token", auth.idToken)
            .putString("account_id", auth.accountId)
            .putString("plan_type", auth.planType)
            .putBoolean("is_fedramp", auth.isFedramp)
            .apply()
    }

    private fun fetchQuotaWithAuth(context: Context, auth: AuthState): Quota {
        val headers = mutableMapOf("Authorization" to "Bearer ${auth.accessToken}")
        auth.accountId?.let { headers["ChatGPT-Account-Id"] = it }
        if (auth.isFedramp) headers["X-OpenAI-Fedramp"] = "true"
        val response = rawHttp(
            url = "$CHATGPT_BASE/wham/usage",
            method = "GET",
            headers = headers,
        )
        if (response.status == 401) throw UnauthorizedException()
        if (response.status !in 200..299) error("Usage HTTP ${response.status}: ${response.body.take(160)}")
        CodexQuotaLog.append(context, "usage HTTP ${response.status}")
        val quota = parseUsage(JSONObject(response.body), auth.planType ?: "codex")
        check(quota.primary != null || quota.weekly != null) { "Usage response missing quota windows" }
        return quota
    }

    internal fun parseUsage(json: JSONObject, planType: String): Quota = UsageParser.parseUsage(json, planType)

    private fun decodeJwtPayload(jwt: String): JSONObject? = runCatching {
        val payload = jwt.split('.')[1]
        val bytes = android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        JSONObject(String(bytes, Charsets.UTF_8))
    }.getOrNull()

    private fun httpJson(url: String, method: String, body: String? = null, contentType: String = "application/json"): JSONObject {
        val response = rawHttp(url, method, body, contentType)
        if (response.status !in 200..299) error("HTTP ${response.status}: ${response.body.take(120)}")
        return JSONObject(response.body)
    }

    private fun rawHttp(
        url: String,
        method: String,
        body: String? = null,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val targetUrl = URL(url)
        return rawHttpWithRetry(targetUrl, method, body, contentType, headers)
    }

    private fun rawHttpWithRetry(
        targetUrl: URL,
        method: String,
        body: String?,
        contentType: String,
        headers: Map<String, String>,
    ): HttpResponse {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                return rawHttpOnce(targetUrl, method, body, contentType, headers)
            } catch (error: UnknownHostException) {
                lastError = error
                warmDns(targetUrl.host)
                Thread.sleep(350L * (attempt + 1))
            } catch (error: SocketTimeoutException) {
                lastError = error
                Thread.sleep(250L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("HTTP retry failed")
    }

    private fun rawHttpOnce(
        targetUrl: URL,
        method: String,
        body: String?,
        contentType: String,
        headers: Map<String, String>,
    ): HttpResponse {
        val conn = (targetUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            requestMethod = method
            setRequestProperty("User-Agent", "codex-quota-widget")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return HttpResponse(status, text)
    }

    private fun warmDns(host: String) {
        runCatching { InetAddress.getAllByName(host) }
    }

    private fun formEncode(vararg pairs: Pair<String, String>) = pairs.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }

    private data class HttpResponse(val status: Int, val body: String)
    private class PendingAuthException : Exception()
    private class UnauthorizedException : Exception()
}
