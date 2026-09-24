package com.vaan.behindthecurtain

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Fft {
    data class S(val m: DoubleArray, val rms: Float)

    fun spectrum(x: ShortArray): S {
        var n = 1
        while (n * 2 <= x.size) n *= 2
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        var rmsSum = 0.0
        for (i in 0 until n) {
            val v = x[i] / 32768.0
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1).coerceAtLeast(1))
            re[i] = v * window
            rmsSum += v * v
        }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val baseR = cos(angle)
            val baseI = sin(angle)
            var start = 0
            while (start < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[start + k]
                    val ui = im[start + k]
                    val vr = re[start + k + len / 2] * wr - im[start + k + len / 2] * wi
                    val vi = re[start + k + len / 2] * wi + im[start + k + len / 2] * wr
                    re[start + k] = ur + vr
                    im[start + k] = ui + vi
                    re[start + k + len / 2] = ur - vr
                    im[start + k + len / 2] = ui - vi
                    val nextWr = wr * baseR - wi * baseI
                    wi = wr * baseI + wi * baseR
                    wr = nextWr
                }
                start += len
            }
            len = len shl 1
        }

        val mags = DoubleArray(n / 2) { i -> hypot(re[i], im[i]) + 1e-12 }
        val rmsDb = (20.0 * log10(sqrt(rmsSum / n.coerceAtLeast(1)) + 1e-12)).toFloat()
        return S(mags, rmsDb)
    }
}

object AudioInspector {
    fun scan(x: ShortArray, rate: Int): AudioFinding? {
        if (x.size < 2048) return null
        val s = Fft.spectrum(x)
        val fftSize = s.m.size * 2
        var total = 0.0
        var infra = 0.0
        var ultra = 0.0
        var peak = 0.0
        var peakBin = 1
        val vals = ArrayList<Double>(s.m.size)

        for (i in 1 until s.m.size) {
            val energy = s.m[i] * s.m[i]
            total += energy
            val frequency = i * rate.toDouble() / fftSize
            if (frequency in 1.0..20.0) infra += energy
            if (frequency >= 20_000.0 && frequency < rate / 2.0) ultra += energy
            if (s.m[i] > peak) {
                peak = s.m[i]
                peakBin = i
            }
            vals += s.m[i]
        }
        if (total <= 0.0 || vals.isEmpty()) return null

        vals.sort()
        val median = vals[vals.size / 2].coerceAtLeast(1e-12)
        val tonal = peak / median
        val infraRatio = (infra / total).toFloat()
        val ultraRatio = (ultra / total).toFloat()
        val peakHz = (peakBin * rate.toDouble() / fftSize).toFloat()

        return when {
            s.rms > -62f && infraRatio > 0.08f -> AudioFinding(
                "INFRASOUND / SUB-20Hz ENERGY",
                (0.55f + infraRatio * 2.8f).coerceAtMost(0.98f),
                peakHz,
                s.rms,
                "sub-20Hz ratio ${(infraRatio * 100).toInt()}%"
            )
            rate >= 44_100 && s.rms > -70f && ultraRatio > 0.06f -> AudioFinding(
                "ULTRASONIC-BAND ENERGY",
                (0.55f + ultraRatio * 3f).coerceAtMost(0.98f),
                peakHz,
                s.rms,
                "20kHz+ ratio ${(ultraRatio * 100).toInt()}%"
            )
            tonal > 28.0 && s.rms < -14f -> AudioFinding(
                "LOW-LEVEL TONAL / CARRIER CANDIDATE",
                (0.52f + (ln(tonal) / 10.0).toFloat()).coerceAtMost(0.95f),
                peakHz,
                s.rms,
                "peak/median ${"%.1f".format(tonal)}"
            )
            else -> null
        }
    }
}

class PcmRing(private val capacity: Int) {
    private val data = ShortArray(capacity)
    private var pos = 0
    private var full = false

    @Synchronized
    fun add(x: ShortArray) {
        for (v in x) {
            data[pos] = v
            pos = (pos + 1) % capacity
            if (pos == 0) full = true
        }
    }

    @Synchronized
    fun snap(): ShortArray {
        if (!full) return data.copyOf(pos)
        val out = ShortArray(capacity)
        val tail = capacity - pos
        System.arraycopy(data, pos, out, 0, tail)
        System.arraycopy(data, 0, out, tail, pos)
        return out
    }
}

object VoskModelManager {
    private const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val DIR = "vosk-model-small-en-us-0.15"
    private val client = OkHttpClient()

    fun path(c: Context) = File(c.filesDir, "models/$DIR")
    fun ready(c: Context) = File(path(c), "am/final.mdl").exists()

    fun ensure(c: Context, status: (String) -> Unit) {
        if (ready(c)) {
            status("ready")
            return
        }
        status("downloading…")
        client.newCall(Request.Builder().url(URL).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                status("download failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        status("server ${r.code}")
                        return
                    }
                    val base = File(c.filesDir, "models").apply { mkdirs() }
                    val zip = File(base, "model.zip")
                    FileOutputStream(zip).use { out -> r.body?.byteStream()?.copyTo(out) }
                    status("unpacking…")
                    ZipInputStream(zip.inputStream().buffered()).use { input ->
                        var entry = input.nextEntry
                        while (entry != null) {
                            val f = File(base, entry.name)
                            if (entry.isDirectory) {
                                f.mkdirs()
                            } else {
                                f.parentFile?.mkdirs()
                                f.outputStream().use { out -> input.copyTo(out) }
                            }
                            input.closeEntry()
                            entry = input.nextEntry
                        }
                    }
                    zip.delete()
                    status(if (ready(c)) "ready" else "unpack incomplete")
                }
            }
        })
    }
}

