package top.logge.codexquota

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** No automatic retries for token exchanges: refresh tokens can rotate. */
internal object CodexHttp {
    data class Response(val status: Int, val body: String) {
        fun json(): JSONObject {
            if (status !in 200..299) throw HttpException(status)
            return JSONObject(body)
        }
    }
    class HttpException(val status: Int) : Exception("HTTP $status")

    fun request(
        url: String,
        body: String? = null,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("User-Agent", "codex-quota-widget/1.0")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return Response(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    fun form(vararg values: Pair<String, String>): String = values.joinToString("&") {
        "${URLEncoder.encode(it.first, "UTF-8")}=${URLEncoder.encode(it.second, "UTF-8")}"
    }
}
