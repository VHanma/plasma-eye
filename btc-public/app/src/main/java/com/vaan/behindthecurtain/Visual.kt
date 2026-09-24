package com.vaan.behindthecurtain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object ImageFx {
    fun enhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        for (pixel in pixels) {
            val y = (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) shr 8
            hist[y]++
        }

        val loTarget = (pixels.size * 0.01).toInt()
        val hiTarget = (pixels.size * 0.99).toInt()
        var acc = 0
        var lo = 0
        var hi = 255
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= loTarget) {
                lo = i
                break
            }
        }
        acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= hiTarget) {
                hi = i
                break
            }
        }
        val span = max(12, hi - lo)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val rr = ((Color.red(pixel) - lo) * 255 / span).coerceIn(0, 255)
            val gg = ((Color.green(pixel) - lo) * 255 / span).coerceIn(0, 255)
            val bb = ((Color.blue(pixel) - lo) * 255 / span).coerceIn(0, 255)
            pixels[i] = Color.rgb(rr, gg, bb)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun invert(src: Bitmap): Bitmap {
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            pixels[i] = Color.rgb(
                255 - Color.red(pixel),
                255 - Color.green(pixel),
                255 - Color.blue(pixel)
            )
        }
        return Bitmap.createBitmap(pixels, src.width, src.height, Bitmap.Config.ARGB_8888)
    }
}

class Tracker {
    private data class Track(val id: Int, var box: RectF, var miss: Int = 0, var label: String = "")
    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    @Synchronized
    fun update(detections: List<Detection>): List<Detection> {
        tracks.forEach { it.miss++ }
        val out = mutableListOf<Detection>()
        for (d in detections) {
            var best: Track? = null
            var bestIou = 0f
            for (track in tracks) {
                val value = iou(track.box, d.rect)
                if (value > bestIou) {
                    bestIou = value
                    best = track
                }
            }
            val track = if (best != null && bestIou > 0.16f) {
                best.box = smooth(best.box, d.rect)
                best.miss = 0
                best.label = d.label
                best
            } else {
                Track(nextId++, RectF(d.rect), 0, d.label).also { tracks += it }
            }
            out += d.copy(id = track.id, rect = RectF(track.box))
        }
        tracks.removeAll { it.miss > 5 }
        return out
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun smooth(a: RectF, b: RectF) = RectF(
        a.left * 0.65f + b.left * 0.35f,
        a.top * 0.65f + b.top * 0.35f,
        a.right * 0.65f + b.right * 0.35f,
        a.bottom * 0.65f + b.bottom * 0.35f
    )
}

class TemporalEngine {
    private var previous: IntArray? = null

    @Synchronized
    fun scan(src: Bitmap): List<Detection> {
        val w = min(240, src.width)
        val h = max(1, (src.height * w.toFloat() / src.width).toInt())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        val current = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            current[i] = (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) shr 8
        }
        val old = previous
        previous = current
        if (old == null || old.size != current.size) return emptyList()

        val out = mutableListOf<Detection>()
        val tile = 24
        val sx = src.width.toFloat() / w
        val sy = src.height.toFloat() / h
        var y = 0
        while (y + tile < h) {
            var x = 0
            while (x + tile < w) {
                var diffSum = 0
                var changed = 0
                var edges = 0
                for (yy in y until y + tile) {
                    for (xx in x until x + tile) {
                        val index = yy * w + xx
                        val d = abs(current[index] - old[index])
                        diffSum += d
                        if (d > 14) changed++
                        if (xx > 0 && abs(current[index] - current[index - 1]) > 20) edges++
                    }
                }
                val n = tile * tile
                val average = diffSum.toDouble() / n
                val changeRatio = changed.toDouble() / n
                val edgeRatio = edges.toDouble() / n
                if (average in 7.0..70.0 && changeRatio in 0.07..0.72 && edgeRatio > 0.055) {
                    out += Detection(
                        label = "TRANSIENT / ALPHA-FLASH",
                        confidence = (0.48 + changeRatio * 0.42).toFloat().coerceAtMost(0.95f),
                        rect = RectF(x * sx, y * sy, (x + tile) * sx, (y + tile) * sy)
                    )
                }
                x += tile
            }
            y += tile
        }
        return out.take(10)
    }
}

object VisualEngine {
    fun scan(src: Bitmap): List<Detection> {
        val w = min(320, src.width)
        val h = max(1, (src.height * w.toFloat() / src.width).toInt())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val red = IntArray(pixels.size)
        val green = IntArray(pixels.size)
        val blue = IntArray(pixels.size)
        val lum = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            red[i] = Color.red(pixel)
            green[i] = Color.green(pixel)
            blue[i] = Color.blue(pixel)
            lum[i] = (red[i] * 77 + green[i] * 150 + blue[i] * 29) shr 8
        }

