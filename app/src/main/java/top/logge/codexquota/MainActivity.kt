package top.logge.codexquota

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val runtime by lazy { QuotaRuntime.get(this) }
    private val observer: () -> Unit = { if (!isFinishing) render() }
    private var scroll: ScrollView? = null
    private var busy = false
    private var localMessage: String? = null
    private val ink = Color.rgb(239, 244, 247)
    private val muted = Color.rgb(157, 174, 189)
    private val accent = Color.rgb(110, 216, 186)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        runtime.login.onConnected = { QuotaRefreshJob.schedule(appContext, immediately = true) }
        QuotaRefreshJob.schedule(this)
        render()
    }
    override fun onStart() {
        super.onStart()
        runtime.listeners.add(observer)
        runCatching { runtime.login.resume() }
        render()
    }
    override fun onStop() {
        runtime.listeners.remove(observer)
        super.onStop()
    }

    private fun render() {
        val position = scroll?.scrollY ?: 0
        val state = runCatching { runtime.store.read() }.getOrElse {
            val message = TextView(this).apply { text = "Account-Speicher nicht lesbar. Bitte die App neu starten."; setPadding(32, 80, 32, 32) }
            setContentView(message)
            return
        }
        val root = column().apply {
            setBackgroundColor(Color.rgb(11, 17, 23))
            setPadding(dp(22), dp(24), dp(22), dp(28))
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(22), dp(24) + insets.systemWindowInsetTop, dp(22), dp(28) + insets.systemWindowInsetBottom)
                insets
            }
        }
        root.addView(text("CODEX / QUOTA", 12, accent, bold = true))
        root.addView(text("Deine Accounts.\nAlles im Blick.", 30, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(12)) })
        root.addView(text("Restquota, Verbrauch und Prognose für jeden Account.", 14, muted))
        localMessage?.let { root.addView(text(it, 14, accent)) }
        runtime.login.message?.let { root.addView(text(it, 14, accent)) }

        if (state.accounts.isEmpty()) {
            root.addView(card().apply {
                addView(text("Dein erster Account", 20, ink, bold = true))
                addView(text("Verbinde deinen ChatGPT-Account. Danach kannst du den zweiten Account ergänzen und beide im Widget sehen.", 15, muted))
            })
        }
        state.accounts.forEach { account -> root.addView(accountCard(account)) }

        if (state.accounts.isNotEmpty()) root.addView(card().apply {
            addView(text("So liest du die Prognose", 16, ink, bold = true))
            addView(text("25 → 175 % heißt: 25 % verbraucht, bei gleichem Durchschnittstempo bis zum Reset voraussichtlich 175 %. Die Hochrechnung nutzt die Zeit seit Beginn des Quota-Fensters und den letzten Datenstand.", 13, muted))
            addView(text("Der Balken zeigt den Verbrauch. Die weiße Marke zeigt das Soll bei gleichmäßiger Nutzung. +11 pp bedeutet 11 Prozentpunkte Defizit, −11 pp entsprechend Vorsprung. Im 4×1-Widget steht oben der Vergleich für das knappere Zeitfenster (5h oder W).", 13, muted))
            addView(text("Eine Schätzung, keine Zusage: Dein künftiges Tempo kann sich ändern. Bei alten Daten erst aktualisieren; nach dem Reset ist die alte Prognose ungültig.", 12, muted))
        })
        val pending = state.pendingLogin
        if (pending != null) root.addView(card().apply {
            addView(text("Anmeldung abschließen", 20, ink, bold = true))
            addView(text("Wähle im Browser den gewünschten Account. Für einen anderen Account dort zuerst den Account wechseln.", 14, muted))
            addView(text(pending.login.userCode, 30, accent, bold = true).apply { setTextIsSelectable(true) })
            addView(button("Code kopieren und Browser öffnen") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Codex Login", pending.login.userCode))
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.login.verificationUrl))) }
                    .onFailure { localMessage = "Kein Browser verfügbar"; render() }
            })
            addView(text("Die Anmeldung läuft beim Wechsel in den Browser weiter. Nach einem App-Neustart wird sie fortgesetzt.", 12, muted))
            addView(button("Anmeldung abbrechen") { runtime.login.cancel() })
        }) else root.addView(button(if (runtime.login.requesting) "Anmeldecode wird geladen …" else "+ Account hinzufügen") {
            localMessage = null
            runtime.login.start()
        }.apply { isEnabled = !runtime.login.requesting })

        root.addView(button(if (busy) "Quota wird aktualisiert …" else "Alle Accounts aktualisieren") {
            busy = true
            localMessage = null
            render()
            runtime.executor.execute {
                val result = runCatching { runtime.repository.refreshAll() }
                runOnUiThread {
                    busy = false
                    localMessage = if (result.isFailure) "Aktualisierung fehlgeschlagen" else null
                    if (!isDestroyed) render()
                }
            }
        }.apply { isEnabled = state.accounts.isNotEmpty() && !busy })
        root.addView(button("Widget zum Startbildschirm hinzufügen") {
            val manager = AppWidgetManager.getInstance(this)
            if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this, CodexQuotaWidgetProvider::class.java), null, null)
            else { localMessage = "Auf dem Startbildschirm lange drücken und unter Widgets Codex Quota wählen."; render() }
        })
        root.addView(text("Automatische Aktualisierung ungefähr alle 30 Minuten, abhängig von Android. Im Widget öffnet ein Tipp die App; ↻ aktualisiert beide Accounts.", 12, muted))
        root.addView(text("Dein Desktop-Switcher bleibt unabhängig. Melde dieselben Accounts hier einmal an. Zugangsdaten werden auf diesem Gerät verschlüsselt gespeichert.", 12, muted).apply { setPadding(0, dp(12), 0, dp(8)) })
        root.addView(button("Diagnose anzeigen") {
            AlertDialog.Builder(this).setTitle("Diagnose")
                .setMessage(CodexQuotaLog.read(this).ifBlank { "Keine Diagnose-Einträge." })
                .setPositiveButton("Schließen", null).setNeutralButton("Leeren") { _, _ -> CodexQuotaLog.clear(this) }.show()
        })
        root.addView(text("Version ${BuildConfig.VERSION_NAME}", 11, muted))
        scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        scroll?.post { scroll?.scrollTo(0, position) }
    }

    private fun accountCard(account: Account): LinearLayout {
        val data = QuotaPresentation.card(account)
        return card().apply {
            addView(text(data.plan, 11, accent, bold = true))
            addView(text(data.name, 20, ink, bold = true))
            if (account.auth.email != null && account.auth.email != data.name) addView(text(account.auth.email, 12, muted))
            addView(text(data.status, 12, if (data.stale) Color.rgb(240, 191, 118) else muted))
            data.windows.forEach { window ->
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(16), 0, dp(4))
                    addView(text(window.label, 14, muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text(window.text, 22, ink, bold = true))
                })
                addView(text("${window.used} % verbraucht", 12, muted))
                addView(ImageView(this@MainActivity).apply {
                    setImageBitmap(QuotaUsageBar.bitmap(window))
                    scaleType = ImageView.ScaleType.FIT_XY
                    contentDescription = QuotaUsageBar.description(data.name, window)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
                addView(text(window.forecastText, 14, ink).apply { setPadding(0, dp(8), 0, 0) })
                addView(text(window.paceText, 12, QuotaUsageBar.color(window.pace)))
                window.pace?.let { addView(text(it.status, 12, QuotaUsageBar.color(it), bold = true)) }
                addView(text(window.resetText, 12, muted).apply { setPadding(0, dp(6), 0, 0) })
            }
            if (data.windows.isEmpty()) addView(text("Aktualisiere die Quota oder melde diesen Account erneut an.", 14, muted))
            addView(LinearLayout(this@MainActivity).apply {
                addView(button("Umbenennen") { rename(account) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(button("Entfernen") { remove(account) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }
    private fun rename(account: Account) {
        val input = EditText(this).apply { setText(account.name); setSingleLine(); filters = arrayOf(android.text.InputFilter.LengthFilter(40)) }
        AlertDialog.Builder(this).setTitle("Account benennen").setView(input)
            .setNegativeButton("Abbrechen", null).setPositiveButton("Speichern") { _, _ ->
                runtime.store.rename(account.id, input.text.toString()); runtime.changed()
            }.show()
    }
    private fun remove(account: Account) {
        AlertDialog.Builder(this).setTitle("Account entfernen?").setMessage("${account.name} wird mit seinen gespeicherten Zugangsdaten und Quota-Werten von diesem Gerät entfernt.")
            .setNegativeButton("Abbrechen", null).setPositiveButton("Entfernen") { _, _ ->
                runtime.store.remove(account.id); runtime.changed()
            }.show()
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply {
        background = GradientDrawable().apply { setColor(Color.rgb(20, 29, 38)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(35, 48, 60)) }
        setPadding(dp(18), dp(16), dp(18), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18); bottomMargin = dp(4) }
    }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minHeight = dp(48)
        setTextColor(accent)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(31, 48, 58))
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