object SpeechDecoder {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var model: Model? = null

    private data class Candidate(val text: String, val score: Double, val pass: String)

    fun decode(c: Context, pcm: ShortArray, rate: Int, done: (List<String>) -> Unit) {
        if (!VoskModelManager.ready(c)) {
            done(emptyList())
            return
        }
        executor.execute {
            try {
                if (model == null) model = Model(VoskModelManager.path(c).absolutePath)
                val mdl = model ?: run {
                    done(emptyList())
                    return@execute
                }
                val configs = listOf(
                    Triple("forward", 1.0, false),
                    Triple("slow 0.5×", 0.5, false),
                    Triple("slow 0.75×", 0.75, false),
                    Triple("fast 1.5×", 1.5, false),
                    Triple("fast 2×", 2.0, false),
                    Triple("reversed", 1.0, true)
                )
                val all = mutableListOf<Candidate>()

                for ((name, speed, reversed) in configs) {
                    val transformed = transform(pcm, rate, speed, reversed)
                    val recognizer = Recognizer(mdl, 16_000f)
                    runCatching { recognizer.setMaxAlternatives(5) }
                    val buffer = ByteBuffer.allocate(transformed.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    transformed.forEach { buffer.putShort(it) }
                    val bytes = buffer.array()
                    recognizer.acceptWaveForm(bytes, bytes.size)
                    val json = JSONObject(recognizer.finalResult)
                    val alternatives = json.optJSONArray("alternatives")
                    if (alternatives != null) {
                        for (i in 0 until alternatives.length()) {
                            val obj = alternatives.optJSONObject(i) ?: continue
                            val text = obj.optString("text", "").trim()
                            if (text.isNotBlank()) all += Candidate(text, obj.optDouble("confidence", 0.0), name)
                        }
                    } else {
                        val text = json.optString("text", "").trim()
                        if (text.isNotBlank()) all += Candidate(text, 0.0, name)
                    }
                    recognizer.close()
                }

                val kept = mutableListOf<Candidate>()
                for (candidate in all.sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.text.length })) {
                    if (kept.none { similarity(it.text, candidate.text) > 0.88 }) kept += candidate
                    if (kept.size >= 10) break
                }
                done(kept.mapIndexed { index, v ->
                    val confidence = when {
                        v.score >= 0.90 -> "high"
                        v.score >= 0.65 -> "medium"
                        v.score > 0.0 -> "low"
                        else -> "uncertain"
                    }
                    "${if (index == 0) "BEST" else "ALT $index"} [$confidence, ${v.pass}]: ${v.text}"
                })
            } catch (_: Throwable) {
                done(emptyList())
            }
        }
    }

    private fun transform(src: ShortArray, rate: Int, speed: Double, reversed: Boolean): ShortArray {
        val input = if (reversed) src.reversedArray() else src
        if (input.isEmpty()) return ShortArray(0)
        val outLen = ((input.size.toDouble() / rate) * 16_000.0 / speed).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in out.indices) {
            val pos = i * speed * rate / 16_000.0
            val a = pos.toInt().coerceIn(0, input.lastIndex)
            val b = (a + 1).coerceAtMost(input.lastIndex)
            val fraction = pos - a
            out[i] = (input[a] * (1.0 - fraction) + input[b] * fraction).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun similarity(a: String, b: String): Double {
        val x = a.lowercase()
        val y = b.lowercase()
        if (x == y) return 1.0
        val prev = IntArray(y.length + 1) { it }
        val cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            for (j in prev.indices) prev[j] = cur[j]
        }
        return 1.0 - prev[y.length].toDouble() / max(1, max(x.length, y.length))
    }
}

object AudioFileDecoder {
    data class D(val pcm: ShortArray, val rate: Int)

    fun decode(c: Context, u: Uri): D? {
        val extractor = MediaExtractor()
        extractor.setDataSource(c, u, null)
        var trackIndex = -1
        var selected: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                selected = f
                break
            }
        }
        val format = selected
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return null
        }
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
            extractor.release()
            return null
        }
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        while (!outputDone) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index) ?: continue
                    val n = extractor.readSampleData(input, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex >= 0 -> {
                    codec.getOutputBuffer(outIndex)?.let { buffer ->
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(bytes)
                        out.write(bytes)
                    }
                    outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outIndex, false)
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
            }
        }
        codec.stop()
        codec.release()
        extractor.release()

        val bytes = out.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val pcm = ShortArray(bytes.size / 2)
        var count = 0
        while (bb.remaining() >= 2 && count < pcm.size) pcm[count++] = bb.short
        return D(if (count == pcm.size) pcm else pcm.copyOf(count), sampleRate)
    }
}
