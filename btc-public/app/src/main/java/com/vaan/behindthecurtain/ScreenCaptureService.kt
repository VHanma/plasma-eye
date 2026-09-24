package com.vaan.behindthecurtain

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

/**
 * Passive screen watcher.
 *
 * The service continuously analyzes the user-approved MediaProjection stream,
 * but creates NO full-screen overlay until a candidate passes the visibility
 * gate. The overlay is FLAG_NOT_TOUCHABLE, so even while a finding is boxed it
 * never steals taps/swipes from the app underneath it.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: android.hardware.display.VirtualDisplay? = null
    private var wm: WindowManager? = null
    private var overlay: OverlayView? = null

    private val ex = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tracker = Tracker()
    private val temporal = TemporalEngine()

    private var lastFrame = 0L
    private var lastOcr = 0L
    private var lastTpl = 0L
    private var ocr: List<Detection> = emptyList()
    private var tpl: List<Detection> = emptyList()

    // Tracks how many consecutive analyzed frames a stable candidate survives.
    private val persistence = mutableMapOf<Int, Int>()

    override fun onCreate() {
        super.onCreate()
        channel()
        startForeground(401, note("Passive Screen Watch active"))
        // Intentionally no overlay here. The screen stays visually untouched
        // until an actual finding is ready to point out.
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val code = i?.getIntExtra("code", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra("data")
        }
        if (data == null) return START_NOT_STICKY

        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, main)
        startDisplay()
        return START_NOT_STICKY
    }

    private fun startDisplay() {
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val w = bounds.width().coerceAtLeast(1)
        val h = bounds.height().coerceAtLeast(1)

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vd = projection?.createVirtualDisplay(
            "BTC-passive-screen-watch",
            w,
            h,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            null
        )

        reader!!.setOnImageAvailableListener({ r ->
            val now = System.currentTimeMillis()
            val im = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (now - lastFrame < 220) {
                im.close()
                return@setOnImageAvailableListener
            }
            lastFrame = now

            val p = im.planes[0]
            val buf = p.buffer
            val ps = p.pixelStride
            val rs = p.rowStride
            val pad = rs - ps * w
            val paddedW = w + pad / ps
            val tmp = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888)
            tmp.copyPixelsFromBuffer(buf)
            im.close()
            val bmp = Bitmap.createBitmap(tmp, 0, 0, w, h)
            tmp.recycle()

            ex.execute {
                if (!AppState.master(this)) {
                    persistence.clear()
                    showOnlyWhenNeeded(w, h, emptyList(), emptyList())
                    return@execute
                }

                if (now - lastOcr > 950) {
                    lastOcr = now
                    ocr = OcrEngine.scan(bmp, true)
                }
                if (now - lastTpl > 1450) {
                    lastTpl = now
                    tpl = TemplateMatcher.scan(bmp, TemplateStore.load(this))
                }

                val tracked = tracker.update(
                    (VisualEngine.scan(bmp) + temporal.scan(bmp) + ocr + tpl)
                        .sortedByDescending { it.confidence }
                        .take(30)
                )

                val alive = tracked.map { it.id }.toSet()
                persistence.keys.retainAll(alive)
                tracked.forEach { d -> persistence[d.id] = (persistence[d.id] ?: 0) + 1 }

                // Text/template matches can appear immediately. Generic structure
                // has to survive several analyzed frames so ordinary UI texture
                // does not constantly light up the screen.
                val findings = tracked.filter { d ->
                    val immediate =
                        d.label.startsWith("TEXT:") ||
                        d.label.startsWith("TEXT ALT:") ||
                        d.label.startsWith("TEMPLATE:") ||
                        (d.label.contains("TRANSIENT") && d.confidence >= 0.84f)
                    val stable = (persistence[d.id] ?: 0) >= 3 && d.confidence >= 0.80f
                    immediate || stable
                }
                    .sortedByDescending { it.confidence }
                    .take(6)

                val subtitles = findings.mapNotNull {
                    when {
                        it.label.startsWith("TEXT ALT:") -> "ALT: " + it.label.removePrefix("TEXT ALT:").trim()
                        it.label.startsWith("TEXT:") -> it.label.removePrefix("TEXT:").trim()
                        else -> null
                    }
                }.distinct().take(3)

                showOnlyWhenNeeded(w, h, findings, subtitles)

                // Evidence is saved only for findings that actually crossed the
                // display gate, keeping the vault from filling with background noise.
                if (findings.isNotEmpty()) EvidenceStore.visual(this, bmp, findings)
            }
        }, main)
    }

    private fun showOnlyWhenNeeded(
        sourceW: Int,
        sourceH: Int,
        findings: List<Detection>,
        subtitles: List<String>
    ) {
        main.post {
            if (findings.isEmpty()) {
                removeOverlay()
                return@post
            }

            ensureOverlay()
            overlay?.sourceW = sourceW
            overlay?.sourceH = sourceH
            overlay?.ds = findings
            overlay?.subtitles = if (AppState.subtitles(this)) subtitles else emptyList()
            overlay?.invalidate()
        }
    }

    private fun ensureOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        if (wm == null) wm = getSystemService(WINDOW_SERVICE) as WindowManager

        overlay = OverlayView(this)
        wm?.addView(
            overlay,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
        )
    }

    private fun removeOverlay() {
        val current = overlay ?: return
        try { wm?.removeView(current) } catch (_: Throwable) {}
        overlay = null
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    "btc_screen",
                    "Behind the Curtain passive screen watch",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun note(s: String) = NotificationCompat.Builder(this, "btc_screen")
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("Behind the Curtain")
        .setContentText(s)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        main.post { removeOverlay() }
        reader?.close()
        vd?.release()
        projection?.stop()
        ex.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(i: Intent?) = null
}
