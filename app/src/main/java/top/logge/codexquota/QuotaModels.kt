package top.logge.codexquota

/** Percentages always belong to one account and one window. */
data class WindowQuota(
    val used: Int,
    val reset: String,
    val resetsAtMs: Long? = null,
    val durationMinutes: Long? = null,
)

data class Quota(
    val plan: String,
    val primary: WindowQuota?,
    val weekly: WindowQuota?,
    val creditsBalance: String? = null,
)

data class Account(
    val id: String,
    val name: String,
    val auth: CodexAuth.AuthState,
    val quota: Quota? = null,
    val fetchedAt: Long = 0,
    val error: String? = null,
)

data class PendingLogin(val login: CodexAuth.DeviceLogin, val expiresAt: Long)
data class AccountState(val accounts: List<Account> = emptyList(), val pendingLogin: PendingLogin? = null)
