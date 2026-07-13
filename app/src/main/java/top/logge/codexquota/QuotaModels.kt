package top.logge.codexquota

data class WindowQuota(val used: Int, val reset: String)

data class Quota(
    val plan: String,
    val primary: WindowQuota?,
    val weekly: WindowQuota?,
    val creditsBalance: String? = null,
)
