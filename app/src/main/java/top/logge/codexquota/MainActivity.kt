package top.logge.codexquota

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private var activeLogin: CodexAuth.DeviceLogin? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Codex Quota Widget"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val body = TextView(this).apply {
            text = if (CodexAuth.isLoggedIn(this@MainActivity)) {
                "Signed in. Add the widget to your home screen; it refreshes every 30 minutes or on tap."
            } else {
                "Sign in with ChatGPT/Codex once. The widget then reads your quota directly from your account."
            }
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        status = TextView(this).apply {
            text = if (CodexAuth.isLoggedIn(this@MainActivity)) "Ready" else "Not signed in"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        logView = TextView(this).apply {
            text = CodexQuotaLog.read(this@MainActivity)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 16)
        }

        val loginButton = Button(this).apply {
            text = if (CodexAuth.isLoggedIn(this@MainActivity)) "Sign in again" else "Sign in with Codex"
            setOnClickListener { startLogin() }
        }
        val refreshButton = Button(this).apply {
            text = "Refresh widgets"
            setOnClickListener { refreshWidgets() }
        }
        val refreshLogButton = Button(this).apply {
            text = "Refresh log"
            setOnClickListener { refreshLog() }
        }
        val clearLogButton = Button(this).apply {
            text = "Clear log"
            setOnClickListener {
                CodexQuotaLog.clear(this@MainActivity)
                refreshLog()
            }
        }
        val logoutButton = Button(this).apply {
            text = "Log out"
            isEnabled = CodexAuth.isLoggedIn(this@MainActivity)
            setOnClickListener {
                CodexAuth.logout(this@MainActivity)
                render()
            }
        }

        layout.addView(title)
        layout.addView(body)
        layout.addView(status)
        layout.addView(loginButton)
        layout.addView(refreshButton)
        layout.addView(refreshLogButton)
        layout.addView(clearLogButton)
        layout.addView(logoutButton)

        val logTitle = TextView(this).apply {
            text = "Widget log"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 8)
        }
        val logScroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        layout.addView(logTitle)
        layout.addView(logScroll)
        setContentView(layout)
    }

    private fun startLogin() {
        status.text = "Requesting device code…"
        thread {
            val result = runCatching { CodexAuth.startDeviceLogin() }
            runOnUiThread {
                result.onSuccess { login -> showDeviceCode(login) }
                    .onFailure { status.text = "Login start failed: ${it.message}" }
            }
        }
    }

    private fun showDeviceCode(login: CodexAuth.DeviceLogin) {
        activeLogin = login
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Codex login code", login.userCode))
        status.text = "Open login page and enter code: ${login.userCode}\n(code copied)"

        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login.verificationUrl)))

        thread {
            val result = runCatching { CodexAuth.completeDeviceLogin(this, login) }
            runOnUiThread {
                result.onSuccess {
                    status.text = "Signed in. Refreshing widget…"
                    refreshWidgets()
                    render()
                }.onFailure {
                    status.text = "Login failed: ${it.message}"
                }
            }
        }
    }

    private fun refreshWidgets() {
        CodexQuotaLog.append(this, "manual refresh requested from app")
        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, CodexQuotaWidgetProvider::class.java))
        CodexQuotaWidgetProvider().onUpdate(this, manager, ids)
        status.text = "Refresh requested for ${ids.size} widget(s)"
        refreshLog()
    }

    private fun refreshLog() {
        if (::logView.isInitialized) {
            logView.text = CodexQuotaLog.read(this)
        }
    }
}
