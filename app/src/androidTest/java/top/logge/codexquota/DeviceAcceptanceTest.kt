package top.logge.codexquota

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import android.view.View
import android.widget.FrameLayout
import java.io.File

/** Uses invented accounts only. Exercises the real Android Keystore and RemoteViews runtime. */
@Suppress("DEPRECATION")
class DeviceAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var activity: Activity? = null
    private val now: Long get() = System.currentTimeMillis()
    @Before fun setUp() { clear() }
    @After fun tearDown() {
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        clear()
    }
    private fun clear() {
        listOf("accounts_v1", "codex_auth", "codex_quota_cache").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    private fun fake(id: String, name: String, used: Int, primary: Boolean = false): Account {
        val auth = CodexAuth.AuthState("test-access-$id", "test-refresh-$id", "", id, "pro", false, null, "user-$id")
        return Account(id, name, auth, Quota("pro", if (primary) WindowQuota(72, "1h", now + 3600000) else null,
            WindowQuota(used, "6d", now + 6 * 86400000L)), now)
    }
    @Test fun testEncryptedStorageSurvivesNewStoreInstanceAndRemovesOnlyOneAccount() {
        val store = AccountStore(context)
        val accounts = listOf(fake("1", "Privat", 25), fake("2", "Arbeit", 19))
        store.update { AccountState(accounts) }
        val disk = context.getSharedPreferences("accounts_v1", Context.MODE_PRIVATE).getString("data", "")!!
        assertFalse(disk.contains("test-access"))
        assertFalse(disk.contains("Privat"))
        assertEquals(accounts, AccountStore(context).read().accounts)
        store.remove("1")
        assertEquals(listOf(accounts[1]), AccountStore(context).read().accounts)
    }
    @Test fun testLegacyLoginMigratesOnceAndDeletesPlaintext() {
        context.getSharedPreferences("codex_auth", Context.MODE_PRIVATE).edit()
            .putString("access_token", "legacy-access").putString("refresh_token", "legacy-refresh")
            .putString("account_id", "legacy-account").commit()
        val state = AccountStore(context).read()
        assertEquals(1, state.accounts.size)
        assertEquals("legacy-access", state.accounts.single().auth.accessToken)
        assertTrue(context.getSharedPreferences("codex_auth", Context.MODE_PRIVATE).all.isEmpty())
        assertEquals(state, AccountStore(context).read())
    }
    @Test fun testTwoAccountAppAndWidgetScreens() {
        val accounts = listOf(fake("1", "Privat", 25), fake("2", "Arbeit", 19))
        AccountStore(context).update { AccountState(accounts) }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        screenshot("app-two-accounts.png")
        renderWidget(accounts, 180, "widget-two-weekly.png")
        renderWidget(listOf(fake("1", "Privat", 25, true), fake("2", "Arbeit", 19, true)), 180, "widget-two-windows.png")
        renderWidget(listOf(accounts[0].copy(error = "Erneut anmelden"), accounts[1]), 180, "widget-partial-error.png")
    }
    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun renderWidget(accounts: List<Account>, heightDp: Int, name: String) {
        instrumentation.runOnMainSync {
            val density = context.resources.displayMetrics.density
            val width = (360 * density).toInt(); val height = (heightDp * density).toInt()
            val root = CodexQuotaWidgetProvider.buildViews(context, accounts, heightDp).apply(context, FrameLayout(context))
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            fun checkBounds(view: View) {
                if (view.visibility != View.VISIBLE) return
                if (view is android.widget.TextView) {
                    val rect = android.graphics.Rect(0, 0, view.width, view.height)
                    (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(view, rect)
                    assertTrue("Clipped text: ${view.text}", rect.bottom <= height - root.paddingBottom)
                }
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) checkBounds(view.getChildAt(i))
            }
            checkBounds(root)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null), name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
