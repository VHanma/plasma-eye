package com.vaan.behindthecurtain

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * User-started Screen Sentinel. Android MediaProjection consent is required for
 * every session. Visual frames and playback audio are processed only while the
 * visible foreground service is active. Android/source-app capture policy is
 * always respected.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: android.hardware.display.VirtualDisplay? = null
    private var wm: WindowManager? = null
    private var overlay: OverlayView? = null

    private val visualEx = Executors.newSingleThreadExecutor()
    private val audioEx = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tracker = Tracker()
    private val temporal = TemporalEngine()

    private var lastFrame = 0L
    private var lastOcr = 0L
    private var lastTpl = 0L
    private var ocr: List<Detection> = emptyList()
    private var tpl: List<Detection> = emptyList()
    private val persistence = mutableMapOf<Int, Int>()

    private var playback: AudioRecord? = null
    @Volatile private var audioRunning = false
    @Volatile private var speechBusy = false
    private var audioRate = 48_000
    private var audioRing: PcmRing? = null
    private var lastAudioSignal = 0L
    private var lastSpeechSweep = 0L
    private lateinit var audioSubs: SubtitleOverlay

    override fun onCreate() {
        super.onCreate()
        audioSubs = SubtitleOverlay(this)
        createChannels()
        startProjectionForeground("Screen Sentinel active")
    }

    private fun startProjectionForeground(text: String) {
        val n = ongoingNote(text)
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(401, n, types)
        } else {
            @Suppress("DEPRECATION")
            startForeground(401, n)
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val code = i?.getIntExtra("code", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra("data")
        } ?: return START_NOT_STICKY

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, main)

        startDisplay()
        startPlaybackCapture()
        return START_NOT_STICKY
    }

    private fun startDisplay() {
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val w = bounds.width().coerceAtLeast(1)
        val h = bounds.height().coerceAtLeast(1)

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vd = projection?.createVirtualDisplay(
            "BTC-screen-sentinel",
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
            if (now - lastFrame < 220L) {
                im.close()
                return@setOnImageAvailableListener
            }
            lastFrame = now

            val plane = im.planes[0]
            val ps = plane.pixelStride
            val rs = plane.rowStride
            val pad = rs - ps * w
            val paddedW = w + pad / ps
            val tmp = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888)
            tmp.copyPixelsFromBuffer(plane.buffer)
            im.close()
            val bmp = Bitmap.createBitmap(tmp, 0, 0, w, h)
            tmp.recycle()

            visualEx.execute {
                try {
                    if (!AppState.master(this)) {
                        persistence.clear()
                        showOnlyWhenNeeded(w, h, emptyList(), emptyList())
                        return@execute
                    }

                    if (now - lastOcr > 950L) {
                        lastOcr = now
                        ocr = OcrEngine.scan(bmp, true)
                    }
                    if (now - lastTpl > 1450L) {
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

                    val findings = tracked.filter { d ->
                        val immediate = d.label.startsWith("TEXT:") ||
                            d.label.startsWith("TEXT ALT:") ||
                            d.label.startsWith("TEMPLATE:") ||
                            (d.label.contains("TRANSIENT") && d.confidence >= 0.84f)
                        val stable = (persistence[d.id] ?: 0) >= 3 && d.confidence >= 0.80f
                        immediate || stable
                    }.sortedByDescending { it.confidence }.take(6)

                    val subtitles = findings.mapNotNull {
                        when {
                            it.label.startsWith("TEXT ALT:") -> "ALT: " + it.label.removePrefix("TEXT ALT:").trim()
                            it.label.startsWith("TEXT:") -> it.label.removePrefix("TEXT:").trim()
                            else -> null
                        }
                    }.distinct().take(3)

                    showOnlyWhenNeeded(w, h, findings, subtitles)
                    if (findings.isNotEmpty()) EvidenceStore.visual(this, bmp, findings)
                } finally {
                    bmp.recycle()
                }
            }
        }, main)
    }

    /**
     * Android 10+ playback capture. Only media explicitly capturable under the
     * platform's playback-capture rules can reach this AudioRecord.
     */
    private fun startPlaybackCapture() {
        if (Build.VERSION.SDK_INT < 29) return
        val mp = projection ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateOngoing("Screen active • playback audio permission off")
            return
        }

        audioEx.execute {
            try {
                val capture = AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audioRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()

                val min = AudioRecord.getMinBufferSize(
                    audioRate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                playback = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(65_536, if (min > 0) min * 4 else 65_536))
                    .setAudioPlaybackCaptureConfig(capture)
                    .build()

                val rec = playback ?: return@execute
                audioRing = PcmRing(audioRate * 12)
                val stereo = ShortArray(16_384)
                rec.startRecording()
                audioRunning = true
                updateOngoing("Screen + media audio active")

                while (audioRunning) {
                    val n = rec.read(stereo, 0, stereo.size, AudioRecord.READ_BLOCKING)
                    if (n < 2 || !AppState.master(this)) continue
                    val frames = n / 2
                    val mono = ShortArray(frames)
                    for (k in 0 until frames) {
                        mono[k] = ((stereo[k * 2].toInt() + stereo[k * 2 + 1].toInt()) / 2)
                            .coerceIn(-32768, 32767).toShort()
                    }
                    audioRing?.add(mono)
                    inspectMediaAudio(mono)
                }
            } catch (_: Throwable) {
                updateOngoing("Screen active • this source blocks playback capture")
            }
        }
    }

    private fun inspectMediaAudio(block: ShortArray) {
        val now = System.currentTimeMillis()
        val finding = AudioInspector.scan(block, audioRate)

        if (finding != null && now - lastAudioSignal >= 5_000L) {
            lastAudioSignal = now
            val clip = audioRing?.snap() ?: return
            if (!speechBusy) {
                speechBusy = true
                lastSpeechSweep = now
                SpeechDecoder.decode(this, clip, audioRate) { words ->
                    speechBusy = false
                    EvidenceStore.audio(this, clip, audioRate, finding, words)
                    notifyAudio(finding, words)
                    main.post { audioSubs.show(if (words.isEmpty()) listOf(audioLabel(finding)) else words) }
                }
            } else {
                EvidenceStore.audio(this, clip, audioRate, finding, emptyList())
                notifyAudio(finding, emptyList())
                main.post { audioSubs.show(listOf(audioLabel(finding))) }
            }
            return
        }

        if (!speechBusy && now - lastSpeechSweep >= 12_000L) {
            val clip = audioRing?.snap() ?: return
            if (clip.size >= audioRate * 3) {
                lastSpeechSweep = now
                speechBusy = true
                SpeechDecoder.decode(this, clip, audioRate) { words ->
                    speechBusy = false
                    if (words.isNotEmpty()) {
                        val speech = AudioFinding(
                            "MEDIA SPEECH / TRANSFORM CANDIDATE",
                            0.70f,
                            0f,
                            -120f,
                            "live media forward, reversed and speed-shift speech sweep"
                        )
                        EvidenceStore.audio(this, clip, audioRate, speech, words)
                        notifyAudio(speech, words)
                        main.post { audioSubs.show(words) }
                    }
                }
            }
        }
    }

    private fun audioLabel(f: AudioFinding) =
        if (f.peakHz > 0.1f) "${f.label} • ${"%.1f".format(f.peakHz)} Hz" else f.label

    private fun notifyAudio(f: AudioFinding, words: List<String>) {
        val label = audioLabel(f)
        val b = NotificationCompat.Builder(this, "btc_screen_audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Behind the Curtain • media audio")
            .setContentText(if (words.isEmpty()) label else "$label • ${words.first().take(70)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (words.isNotEmpty()) b.setStyle(NotificationCompat.BigTextStyle().bigText(words.take(6).joinToString("\n")))
        getSystemService(NotificationManager::class.java).notify(405, b.build())
    }

    private fun showOnlyWhenNeeded(sourceW: Int, sourceH: Int, findings: List<Detection>, subtitles: List<String>) {
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
        )
    }

    private fun removeOverlay() {
        val current = overlay ?: return
        try { wm?.removeView(current) } catch (_: Throwable) {}
        overlay = null
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("btc_screen", "Behind the Curtain screen watch", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("btc_screen_audio", "Behind the Curtain media audio alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun updateOngoing(text: String) {
        getSystemService(NotificationManager::class.java).notify(401, ongoingNote(text))
    }

    private fun ongoingNote(s: String) = NotificationCompat.Builder(this, "btc_screen")
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("Behind the Curtain")
        .setContentText(s)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        audioRunning = false
        try { playback?.stop() } catch (_: Throwable) {}
        playback?.release()
        playback = null
        main.post {
            removeOverlay()
            audioSubs.hide()
        }
        reader?.close()
        vd?.release()
        projection?.stop()
        visualEx.shutdownNow()
        audioEx.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(i: Intent?) = null
}
