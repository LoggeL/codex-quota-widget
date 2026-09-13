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
        renderWidget(accounts, 200, "widget-two-weekly.png")
        renderWidget(listOf(fake("1", "Privat", 25, true), fake("2", "Arbeit", 19, true)), 200, "widget-two-windows.png")
        renderWidget(listOf(accounts[0].copy(error = "Erneut anmelden"), accounts[1]), 200, "widget-partial-error.png")
        renderWidget(listOf(fake("1", "Privat", 25, true), fake("2", "Arbeit", 19, true)).map { it.copy(error = "Erneut anmelden") }, 200, "widget-two-errors.png")
        renderWidget(listOf(fake("1", "Privat", 25, true), fake("2", "Arbeit", 19, true)), 260, "widget-expanded.png")
    }
    @Test fun testFourByOneAtMinimumSizeAndLargerText() {
        val accounts = listOf(fake("1", "Privat", 25), fake("2", "Arbeit", 19))
        val both = listOf(fake("1", "Privat", 25, true), fake("2", "Arbeit", 19, true)).map {
            val quota = checkNotNull(it.quota)
            it.copy(quota = quota.copy(weekly = quota.weekly!!.copy(resetsAtMs = now + 6 * 86400000L + 12 * 3600000L)))
        }
        renderWidget(accounts, 56, "widget-4x1.png")
        renderWidget(accounts, 56, "widget-4x1-minimum.png", widthDp = 250)
        renderWidget(both, 56, "widget-4x1-both-windows.png", widthDp = 250)
        renderWidget(both, 56, "widget-4x1-large-text.png", widthDp = 250, fontScale = 1.3f)
        renderWidget(listOf(accounts[0].copy(error = "Erneut anmelden"), accounts[1]), 56, "widget-4x1-error.png")
        renderWidget(listOf(accounts[0].copy(quota = null, error = "Erneut anmelden"), accounts[1]), 56, "widget-4x1-no-data.png")
        renderWidget(emptyList(), 56, "widget-4x1-empty.png", widthDp = 250)
        val extremes = both.map { account ->
            account.copy(quota = account.quota!!.copy(primary = WindowQuota(100, "", now + 150 * 60000L),
                weekly = WindowQuota(99, "", now + 6 * 86400000L + 12 * 3600000L)))
        }
        renderWidget(extremes, 56, "widget-4x1-high-forecast.png", widthDp = 250, fontScale = 1.3f)
    }
    @Test fun testConsumptionBarAndExpectedMarkerPixels() {
        val sampledAt = now
        val window = QuotaPresentation.window("5 Std.", WindowQuota(25, "", sampledAt + 150 * 60000L), sampledAt)
        val bitmap = QuotaUsageBar.bitmap(window)
        assertEquals(android.graphics.Color.rgb(110, 216, 186), bitmap.getPixel(50, 6))
        assertEquals(android.graphics.Color.rgb(41, 54, 68), bitmap.getPixel(300, 6))
        assertEquals(android.graphics.Color.rgb(242, 245, 248), bitmap.getPixel(200, 6))
    }
    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun renderWidget(accounts: List<Account>, heightDp: Int, name: String, widthDp: Int = 360, fontScale: Float = 1f) {
        instrumentation.runOnMainSync {
            val config = android.content.res.Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
            val renderContext = context.createConfigurationContext(config)
            val density = renderContext.resources.displayMetrics.density
            val width = (widthDp * density).toInt(); val height = (heightDp * density).toInt()
            val root = CodexQuotaWidgetProvider.buildViews(renderContext, accounts, heightDp, width = widthDp).apply(renderContext, FrameLayout(renderContext))
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            fun checkBounds(view: View) {
                if (view.visibility != View.VISIBLE) return
                if (view is android.widget.TextView) {
                    if (view.id != R.id.refresh) assertTrue("Collapsed text: ${view.text} at $widthDp x $heightDp font $fontScale (${view.height} < ${view.layout.height})", view.height >= view.layout.height + view.compoundPaddingTop + view.compoundPaddingBottom)
                    val rect = android.graphics.Rect(0, 0, view.width, view.height)
                    (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(view, rect)
                    assertTrue("Clipped text: ${view.text} at $widthDp x $heightDp font $fontScale", rect.bottom <= height - root.paddingBottom)
                    if (view.id in listOf(R.id.window_value, R.id.window_reset, R.id.compact_status, R.id.window_pace, R.id.pair_primary_text, R.id.pair_weekly_text, R.id.pair_primary_pace, R.id.pair_weekly_pace, R.id.pair_primary_reset, R.id.pair_weekly_reset)) {
                        assertEquals("Ellipsized quota: ${view.text}", 0, view.layout.getEllipsisCount(0))
                        assertTrue("Quota text too wide: ${view.text}", view.paint.measureText(view.text.toString()) <= view.width - view.compoundPaddingLeft - view.compoundPaddingRight + 1)
                    }
                }
                if (view is android.widget.ImageView) {
                    val rect = android.graphics.Rect(0, 0, view.width, view.height)
                    (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(view, rect)
                    assertTrue("Clipped quota bar at $widthDp x $heightDp font $fontScale: $rect, height $height. ${view.contentDescription}", rect.bottom <= height - root.paddingBottom)
                    assertTrue("Zero-width quota bar", rect.width() > 0)
                }
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) checkBounds(view.getChildAt(i))
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null), name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            checkBounds(root)
        }
    }
}
