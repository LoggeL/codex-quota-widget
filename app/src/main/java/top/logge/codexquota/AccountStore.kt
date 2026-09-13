package top.logge.codexquota

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** One atomic encrypted document, protected by a device-bound Android Keystore key. */
internal class AccountStore(private val context: Context) : StateStore {
    private val prefs = context.getSharedPreferences("accounts_v1", Context.MODE_PRIVATE)

    @Synchronized override fun read(): AccountState {
        val encrypted = prefs.getString("data", null)
        if (encrypted != null) return AccountCodec.decode(decrypt(encrypted))
        return migrateLegacy()
    }

    @Synchronized override fun update(transform: (AccountState) -> AccountState): AccountState =
        transform(read()).also(::write)

    fun add(auth: CodexAuth.AuthState): Account = update { state -> withAccount(state, auth) }
        .accounts.first { it.auth.identity == auth.identity }

    fun remove(id: String) = update { it.copy(accounts = it.accounts.filterNot { a -> a.id == id }) }
    fun rename(id: String, name: String) = update { state -> state.copy(accounts = state.accounts.map {
        if (it.id == id) it.copy(name = name.trim().take(40).ifBlank { it.name }) else it
    }) }

    private fun write(state: AccountState) {
        check(prefs.edit().putString("data", encrypt(AccountCodec.encode(state))).commit()) { "Speichern fehlgeschlagen" }
        // Also clean up after a process death between the encrypted write and legacy deletion.
        context.getSharedPreferences("codex_auth", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("codex_quota_cache", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun migrateLegacy(): AccountState {
        val old = context.getSharedPreferences("codex_auth", Context.MODE_PRIVATE)
        val access = old.getString("access_token", null) ?: return AccountState()
        val id = old.getString("id_token", "").orEmpty()
        val claims = CodexAuth.claims(id)
        val auth = CodexAuth.AuthState(access, old.getString("refresh_token", "").orEmpty(), id,
            old.getString("account_id", null), old.getString("plan_type", null), old.getBoolean("is_fedramp", false),
            claims?.stringOrNull("email"), claims?.stringOrNull("sub"))
        val cache = context.getSharedPreferences("codex_quota_cache", Context.MODE_PRIVATE)
        val savedAt = cache.getLong("saved_at", 0)
        val quota = runCatching { QuotaCacheCodec.decode(JSONObject(cache.getString("quota_json", "").orEmpty())) }
            .getOrNull()?.let { q -> q.copy(primary = q.primary?.anchoredAt(savedAt), weekly = q.weekly?.anchoredAt(savedAt)) }
        return AccountState(listOf(Account(UUID.randomUUID().toString(), auth.email ?: "Account 1", auth,
            quota, savedAt))).also(::write)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        // Never silently replace a lost key while ciphertext still exists.
        check(!prefs.contains("data")) { "Account-Speicher kann nicht entschlüsselt werden" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(raw: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(raw.toByteArray(Charsets.UTF_8)))
    }
    private fun decrypt(raw: String): String {
        val bytes = Base64.getDecoder().decode(raw)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    companion object {
        private const val KEY_ALIAS = "codex-quota-accounts-v1"
        internal fun withAccount(state: AccountState, auth: CodexAuth.AuthState): AccountState {
            val old = state.accounts.firstOrNull { it.auth.identity == auth.identity }
            val account = old?.copy(auth = auth, error = null)
                ?: Account(UUID.randomUUID().toString(), auth.email ?: "Account ${state.accounts.size + 1}", auth)
            return state.copy(accounts = if (old == null) state.accounts + account else state.accounts.map {
                if (it.id == old.id) account else it
            })
        }
    }
}