        val out = mutableListOf<Detection>()
        val tile = 28
        val sx = src.width.toFloat() / w
        val sy = src.height.toFloat() / h
        var y0 = 1
        while (y0 + tile < h - 1) {
            var x0 = 1
            while (x0 + tile < w - 1) {
                val n = tile * tile
                var sum = 0.0
                var sum2 = 0.0
                var edges = 0
                var chroma = 0.0
                var lumaEdge = 0.0
                var bits = 0
                var mirror = 0.0
                var mirrorBase = 0.0

                for (yy in y0 until y0 + tile) {
                    for (xx in x0 until x0 + tile) {
                        val index = yy * w + xx
                        val value = lum[index]
                        sum += value
                        sum2 += value * value
                        if (xx < w - 1) {
                            val j = index + 1
                            val ld = abs(lum[index] - lum[j])
                            val cd = max(
                                abs(red[index] - red[j]),
                                max(abs(green[index] - green[j]), abs(blue[index] - blue[j]))
                            )
                            lumaEdge += ld
                            chroma += cd
                            if (ld > 22) edges++
                            bits += Integer.bitCount((lum[index] and 7) xor (lum[j] and 7))
                        }
                    }
                }
                for (yy in y0 until y0 + tile) {
                    for (dx in 0 until tile / 2) {
                        val a = lum[yy * w + x0 + dx]
                        val b = lum[yy * w + x0 + tile - 1 - dx]
                        mirror += abs(a - b)
                        mirrorBase += abs(a - 128) + abs(b - 128) + 1
                    }
                }

                val mean = sum / n
                val std = sqrt(max(0.0, sum2 / n - mean * mean))
                val edgeRatio = edges.toDouble() / n
                val chromaRatio = chroma / (lumaEdge + 1.0)
                val bitRatio = bits.toDouble() / (n * 3.0)
                val symmetry = 1.0 - (mirror / mirrorBase).coerceIn(0.0, 1.0)
                val rect = RectF(x0 * sx, y0 * sy, (x0 + tile) * sx, (y0 + tile) * sy)

                if (std in 2.0..19.0 && edgeRatio > 0.08) {
                    out += Detection("LOW-CONTRAST STRUCTURE", (0.54 + edgeRatio * 0.9).toFloat().coerceAtMost(0.94f), rect = rect)
                }
                if (std < 30.0 && chromaRatio > 1.8 && chroma / n > 8.0) {
                    out += Detection("CHROMA-MASK STRUCTURE", (0.55 + (chromaRatio - 1.8) * 0.1).toFloat().coerceAtMost(0.94f), rect = rect)
                }
                if (std < 25.0 && bitRatio > 0.37 && bitRatio < 0.72) {
                    out += Detection("LOW BIT-PLANE STRUCTURE", (0.51 + bitRatio * 0.45).toFloat().coerceAtMost(0.91f), rect = rect)
                }
                if (std in 8.0..44.0 && symmetry > 0.86 && lumaEdge / n > 7.0) {
                    out += Detection("SYMMETRIC / NEGATIVE-SPACE SHAPE", (0.48 + symmetry * 0.4).toFloat().coerceAtMost(0.91f), rect = rect)
                }
                if (std < 34.0 && edgeRatio > 0.21) {
                    out += Detection("TINY / DENSE DETAIL", (0.50 + edgeRatio).toFloat().coerceAtMost(0.90f), rect = rect)
                }
                x0 += tile
            }
            y0 += tile
        }
        return out.sortedByDescending { it.confidence }.take(18)
    }
}

object OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun scan(src: Bitmap, multi: Boolean = true): List<Detection> {
        val enhanced = ImageFx.enhance(src)
        val variants = if (multi) listOf(enhanced, ImageFx.invert(enhanced)) else listOf(enhanced)
        val raw = mutableListOf<Detection>()
        for ((pass, bitmap) in variants.withIndex()) {
            try {
                val result = Tasks.await(
                    recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                    1700,
                    TimeUnit.MILLISECONDS
                )
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val rect = line.boundingBox ?: continue
                        val text = line.text.trim()
                        if (text.isNotBlank()) {
                            raw += Detection(
                                label = (if (pass == 0) "TEXT: " else "TEXT ALT: ") + text,
                                confidence = if (pass == 0) 0.86f else 0.72f,
                                rect = RectF(rect)
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        val out = mutableListOf<Detection>()
        for (d in raw) {
            if (out.none { it.label == d.label && overlap(it.rect, d.rect) > 0.55f }) out += d
        }
        return out.take(16)
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val smaller = min(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else intersection / smaller
    }
}

object TemplateStore {
    private fun dir(c: Context) = File(c.filesDir, "btc_templates").apply { mkdirs() }

    fun add(c: Context, b: Bitmap): String {
        val name = "shape_${System.currentTimeMillis()}.png"
        File(dir(c), name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    fun load(c: Context): List<Pair<String, Bitmap>> =
        dir(c).listFiles()
            ?.filter { it.extension.lowercase() == "png" }
            ?.takeLast(8)
            ?.mapNotNull { file -> BitmapFactory.decodeFile(file.absolutePath)?.let { file.nameWithoutExtension to it } }
            ?: emptyList()
}

object TemplateMatcher {
    private fun gray(b: Bitmap, w: Int, h: Int): IntArray {
        val scaled = Bitmap.createScaledBitmap(b, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            out[i] = (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) shr 8
        }
        return out
    }

    fun scan(src: Bitmap, templates: List<Pair<String, Bitmap>>): List<Detection> {
        if (templates.isEmpty()) return emptyList()
        val sw = min(260, src.width)
        val sh = max(1, (src.height * sw.toFloat() / src.width).toInt())
        val sourceGray = gray(src, sw, sh)
        val sx = src.width.toFloat() / sw
        val sy = src.height.toFloat() / sh
        val out = mutableListOf<Detection>()
        val size = 20

        for ((name, template) in templates) {
            val templateGray = gray(template, size, size)
            val templateMean = templateGray.average()
            val templateDeviation = templateGray.sumOf { abs(it - templateMean) }.coerceAtLeast(1.0)
            for (y in 0 until sh - size step 10) {
                for (x in 0 until sw - size step 10) {
                    var patchMean = 0.0
                    for (yy in 0 until size) for (xx in 0 until size) patchMean += sourceGray[(y + yy) * sw + x + xx]
                    patchMean /= size * size
                    var error = 0.0
                    var patchDeviation = 0.0
                    var k = 0
                    for (yy in 0 until size) {
                        for (xx in 0 until size) {
                            val pv = sourceGray[(y + yy) * sw + x + xx] - patchMean
                            val tv = templateGray[k++] - templateMean
                            error += abs(pv - tv)
                            patchDeviation += abs(pv)
                        }
                    }
                    val confidence = (1.0 - error / (templateDeviation + patchDeviation + 1.0)).toFloat()
                    if (confidence > 0.80f) {
                        out += Detection(
                            label = "TEMPLATE: $name",
                            confidence = confidence.coerceAtMost(0.98f),
                            rect = RectF(x * sx, y * sy, (x + size) * sx, (y + size) * sy)
                        )
                    }
                }
            }
        }
        return out.sortedByDescending { it.confidence }.take(5)
    }
}

class OverlayView(c: Context) : View(c) {
    @Volatile var ds: List<Detection> = emptyList()
    @Volatile var sourceW = 1
    @Volatile var sourceH = 1
    @Volatile var subtitles: List<String> = emptyList()

    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 27f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val background = Paint().apply { color = Color.argb(190, 0, 0, 0) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sourceW <= 0 || sourceH <= 0) return
        val sx = width.toFloat() / sourceW
        val sy = height.toFloat() / sourceH
        for (d in ds) {
            val rect = RectF(d.rect.left * sx, d.rect.top * sy, d.rect.right * sx, d.rect.bottom * sy)
            box.color = when {
                d.label.startsWith("TEXT") -> Color.YELLOW
                d.label.contains("TRANSIENT") -> Color.MAGENTA
                d.label.contains("TEMPLATE") -> Color.GREEN
                d.confidence > 0.82f -> Color.RED
                else -> Color.CYAN
            }
            canvas.drawRect(rect, box)
            val label = "#${d.id} ${d.label} ${(d.confidence * 100).toInt()}%"
            val width = textPaint.measureText(label)
            val top = (rect.top - 32f).coerceAtLeast(0f)
            canvas.drawRect(rect.left, top, (rect.left + width + 10f).coerceAtMost(this.width.toFloat()), top + 32f, background)
            canvas.drawText(label, rect.left + 5f, top + 25f, textPaint)
        }
        if (subtitles.isNotEmpty()) {
            val lines = subtitles.take(4)
            val areaHeight = 42f * lines.size + 12f
            canvas.drawRect(0f, height - areaHeight, width.toFloat(), height.toFloat(), background)
            lines.forEachIndexed { index, line ->
                canvas.drawText(line.take(110), 18f, height - areaHeight + 32f + index * 42f, textPaint)
            }
        }
    }
}
