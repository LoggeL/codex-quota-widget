package top.logge.codexquota

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Actual consumption with a white marker at the same snapshot's elapsed-time budget. */
internal object QuotaUsageBar {
    fun color(pace: QuotaPace.Result?): Int = when {
        pace == null -> Color.rgb(157, 174, 189)
        pace.delta >= 15 -> Color.rgb(251, 113, 133)
        pace.delta >= 7 -> Color.rgb(240, 191, 118)
        else -> Color.rgb(110, 216, 186)
    }

    fun bitmap(window: QuotaPresentation.Window): Bitmap {
        val bitmap = Bitmap.createBitmap(400, 12, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(41, 54, 68)
        canvas.drawRoundRect(RectF(0f, 2f, 400f, 10f), 4f, 4f, paint)
        paint.color = color(window.pace)
        if (window.used > 0) canvas.drawRoundRect(RectF(0f, 2f, window.used * 4f, 10f), 4f, 4f, paint)
        window.pace?.let {
            paint.color = Color.rgb(242, 245, 248)
            val x = (it.expectedUsed * 4).toFloat().coerceIn(2f, 398f)
            canvas.drawRect(x - 1.5f, 0f, x + 1.5f, 12f, paint)
        }
        return bitmap
    }

    fun description(account: String, window: QuotaPresentation.Window): String =
        "$account: ${window.label}, ${window.used} % verbraucht, ${window.text}. ${window.forecastText}. ${window.paceText}. ${window.resetText}"
}
