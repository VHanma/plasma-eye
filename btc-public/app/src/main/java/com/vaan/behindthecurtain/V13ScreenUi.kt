package com.vaan.behindthecurtain

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class V13ScreenScannerActivity : AppCompatActivity() {
    private val requestCode = 1313

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Allow display over other apps, then start Live Screen Watch again.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
    }

    @Deprecated("Compatibility path")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, V13ScreenCaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Live Screen Watch is running.", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

class V13DetectionGate {
    private data class State(var hits: Int = 0, var lastSeen: Long = 0L)
    private val states = HashMap<String, State>()

    fun filter(input: List<Detection>, now: Long): List<Detection> {
        val out = mutableListOf<Detection>()
        for (d in input.sortedByDescending { it.confidence }) {
            if (d.confidence < 0.68f) continue
            val family = d.label.substringBefore(':').substringBefore('[').substringBefore('•').trim()
            val x = (d.rect.centerX() / 80f).roundToInt()
            val y = (d.rect.centerY() / 80f).roundToInt()
            val key = "$family/$x/$y"
            val state = states.getOrPut(key) { State() }
            state.hits = if (now - state.lastSeen < 1200L) state.hits + 1 else 1
            state.lastSeen = now
            val immediate = d.label.startsWith("TEXT") || d.label.contains("TRANSIENT") || d.label.contains("TEMPLATE")
            if ((immediate && d.confidence >= 0.78f) || state.hits >= 2 || d.confidence >= 0.90f) out += d
        }
        states.entries.removeAll { now - it.value.lastSeen > 2500L }
        return out.take(28)
    }
}

class V13OverlayView(context: Context) : View(context) {
    @Volatile var detections: List<Detection> = emptyList()
    @Volatile var subtitles: List<String> = emptyList()
    @Volatile var sourceW: Int = 1
    @Volatile var sourceH: Int = 1

    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 27f; typeface = Typeface.DEFAULT_BOLD }
    private val bg = Paint().apply { color = Color.argb(190, 0, 0, 0) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sx = width.toFloat() / max(1, sourceW)
        val sy = height.toFloat() / max(1, sourceH)
        for (d in detections) {
            val r = RectF(d.rect.left * sx, d.rect.top * sy, d.rect.right * sx, d.rect.bottom * sy)
            box.color = when {
                d.label.startsWith("TEXT") -> Color.YELLOW
                d.label.contains("TRANSIENT") -> Color.MAGENTA
                d.label.contains("TEMPLATE") -> Color.GREEN
                d.label.contains("SYMBOL FAMILY") -> Color.rgb(255, 160, 0)
                d.confidence >= 0.86f -> Color.RED
                else -> Color.CYAN
            }
            canvas.drawRect(r, box)
            val label = "${d.label} ${(d.confidence * 100).roundToInt()}%"
            val top = (r.top - 32).coerceAtLeast(0f)
            val right = min(width.toFloat(), r.left + text.measureText(label) + 12)
            canvas.drawRect(r.left, top, right, top + 32, bg)
            canvas.drawText(label.take(90), r.left + 5, top + 25, text)
        }
        if (subtitles.isNotEmpty() && AppState.subtitles(context)) {
            val lines = subtitles.take(4)
            val blockH = 42f * lines.size + 12f
            canvas.drawRect(0f, height - blockH, width.toFloat(), height.toFloat(), bg)
            lines.forEachIndexed { i, s -> canvas.drawText(s.take(110), 18f, height - blockH + 32 + i * 42f, text) }
        }
    }
}
