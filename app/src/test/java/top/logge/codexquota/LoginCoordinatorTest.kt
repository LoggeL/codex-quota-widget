package top.logge.codexquota

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LoginCoordinatorTest {
    @Test fun cancelDuringCodeRequestDoesNotCreateGhostLogin() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadScheduledExecutor()
        val store = MemoryStore(AccountState())
        val client = object : DeviceAuthClient {
            override fun start(): CodexAuth.DeviceLogin {
                entered.countDown(); release.await(3, TimeUnit.SECONDS)
                return CodexAuth.DeviceLogin("https://auth.openai.com/codex/device", "code", "device", 5)
            }
            override fun poll(login: CodexAuth.DeviceLogin): CodexAuth.AuthState? = error("not expected")
        }
        try {
            val coordinator = LoginCoordinator(store, {}, client, worker)
            coordinator.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            coordinator.cancel(); release.countDown()
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertNull(store.state.pendingLogin)
            assertFalse(coordinator.requesting)
            assertTrue(store.state.accounts.isEmpty())
        } finally { release.countDown(); worker.shutdownNow() }
    }
    @Test fun persistedLoginResumesAndDeduplicatesExistingAccount() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val done = CountDownLatch(1)
        val pending = PendingLogin(CodexAuth.DeviceLogin("https://auth.openai.com/codex/device", "code", "device", 0), System.currentTimeMillis() + 60000)
        val original = account().copy(name = "Privat")
        val store = MemoryStore(AccountState(listOf(original), pending))
        val client = object : DeviceAuthClient {
            override fun start(): CodexAuth.DeviceLogin = error("must resume existing code")
            override fun poll(login: CodexAuth.DeviceLogin) = original.auth.copy(accessToken = "fresh-login")
        }
        try {
            val coordinator = LoginCoordinator(store, {}, client, worker)
            coordinator.onConnected = { done.countDown() }
            coordinator.resume(); coordinator.resume()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertNull(store.state.pendingLogin)
            assertEquals(1, store.state.accounts.size)
            assertEquals("Privat", store.state.accounts.single().name)
            assertEquals("fresh-login", store.state.accounts.single().auth.accessToken)
        } finally { worker.shutdownNow() }
    }
    @Test fun expiredLoginIsClearedWithoutNetworkRequest() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val store = MemoryStore(AccountState(pendingLogin = PendingLogin(CodexAuth.DeviceLogin("", "", "", 5), 1)))
        try {
            val coordinator = LoginCoordinator(store, {}, worker = worker)
            coordinator.resume()
            assertNull(store.state.pendingLogin)
            assertTrue(coordinator.message!!.contains("abgelaufen"))
        } finally { worker.shutdownNow() }
    }
}
