package top.logge.codexquota

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CodexQuotaLog {
    private const val PREFS = "codex_quota_debug_log"
    private const val KEY_LINES = "lines"
    private const val MAX_LINES = 80

    fun append(context: Context, message: String) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val timestamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.GERMANY).format(Date())
        val line = "$timestamp  ${message.sanitizeLogLine()}"
        val lines = (prefs.getString(KEY_LINES, "") ?: "")
            .lineSequence()
            .filter { it.isNotBlank() }
            .plus(line)
            .takeLastCompat(MAX_LINES)
            .joinToString("\n")
        prefs.edit().putString(KEY_LINES, lines).apply()
    }

    fun read(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LINES, "")!!
            .ifBlank { "No widget log entries yet." }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun String.sanitizeLogLine(): String =
        replace(Regex("""Bearer\s+[A-Za-z0-9._~+/=-]+"""), "Bearer <redacted>")
            .replace(Regex("""refresh_token[=:]\S+"""), "refresh_token=<redacted>")
            .replace('\n', ' ')
            .take(280)

    private fun <T> Sequence<T>.takeLastCompat(count: Int): List<T> {
        val buffer = ArrayDeque<T>()
        for (item in this) {
            buffer.addLast(item)
            if (buffer.size > count) buffer.removeFirst()
        }
        return buffer.toList()
    }
}
