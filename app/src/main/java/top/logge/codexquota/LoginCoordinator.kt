package top.logge.codexquota

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService

internal interface DeviceAuthClient {
    fun start(): CodexAuth.DeviceLogin
    fun poll(login: CodexAuth.DeviceLogin): CodexAuth.AuthState?
}

internal class LoginCoordinator(
    private val store: StateStore,
    private val changed: () -> Unit,
    private val client: DeviceAuthClient = object : DeviceAuthClient {
        override fun start() = CodexAuth.startDeviceLogin()
        override fun poll(login: CodexAuth.DeviceLogin) = CodexAuth.poll(login)
    },
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
) {
    @Volatile var requesting = false; private set
    @Volatile var message: String? = null; private set
    private var generation = 0
    private var polling = false

    @Synchronized fun start() {
        if (requesting || store.read().pendingLogin != null) return
        requesting = true
        message = null
        val run = ++generation
        changed()
        worker.execute {
            val result = runCatching { client.start() }
            synchronized(this) {
                if (run != generation) return@execute
                requesting = false
                result.onSuccess { login ->
                    store.update { it.copy(pendingLogin = PendingLogin(login, System.currentTimeMillis() + 15 * 60_000L)) }
                    resume()
                }.onFailure { message = userError(it) }
                changed()
            }
        }
    }

    @Synchronized fun resume() {
        if (polling) return
        val pending = store.read().pendingLogin ?: return
        if (pending.expiresAt <= System.currentTimeMillis()) { finish("Anmeldung abgelaufen · bitte erneut starten"); return }
        polling = true
        val run = generation
        worker.schedule({ poll(pending, run) }, pending.login.intervalSeconds, TimeUnit.SECONDS)
    }

    private fun poll(pending: PendingLogin, run: Int) {
        synchronized(this) {
            if (run != generation) return
            if (pending.expiresAt <= System.currentTimeMillis()) { finish("Anmeldung abgelaufen · bitte erneut starten"); return }
        }
        val result = runCatching { client.poll(pending.login) }
        synchronized(this) {
            if (run != generation) return
            polling = false
            result.onSuccess { auth ->
                if (auth == null) { message = null; resume() } else {
                    store.update { AccountStore.withAccount(it, auth).copy(pendingLogin = null) }
                    message = "Account verbunden"
                    changed()
                    onConnected?.invoke()
                }
            }.onFailure {
                // A pending code survives network failures and process recreation until its deadline.
                if (it is CodexHttp.HttpException && it.status in 400..499 && it.status != 429) finish(userError(it))
                else { message = "Anmeldung wartet auf Verbindung"; resume() }
            }
            changed()
        }
    }

    var onConnected: (() -> Unit)? = null

    @Synchronized fun cancel() { finish(null) }
    private fun finish(text: String?) {
        generation++
        requesting = false
        polling = false
        message = text
        store.update { it.copy(pendingLogin = null) }
        changed()
    }
}
