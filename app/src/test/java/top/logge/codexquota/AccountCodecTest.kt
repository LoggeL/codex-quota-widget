package top.logge.codexquota

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountCodecTest {
    @Test fun accountsAndPendingLoginRoundTripIndependently() {
        val state = AccountState(listOf(account("one"), account("two", 19)), PendingLogin(
            CodexAuth.DeviceLogin("https://auth.openai.com/codex/device", "CODE", "device", 5), 2000000000000L))
        assertEquals(state, AccountCodec.decode(AccountCodec.encode(state)))
    }
    @Test fun signingInAgainUpdatesSameAccountAndKeepsName() {
        val old = account().copy(name = "Privat")
        val state = AccountStore.withAccount(AccountState(listOf(old)), old.auth.copy(accessToken = "new"))
        assertEquals(1, state.accounts.size)
        assertEquals("Privat", state.accounts.single().name)
        assertEquals("new", state.accounts.single().auth.accessToken)
    }
    @Test fun differentAccountsAreNotMergedByEmailOrPlan() {
        val a = account()
        val other = auth("two").copy(email = a.auth.email)
        assertEquals(2, AccountStore.withAccount(AccountState(listOf(a)), other).accounts.size)
    }
    @Test fun refreshedResponseCanOmitIdToken() {
        val initial = auth()
        val result = CodexAuth.fromTokens(JSONObject("""{"access_token":"new-access"}"""), initial)
        assertEquals(initial.accountId, result.accountId)
        assertEquals(initial.refreshToken, result.refreshToken)
        assertEquals(initial.email, result.email)
    }
}
