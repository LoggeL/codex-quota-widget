package top.logge.codexquota

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Process-scoped work and listeners never retain an Activity across browser login or rotation. */
internal class QuotaRuntime private constructor(context: Context) {
    val context: Context = context.applicationContext
    val store = AccountStore(this.context)
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    val repository = QuotaRepository(store, changed = ::changed)
    val login = LoginCoordinator(store, ::changed)

    fun changed() {
        CodexQuotaLog.append(context, "Account- oder Quota-Status aktualisiert")
        CodexQuotaWidgetProvider.renderAll(context)
        main.post { listeners.forEach { it() } }
    }

    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak") // Stores applicationContext only.
        @Volatile private var instance: QuotaRuntime? = null
        fun get(context: Context): QuotaRuntime = instance ?: synchronized(this) {
            instance ?: QuotaRuntime(context).also { instance = it }
        }
    }
}
