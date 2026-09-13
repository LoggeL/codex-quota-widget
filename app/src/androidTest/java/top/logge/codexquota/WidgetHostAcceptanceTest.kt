package top.logge.codexquota

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

/** Exercises the same serialized RemoteViews and asynchronous reuse path as a launcher. */
class WidgetHostAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val now = System.currentTimeMillis()
    private fun account(id: String, used: Int) = Account(id, "Account $id",
        CodexAuth.AuthState("fixture", "fixture", "", id, "pro", false, null, id),
        Quota("pro", null, WindowQuota(used, "", now + 6 * 86400000L)), now)

    @Test fun asyncHostKeepsEveryAccountAndQuotaAcrossUpdates() {
        val executor = Executors.newSingleThreadExecutor()
        lateinit var host: AppWidgetHostView
        instrumentation.runOnMainSync {
            // The test APK supplies a different package/resource context, like a launcher.
            host = AppWidgetHostView(instrumentation.context)
            val info = AppWidgetManager.getInstance(context).installedProviders.first {
                it.provider == ComponentName(context, CodexQuotaWidgetProvider::class.java)
            }
            host.setAppWidget(1, info)
            host.setPadding(0, 0, 0, 0)
            host.setExecutor(executor)
            host.updateAppWidget(RemoteViews(context.packageName, R.layout.codex_quota_widget_compact))
        }
        executor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
        try {
            listOf(listOf(account("one", 25), account("two", 19)),
                listOf(account("one", 31)),
                listOf(account("one", 40), account("two", 52)),
                emptyList(), listOf(account("one", 48), account("two", 60))).forEachIndexed { step, accounts ->
                val remote = CodexQuotaWidgetProvider.buildViews(context, accounts, 56, now, width = 360)
                val parcel = Parcel.obtain()
                val transported = try {
                    remote.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    RemoteViews.CREATOR.createFromParcel(parcel)
                } finally { parcel.recycle() }
                instrumentation.runOnMainSync { host.updateAppWidget(transported) }
                val expected = accounts.map { "W ${it.quota!!.weekly!!.used}→${it.quota!!.weekly!!.used * 7}%" }
                var observed = emptyList<String>()
                val deadline = System.currentTimeMillis() + 5000
                do {
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync { observed = descendants(host).filterIsInstance<TextView>().filter { it.id in listOf(R.id.first_weekly_value, R.id.second_weekly_value) }.map { it.text.toString() }.toList() }
                    if (observed == expected) break
                    Thread.sleep(25)
                } while (System.currentTimeMillis() < deadline)
                instrumentation.runOnMainSync {
                    val density = context.resources.displayMetrics.density
                    val width = (360 * density).toInt(); val height = (56 * density).toInt()
                    host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    host.layout(0, 0, width, height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    host.draw(Canvas(bitmap))
                    File(context.getExternalFilesDir(null), "widget-host-$step.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                assertEquals("Step $step: host lost quota rows", expected, observed)
                instrumentation.runOnMainSync {
                    assertEquals(accounts.size, descendants(host).filterIsInstance<TextView>().count { it.id in listOf(R.id.first_name, R.id.second_name) })
                    val more = host.findViewById<TextView>(R.id.widget_more)
                    assertEquals(View.GONE, more.visibility)
                }
            }
        } finally { executor.shutdownNow() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        if (view.visibility != View.VISIBLE) return@sequence
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
