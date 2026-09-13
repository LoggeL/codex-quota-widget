package top.logge.codexquota

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

internal class MemoryStore(var state: AccountState) : StateStore {
    override fun read() = state
    override fun update(transform: (AccountState) -> AccountState) = transform(state).also { state = it }
}
class QuotaRepositoryTest {
    @Test fun failureOfFirstAccountDoesNotBlockSecondOrEraseCache() {
        val store = MemoryStore(AccountState(listOf(account("one"), account("two"))))
        val client = object : QuotaClient {
            override fun fetch(auth: CodexAuth.AuthState): Quota {
                if (auth.accountId == "one") throw UnknownHostException()
                return Quota("pro", null, WindowQuota(19, "2h"))
            }
            override fun refresh(auth: CodexAuth.AuthState) = error("not expected")
        }
        assertTrue(QuotaRepository(store, client).refreshAll())
        assertEquals(25, store.state.accounts[0].quota!!.weekly!!.used)
        assertTrue(store.state.accounts[0].error!!.contains("Offline"))
        assertNull(store.state.accounts[1].error)
        assertEquals(19, store.state.accounts[1].quota!!.weekly!!.used)
    }
    @Test fun rotatedTokenIsSavedEvenIfQuotaRetryFails() {
        val store = MemoryStore(AccountState(listOf(account())))
        val client = object : QuotaClient {
            override fun fetch(auth: CodexAuth.AuthState): Quota {
                if (auth.accessToken == "rotated") throw UnknownHostException()
                throw CodexHttp.HttpException(401)
            }
            override fun refresh(auth: CodexAuth.AuthState) = auth.copy(accessToken = "rotated", refreshToken = "rotated-refresh")
        }
        QuotaRepository(store, client).refreshAll()
        assertEquals("rotated-refresh", store.state.accounts.single().auth.refreshToken)
        assertNotNull(store.state.accounts.single().quota)
    }
    @Test fun removedAccountIsNotResurrected() {
        val store = MemoryStore(AccountState(listOf(account())))
        val client = object : QuotaClient {
            override fun fetch(auth: CodexAuth.AuthState): Quota {
                store.state = AccountState()
                return Quota("pro", null, WindowQuota(1, "2h"))
            }
            override fun refresh(auth: CodexAuth.AuthState) = auth
        }
        QuotaRepository(store, client).refreshAll()
        assertTrue(store.state.accounts.isEmpty())
    }
    @Test fun newLoginCannotBeOverwrittenByOldRefresh() {
        val store = MemoryStore(AccountState(listOf(account())))
        val client = object : QuotaClient {
            override fun fetch(auth: CodexAuth.AuthState): Quota = throw CodexHttp.HttpException(401)
            override fun refresh(auth: CodexAuth.AuthState): CodexAuth.AuthState {
                store.state = store.state.copy(accounts = listOf(account().copy(auth = auth.copy(accessToken = "new-login"))))
                return auth.copy(accessToken = "old-refresh")
            }
        }
        QuotaRepository(store, client).refreshAll()
        assertEquals("new-login", store.state.accounts.single().auth.accessToken)
    }
    @Test fun unauthorizedDoesNotRetryForever() {
        val store = MemoryStore(AccountState(listOf(account())))
        val client = object : QuotaClient {
            override fun fetch(auth: CodexAuth.AuthState): Quota = throw CodexHttp.HttpException(401)
            override fun refresh(auth: CodexAuth.AuthState): CodexAuth.AuthState = throw CodexHttp.HttpException(400)
        }
        assertFalse(QuotaRepository(store, client).refreshAll())
        assertEquals("Erneut anmelden", store.state.accounts.single().error)
    }
}
