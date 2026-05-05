package top.logge.codexquota

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            text = "Add the widget to your home screen. It reads https://codex-quota.logge.top/status.json and refreshes every 30 minutes or on tap."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        val button = Button(this).apply {
            text = "Refresh widgets"
            setOnClickListener {
                val manager = AppWidgetManager.getInstance(this@MainActivity)
                val ids = manager.getAppWidgetIds(ComponentName(this@MainActivity, CodexQuotaWidgetProvider::class.java))
                CodexQuotaWidgetProvider().onUpdate(this@MainActivity, manager, ids)
            }
        }
        layout.addView(title)
        layout.addView(body)
        layout.addView(button)
        setContentView(layout)
    }
}
