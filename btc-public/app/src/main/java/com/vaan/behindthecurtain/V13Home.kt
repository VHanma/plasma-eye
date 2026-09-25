package com.vaan.behindthecurtain

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class V13MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.root(this)
        root.addView(Ui.title(this, "BEHIND THE CURTAIN v1.3"))
        root.addView(Ui.body(this, "Live screen watch + deep image/video/audio forensics. Candidate findings appear only when they pass the detection gate; raw evidence stays separate from interpretation."))
        root.addView(Ui.button(this, "◉ LIVE SCREEN WATCH") { startActivity(Intent(this, V13ScreenScannerActivity::class.java)) })
        root.addView(Ui.button(this, "◉ LIVE CAMERA SCAN") { startActivity(Intent(this, CameraScannerActivity::class.java)) })
        root.addView(Ui.button(this, "〽 AUDIO SENTINEL") { startActivity(Intent(this, AudioScannerActivity::class.java)) })
        root.addView(Ui.button(this, "◫ DEEP FORENSIC LAB") { startActivity(Intent(this, V13ForensicLabActivity::class.java)) })
        root.addView(Ui.button(this, "┃ FLOATING EDGE CONTROL") { startEdgeControl() })
        root.addView(Ui.button(this, if (AppState.subtitles(this)) "CC SUBTITLES: ON" else "CC SUBTITLES: OFF") {
            AppState.setSubtitles(this, !AppState.subtitles(this)); recreate()
        })
        root.addView(Ui.button(this, "⌂ BEHIND THE CURTAIN VAULT") { startActivity(Intent(this, VaultActivity::class.java)) })
        root.addView(Ui.body(this, "\nv1.3 adds transform-consensus scanning, symbol-family geometry, multi-pass hidden-text recovery, full-timeline video sampling, high-frequency/tonal analysis, reverse + speed-shift speech candidates, prosody-shift flags and defensive language-pattern analysis."))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startEdgeControl() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Allow display over other apps, then tap Edge Control again.", Toast.LENGTH_LONG).show()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, EdgeControlService::class.java))
    }
}
