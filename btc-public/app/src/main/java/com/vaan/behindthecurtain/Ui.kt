package com.vaan.behindthecurtain

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    fun dp(c: Context, n: Int) = (n * c.resources.displayMetrics.density).toInt()

    fun root(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18))
        setBackgroundColor(Color.BLACK)
    }

    fun title(c: Context, s: String) = TextView(c).apply {
        text = s
        setTextColor(Color.WHITE)
        textSize = 27f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun body(c: Context, s: String) = TextView(c).apply {
        text = s
        setTextColor(Color.rgb(190, 205, 210))
        textSize = 14f
        setLineSpacing(0f, 1.12f)
    }

    fun button(c: Context, s: String, click: () -> Unit) = Button(c).apply {
        text = s
        isAllCaps = false
        textSize = 15f
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(c, 56)).apply { topMargin = dp(c, 9) }
    }
}

data class Detection(
    val id: Int = -1,
    val label: String,
    val confidence: Float,
    val rect: RectF
) {
    constructor(label: String, confidence: Float, rect: RectF) : this(-1, label, confidence, rect)
}

data class AudioFinding(
    val label: String,
    val confidence: Float,
    val peakHz: Float,
    val rmsDb: Float,
    val detail: String
)

object AppState {
    private const val P = "btc_state"
    fun master(c: Context) = c.getSharedPreferences(P, 0).getBoolean("master", true)
    fun setMaster(c: Context, v: Boolean) = c.getSharedPreferences(P, 0).edit().putBoolean("master", v).apply()
    fun subtitles(c: Context) = c.getSharedPreferences(P, 0).getBoolean("subs", true)
    fun setSubtitles(c: Context, v: Boolean) = c.getSharedPreferences(P, 0).edit().putBoolean("subs", v).apply()
}
