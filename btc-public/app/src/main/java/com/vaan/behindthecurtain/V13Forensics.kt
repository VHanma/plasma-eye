package com.vaan.behindthecurtain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object V13Transforms {
    data class View(val name: String, val bitmap: Bitmap, val boxSafe: Boolean = true)

    fun bank(src: Bitmap, forensic: Boolean = true): List<View> {
        val base = scale(src, if (forensic) 900 else 520)
        val out = mutableListOf(
            View("BASE", base),
            View("CONTRAST", ImageFx.enhance(base)),
            View("INVERT", ImageFx.invert(base)),
            View("GAMMA_065", gamma(base, 0.65)),
            View("GAMMA_145", gamma(base, 1.45)),
            View("RED", channel(base, 0)),
            View("GREEN", channel(base, 1)),
            View("BLUE", channel(base, 2)),
            View("OPPONENT_RG", opponent(base, true)),
            View("OPPONENT_BY", opponent(base, false)),
            View("HIGH_PASS", highPass(base))
        )
        if (forensic) {
            out += View("GAMMA_045", gamma(base, 0.45))
            out += View("GAMMA_220", gamma(base, 2.20))
            out += View("MIRROR", mirror(base), false)
            out += View("ROTATE_180", rotate(base, 180f), false)
            out += View("X_STRETCH_050", stretch(base, 0.5f, 1f), false)
            out += View("X_STRETCH_135", stretch(base, 1.35f, 1f), false)
            out += View("X_STRETCH_200", stretch(base, 2f, 1f), false)
            out += View("Y_STRETCH_050", stretch(base, 1f, 0.5f), false)
            out += View("Y_STRETCH_135", stretch(base, 1f, 1.35f), false)
            out += View("Y_STRETCH_200", stretch(base, 1f, 2f), false)
        }
        return out
    }

    private fun scale(src: Bitmap, maxW: Int): Bitmap {
        if (src.width <= maxW) return src
        val h = (src.height * maxW.toFloat() / src.width).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxW, h, true)
    }

    private fun map(src: Bitmap, f: (Int) -> Int): Bitmap {
        val p = IntArray(src.width * src.height)
        src.getPixels(p, 0, src.width, 0, 0, src.width, src.height)
        for (i in p.indices) p[i] = f(p[i])
        return Bitmap.createBitmap(p, src.width, src.height, Bitmap.Config.ARGB_8888)
    }

    private fun gamma(src: Bitmap, g: Double) = map(src) { c ->
        fun cv(v: Int) = (255.0 * (v / 255.0).pow(g)).roundToInt().coerceIn(0, 255)
        Color.rgb(cv(Color.red(c)), cv(Color.green(c)), cv(Color.blue(c)))
    }

    private fun channel(src: Bitmap, which: Int) = map(src) { c ->
        val v = when (which) { 0 -> Color.red(c); 1 -> Color.green(c); else -> Color.blue(c) }
        Color.rgb(v, v, v)
    }

    private fun opponent(src: Bitmap, rg: Boolean) = map(src) { c ->
        val v = if (rg) Color.red(c) - Color.green(c) + 128
        else Color.blue(c) - (Color.red(c) + Color.green(c)) / 2 + 128
        val q = v.coerceIn(0, 255)
        Color.rgb(q, q, q)
    }

    private fun highPass(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val p = IntArray(w * h)
        src.getPixels(p, 0, w, 0, 0, w, h)
        val y = IntArray(p.size) { i ->
            val c = p[i]
            (Color.red(c) * 77 + Color.green(c) * 150 + Color.blue(c) * 29) shr 8
        }
        val o = IntArray(p.size) { Color.rgb(128, 128, 128) }
        for (yy in 1 until h - 1) for (x in 1 until w - 1) {
            val i = yy * w + x
            val q = y[i] * 8 - y[i - w - 1] - y[i - w] - y[i - w + 1] - y[i - 1] - y[i + 1] - y[i + w - 1] - y[i + w] - y[i + w + 1]
            val v = (128 + q / 4).coerceIn(0, 255)
            o[i] = Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(o, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun mirror(src: Bitmap): Bitmap {
        val m = Matrix().apply { setScale(-1f, 1f); postTranslate(src.width.toFloat(), 0f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun rotate(src: Bitmap, deg: Float): Bitmap {
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun stretch(src: Bitmap, sx: Float, sy: Float) = Bitmap.createScaledBitmap(
        src,
        (src.width * sx).roundToInt().coerceAtLeast(1),
        (src.height * sy).roundToInt().coerceAtLeast(1),
        true
    )
}

object V13SymbolEngine {
    fun scan(src: Bitmap): List<Detection> {
        val w = min(360, src.width)
        val h = max(1, (src.height * w.toFloat() / src.width).roundToInt())
        val b = Bitmap.createScaledBitmap(src, w, h, true)
        val p = IntArray(w * h)
        b.getPixels(p, 0, w, 0, 0, w, h)
        val lum = IntArray(p.size) { i ->
            val c = p[i]
            (Color.red(c) * 77 + Color.green(c) * 150 + Color.blue(c) * 29) shr 8
        }
        val out = mutableListOf<Detection>()
        val tile = 60
        val sx = src.width.toFloat() / w
        val sy = src.height.toFloat() / h
        var y0 = 1
        while (y0 + tile < h - 1) {
            var x0 = 1
            while (x0 + tile < w - 1) {
                var mirror = 0.0
                var mirrorBase = 0.0
                var edgeTop = 0.0
                var edgeBottom = 0.0
                var edgeCenter = 0.0
                var edgeSide = 0.0
                var edgeTotal = 0.0
                for (yy in y0 until y0 + tile) {
                    for (xx in x0 + 1 until x0 + tile - 1) {
                        val i = yy * w + xx
                        val e = abs(lum[i + 1] - lum[i - 1]) + if (yy > 0 && yy < h - 1) abs(lum[i + w] - lum[i - w]) else 0
                        edgeTotal += e
                        if (yy < y0 + tile / 2) edgeTop += e else edgeBottom += e
                        if (abs(xx - (x0 + tile / 2)) < tile / 7) edgeCenter += e else edgeSide += e
                    }
                    for (dx in 0 until tile / 2) {
                        val a = lum[yy * w + x0 + dx]
                        val c = lum[yy * w + x0 + tile - 1 - dx]
                        mirror += abs(a - c)
                        mirrorBase += abs(a - 128) + abs(c - 128) + 1
                    }
                }
                val symmetry = 1.0 - (mirror / mirrorBase).coerceIn(0.0, 1.0)
                val centerRatio = edgeCenter / (edgeTotal + 1.0)
                val topRatio = edgeTop / (edgeTotal + 1.0)
                val rect = RectF(x0 * sx, y0 * sy, (x0 + tile) * sx, (y0 + tile) * sy)
                if (symmetry > 0.89 && edgeTotal > 55_000) {
                    out += Detection("SYMBOL FAMILY • BILATERAL / MASK-LIKE", (0.58 + (symmetry - 0.89) * 2.4).toFloat().coerceAtMost(0.92f), rect)
                }
                if (symmetry > 0.86 && centerRatio > 0.22 && edgeSide > edgeCenter * 1.6) {
                    out += Detection("SYMBOL FAMILY • EYE / WINGED COMPOSITE", (0.57 + symmetry * 0.32).toFloat().coerceAtMost(0.91f), rect)
                }
                if (topRatio > 0.58 && symmetry > 0.82) {
                    out += Detection("SYMBOL FAMILY • APEX / TRIANGULAR FORM", (0.52 + topRatio * 0.42).toFloat().coerceAtMost(0.88f), rect)
                }
                x0 += tile
            }
            y0 += tile
        }
        return out.sortedByDescending { it.confidence }.take(12)
    }
}

object V13VisualAnalyzer {
    fun live(context: Context, src: Bitmap, includeOcr: Boolean): List<Detection> {
        val out = mutableListOf<Detection>()
        out += VisualEngine.scan(src)
        out += V13SymbolEngine.scan(src)
        out += TemplateMatcher.scan(src, TemplateStore.load(context))
        if (includeOcr) out += OcrEngine.scan(src, true)
        return merge(out).take(36)
    }

    fun deep(context: Context, src: Bitmap): Pair<List<Detection>, List<String>> {
        val all = mutableListOf<Detection>()
        val notes = mutableListOf<String>()
        val views = V13Transforms.bank(src, true)
        for ((index, view) in views.withIndex()) {
            val ds = VisualEngine.scan(view.bitmap)
            if (view.boxSafe && view.bitmap.width == views[0].bitmap.width && view.bitmap.height == views[0].bitmap.height) {
                val sx = src.width.toFloat() / view.bitmap.width
                val sy = src.height.toFloat() / view.bitmap.height
                all += ds.map { d -> d.copy(label = "${d.label} [${view.name}]", rect = RectF(d.rect.left * sx, d.rect.top * sy, d.rect.right * sx, d.rect.bottom * sy)) }
            } else if (ds.firstOrNull()?.confidence ?: 0f > 0.84f) {
                notes += "${view.name}: ${ds.take(3).joinToString { it.label + " " + (it.confidence * 100).roundToInt() + "%" }}"
            }
            if (index < 9) {
                val text = OcrEngine.scan(view.bitmap, false).take(5)
                if (text.isNotEmpty()) notes += "OCR ${view.name}: ${text.joinToString(" | ") { it.label.removePrefix("TEXT: ").removePrefix("TEXT ALT: ") }}"
            }
        }
        all += V13SymbolEngine.scan(src)
        all += TemplateMatcher.scan(src, TemplateStore.load(context))
        all += OcrEngine.scan(src, true)
        return merge(all).take(60) to notes.distinct().take(30)
    }

    private fun merge(input: List<Detection>): List<Detection> {
        val out = mutableListOf<Detection>()
        for (d in input.sortedByDescending { it.confidence }) {
            val family = d.label.substringBefore('[').substringBefore('•').trim()
            val old = out.indexOfFirst { it.label.substringBefore('[').substringBefore('•').trim() == family && iou(it.rect, d.rect) > 0.24f }
            if (old < 0) out += d else if (d.confidence > out[old].confidence) out[old] = d
        }
        return out.sortedByDescending { it.confidence }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top); val r = min(a.right, b.right); val bo = min(a.bottom, b.bottom)
        if (r <= l || bo <= t) return 0f
        val inter = (r - l) * (bo - t)
        return inter / (a.width() * a.height() + b.width() * b.height() - inter + 1f)
    }
}

object V13LanguageAnalyzer {
    data class Flag(val category: String, val excerpt: String, val confidence: Float)
    private val rules = listOf(
        Triple("TEMPORAL PRESUPPOSITION", Regex("\\b(when|after|before|once|since)\\s+you\\b", RegexOption.IGNORE_CASE), 0.78f),
        Triple("MODAL PRESSURE", Regex("\\b(you must|you have to|you need to|you should|you cannot|you can't)\\b", RegexOption.IGNORE_CASE), 0.76f),
        Triple("NEGATIVE QUESTION", Regex("\\b(don't|doesn't|isn't|aren't|wouldn't|couldn't|shouldn't)\\s+you\\b", RegexOption.IGNORE_CASE), 0.80f),
        Triple("ASSIGNED INTERNAL STATE", Regex("\\byou\\s+(really\\s+)?(want|feel|know|think|believe|need)\\b", RegexOption.IGNORE_CASE), 0.82f),
        Triple("CAUSE / EFFECT LINK", Regex("\\b(makes? you|causes? you to|means? you|so you will)\\b", RegexOption.IGNORE_CASE), 0.74f),
        Triple("ABSOLUTE / LIMITING WORD", Regex("\\b(always|never|everyone|nobody|only|completely|totally)\\b", RegexOption.IGNORE_CASE), 0.62f),
        Triple("COMMITMENT PRESSURE", Regex("\\b(you already (said|agreed|decided)|you promised|since you agreed)\\b", RegexOption.IGNORE_CASE), 0.80f)
    )

    fun analyze(text: String): List<Flag> {
        val out = mutableListOf<Flag>()
        for ((name, re, score) in rules) for (m in re.findAll(text)) out += Flag(name, m.value, score)
        return out.distinctBy { it.category + it.excerpt.lowercase() }
    }

    fun fromDecoderLines(lines: List<String>): List<Flag> {
        val text = lines.joinToString(" ") { it.substringAfter(":", it) }
        return analyze(text)
    }
}

object V13ProsodyAnalyzer {
    fun analyze(pcm: ShortArray, rate: Int): List<String> {
        if (pcm.size < rate) return emptyList()
        val win = max(256, rate / 20)
        val hop = max(128, win / 2)
        data class F(val t: Long, val rms: Double, val zcr: Double)
        val frames = mutableListOf<F>()
        var p = 0
        while (p + win <= pcm.size) {
            var sq = 0.0; var cross = 0
            for (i in p until p + win) {
                val v = pcm[i] / 32768.0
                sq += v * v
                if (i > p && (pcm[i] >= 0) != (pcm[i - 1] >= 0)) cross++
            }
            frames += F(p * 1000L / rate, sqrt(sq / win), cross.toDouble() / win)
            p += hop
        }
        if (frames.size < 8) return emptyList()
        val rmsMed = frames.map { it.rms }.sorted()[frames.size / 2]
        val zMed = frames.map { it.zcr }.sorted()[frames.size / 2]
        val rmsDev = frames.map { abs(it.rms - rmsMed) }.sorted()[frames.size / 2].coerceAtLeast(0.002)
        val zDev = frames.map { abs(it.zcr - zMed) }.sorted()[frames.size / 2].coerceAtLeast(0.008)
        val out = mutableListOf<String>()
        for (f in frames) {
            val a = abs(f.rms - rmsMed) / rmsDev
            val z = abs(f.zcr - zMed) / zDev
            if (a > 5.0 || z > 6.0) out += "${f.t}ms • PROSODY SHIFT • ${if (a > 5) "level" else ""}${if (z > 6) " articulation" else ""}"
        }
        return out.distinct().take(30)
    }
}

object V13AudioDeep {
    fun signalFindings(pcm: ShortArray, rate: Int): List<AudioFinding> {
        val out = mutableListOf<AudioFinding>()
        val step = 8192
        var p = 0
        while (p + 2048 < pcm.size) {
            val e = min(pcm.size, p + step)
            AudioInspector.scan(pcm.copyOfRange(p, e), rate)?.let { out += it }
            p += step
        }
        return out.sortedByDescending { it.confidence }.distinctBy { it.label + (it.peakHz / 25).roundToInt() }.take(30)
    }

    fun decodeBlocking(context: Context, pcm: ShortArray, rate: Int, timeoutSec: Long = 18): List<String> {
        val latch = CountDownLatch(1)
        var lines: List<String> = emptyList()
        SpeechDecoder.decode(context, pcm, rate) { r -> lines = r; latch.countDown() }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        return lines
    }

    fun carrierNote(pcm: ShortArray, rate: Int): String? {
        if (rate < 44_100 || pcm.size < 4096) return null
        val s = Fft.spectrum(pcm.copyOf(min(pcm.size, 16384)))
        if (s.m.isEmpty()) return null
        val fft = s.m.size * 2
        val lo = (18_000.0 * fft / rate).roundToInt().coerceIn(1, s.m.lastIndex)
        val hi = (min(24_000.0, rate * 0.49) * fft / rate).roundToInt().coerceIn(lo, s.m.lastIndex)
        var bi = lo; var bm = 0.0
        for (i in lo..hi) if (s.m[i] > bm) { bm = s.m[i]; bi = i }
        val med = s.m.sortedArray()[s.m.size / 2].coerceAtLeast(1e-12)
        val ratio = bm / med
        return if (ratio > 18) "NARROWBAND CARRIER CANDIDATE • ${(bi * rate.toDouble() / fft).roundToInt()} Hz • peak/median ${"%.1f".format(ratio)}" else null
    }
}