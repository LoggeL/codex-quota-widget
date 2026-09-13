package top.logge.codexquota

import java.net.UnknownHostException
import java.net.SocketTimeoutException

internal interface StateStore {
    fun read(): AccountState
    fun update(transform: (AccountState) -> AccountState): AccountState
}

internal interface QuotaClient {
    fun fetch(auth: CodexAuth.AuthState): Quota
    fun refresh(auth: CodexAuth.AuthState): CodexAuth.AuthState
}

internal class QuotaRepository(
    private val store: StateStore,
    private val client: QuotaClient = object : QuotaClient {
        override fun fetch(auth: CodexAuth.AuthState) = CodexAuth.fetchQuota(auth)
        override fun refresh(auth: CodexAuth.AuthState) = CodexAuth.refresh(auth)
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val changed: () -> Unit = {},
) {
    /** Serializes refresh-token rotation across periodic, manual and app requests. */
    @Synchronized fun refreshAll(): Boolean {
        var retry = false
        for (initial in store.read().accounts) {
            if (Thread.currentThread().isInterrupted) break
            var auth = initial.auth
            try {
                val quota = try { client.fetch(auth) } catch (e: CodexHttp.HttpException) {
                    if (e.status != 401) throw e
                    val refreshed = client.refresh(auth)
                    var stored = false
                    store.update { state -> state.copy(accounts = state.accounts.map { current ->
                        if (current.id == initial.id && current.auth == auth) {
                            stored = true
                            current.copy(auth = refreshed)
                        } else current
                    }) }
                    // A removed or re-authenticated account must not be resurrected by an old request.
                    if (!stored) continue
                    auth = refreshed
                    client.fetch(auth)
                }
                val fetchedAt = now()
                val anchored = quota.copy(primary = quota.primary?.anchoredAt(fetchedAt), weekly = quota.weekly?.anchoredAt(fetchedAt))
                store.update { state -> state.copy(accounts = state.accounts.map {
                    if (it.id == initial.id && it.auth == auth) it.copy(quota = anchored, fetchedAt = fetchedAt, error = null) else it
                }) }
            } catch (e: Exception) {
                if (e is InterruptedException) { Thread.currentThread().interrupt(); break }
                retry = retry || e !is CodexHttp.HttpException || e.status >= 500 || e.status == 429
                store.update { state -> state.copy(accounts = state.accounts.map {
                    if (it.id == initial.id && it.auth == auth) it.copy(error = userError(e)) else it
                }) }
            }
            changed()
        }
        return retry
    }
}

internal fun userError(error: Throwable): String = when (error) {
    is CodexHttp.HttpException -> when (error.status) {
        400, 401 -> "Erneut anmelden"
        403 -> "Zugriff abgelehnt"
        429 -> "Zu viele Anfragen · später erneut versuchen"
        else -> "Dienst nicht erreichbar (HTTP ${error.status})"
    }
    is UnknownHostException -> "Offline · keine Verbindung"
    is SocketTimeoutException -> "Zeitüberschreitung"
    else -> "Abruf fehlgeschlagen"
}
