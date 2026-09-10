package com.vhanma.omegaechoframe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;

import java.util.Arrays;

public final class AcousticEngine {
    public interface Listener {
        void onFrame(Frame frame);
        void onState(String state);
        void onError(String error);
    }

    public static final class Frame {
        public final float[] raw;
        public final float[] dynamic;
        public final float rangeMeters;
        public final float radialVelocity;
        public final float microMm;
        public final float confidence;
        public final float link;
        public final float micRms;
        public final int bandLow;
        public final int bandHigh;
        public final boolean calibrating;
        public final int warmupPercent;

        Frame(float[] raw, float[] dynamic, float rangeMeters, float radialVelocity,
              float microMm, float confidence, float link, float micRms,
              int bandLow, int bandHigh, boolean calibrating, int warmupPercent) {
            this.raw = raw;
            this.dynamic = dynamic;
            this.rangeMeters = rangeMeters;
            this.radialVelocity = radialVelocity;
            this.microMm = microMm;
            this.confidence = confidence;
            this.link = link;
            this.micRms = micRms;
            this.bandLow = bandLow;
            this.bandHigh = bandHigh;
            this.calibrating = calibrating;
            this.warmupPercent = warmupPercent;
        }
    }

    public static final int SAMPLE_RATE = 48000;
    public static final int CHIRP_SAMPLES = 512;
    public static final int PULSE_PERIOD = 2048;
    public static final int READ_SAMPLES = 8192;
    public static final int MIN_ECHO_SAMPLES = 32;
    public static final int BIN_STEP_SAMPLES = 8;
    public static final int BINS = 150;
    public static final float MAX_RANGE_METERS =
            (MIN_ECHO_SAMPLES + (BINS - 1) * BIN_STEP_SAMPLES) * 343.0f / (2.0f * SAMPLE_RATE);

    private static final int[][] BANDS = new int[][]{
            {18000, 21000}, {17000, 20000}, {16000, 19000}
    };

    private final Context context;
    private final Listener listener;
    private volatile boolean running;
    private volatile short[] txPattern;
    private volatile float[] chirpI;
    private volatile float[] chirpQ;
    private volatile float refEnergy;
    private volatile int bandIndex = 0;
    private volatile float outputPower = 0.18f;
    private volatile float sensitivity = 4.5f;

    private AudioTrack track;
    private AudioRecord record;
    private Thread txThread;
    private Thread rxThread;

    private final float[] baseline = new float[BINS];
    private boolean baselineReady;
    private int warmFrames;
    private float previousRange = -1f;
    private long previousTimeNs;
    private int previousPeak = -1;
    private double previousRelativePhase = Double.NaN;
    private float microPositionMm;

    private volatile boolean calibrating;
    private int calBand;
    private int calFrames;
    private final double[] calScores = new double[BANDS.length];

    public AcousticEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        rebuildSignal();
    }

    public synchronized void start() {
        if (running) return;
        try {
            int minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minOut <= 0 || minIn <= 0) throw new IllegalStateException("48 kHz audio path unavailable");

            AudioFormat outFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(outFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minOut, PULSE_PERIOD * 8))
                    .build();

            record = buildRecorder(minIn);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Microphone initialization failed");
            }

            running = true;
            record.startRecording();
            track.play();
            txThread = new Thread(this::txLoop, "OmegaEcho-TX");
            rxThread = new Thread(this::rxLoop, "OmegaEcho-RX");
            txThread.start();
            rxThread.start();
            listener.onState("ACTIVE • acoustic pulse train locked");
        } catch (Throwable t) {
            running = false;
            releaseAudio();
            listener.onError(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private AudioRecord buildRecorder(int minIn) {
        int source = MediaRecorder.AudioSource.UNPROCESSED;
        try {
            return new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minIn * 2, READ_SAMPLES * 4))
                    .build();
        } catch (Throwable first) {
            return new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minIn * 2, READ_SAMPLES * 4))
                    .build();
        }
    }

    public synchronized void stop() {
        running = false;
        releaseAudio();
        listener.onState("STANDBY");
    }

    private void releaseAudio() {
        try { if (record != null) record.stop(); } catch (Throwable ignored) {}
        try { if (track != null) track.stop(); } catch (Throwable ignored) {}
        try { if (record != null) record.release(); } catch (Throwable ignored) {}
        try { if (track != null) track.release(); } catch (Throwable ignored) {}
        record = null;
        track = null;
    }

    public boolean isRunning() { return running; }

    public void setSensitivity(float value) {
        sensitivity = Math.max(1.0f, Math.min(12.0f, value));
    }

    public void setOutputPower(float value) {
        outputPower = Math.max(0.03f, Math.min(0.35f, value));
        rebuildSignal();
    }

    public int getBandIndex() { return bandIndex; }

    public void setBandIndex(int index) {
        bandIndex = ((index % BANDS.length) + BANDS.length) % BANDS.length;
        calibrating = false;
        rebuildSignal();
        resetBaseline();
        listener.onState("BAND • " + BANDS[bandIndex][0] / 1000.0f + "–" + BANDS[bandIndex][1] / 1000.0f + " kHz");
    }

    public void resetBaseline() {
        Arrays.fill(baseline, 0f);
        baselineReady = false;
        warmFrames = 0;
        previousRange = -1f;
        previousPeak = -1;
        previousRelativePhase = Double.NaN;
        microPositionMm = 0f;
    }

    public void autoCalibrate() {
        calibrating = true;
        calBand = 0;
        calFrames = 0;
        Arrays.fill(calScores, 0.0);
        bandIndex = 0;
        rebuildSignal();
        resetBaseline();
        listener.onState("AUTO CAL • testing near-ultrasonic bands");
    }

    private synchronized void rebuildSignal() {
        int low = BANDS[bandIndex][0];
        int high = BANDS[bandIndex][1];
        float[] iRef = new float[CHIRP_SAMPLES];
        float[] qRef = new float[CHIRP_SAMPLES];
        short[] pattern = new short[PULSE_PERIOD];
        double duration = CHIRP_SAMPLES / (double) SAMPLE_RATE;
        double slope = (high - low) / duration;
        float energy = 0f;
        for (int n = 0; n < CHIRP_SAMPLES; n++) {
            double t = n / (double) SAMPLE_RATE;
            double phase = 2.0 * Math.PI * (low * t + 0.5 * slope * t * t);
            double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / (CHIRP_SAMPLES - 1.0));
            float c = (float) (Math.cos(phase) * window);
            float s = (float) (Math.sin(phase) * window);
            iRef[n] = c;
            qRef[n] = s;
            energy += c * c;
            int sample = Math.round(c * outputPower * 32767f);
            pattern[n] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
        }
        chirpI = iRef;
        chirpQ = qRef;
        refEnergy = Math.max(1e-6f, energy);
        txPattern = pattern;
    }

    private void txLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        while (running) {
            try {
                short[] local = txPattern;
                AudioTrack t = track;
                if (t == null) break;
                int written = t.write(local, 0, local.length, AudioTrack.WRITE_BLOCKING);
                if (written < 0) throw new IllegalStateException("AudioTrack write " + written);
            } catch (Throwable e) {
                if (running) listener.onError("TX: " + e.getMessage());
                break;
            }
        }
    }

    private void rxLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] input = new short[READ_SAMPLES];
        while (running) {
            try {
                AudioRecord r = record;
                if (r == null) break;
                int got = r.read(input, 0, input.length, AudioRecord.READ_BLOCKING);
                if (got > CHIRP_SAMPLES + 1400) process(input, got);
                else if (got < 0) throw new IllegalStateException("AudioRecord read " + got);
            } catch (Throwable e) {
                if (running) listener.onError("RX: " + e.getMessage());
                break;
            }
        }
    }

    private void process(short[] input, int n) {
        float[] ref = chirpI;
        float[] quad = chirpQ;
        float localEnergy = refEnergy;
        if (ref == null || quad == null) return;

        final int maxEcho = MIN_ECHO_SAMPLES + (BINS - 1) * BIN_STEP_SAMPLES;
        int searchEnd = n - CHIRP_SAMPLES - maxEcho - 2;
        if (searchEnd <= 8) return;

        int bestLag = 0;
        float best = 0f;
        for (int lag = 0; lag < searchEnd; lag += 4) {
            float c = normalizedCorrelation(input, lag, ref, localEnergy);
            float a = Math.abs(c);
            if (a > best) { best = a; bestLag = lag; }
        }
        int refineStart = Math.max(0, bestLag - 4);
        int refineEnd = Math.min(searchEnd, bestLag + 5);
        for (int lag = refineStart; lag < refineEnd; lag++) {
            float c = normalizedCorrelation(input, lag, ref, localEnergy);
            float a = Math.abs(c);
            if (a > best) { best = a; bestLag = lag; }
        }

        float[] raw = new float[BINS];
        float[] dyn = new float[BINS];
        float meanDelta = 0f;
        for (int b = 0; b < BINS; b++) {
            int lag = bestLag + MIN_ECHO_SAMPLES + b * BIN_STEP_SAMPLES;
            float c = Math.abs(normalizedCorrelation(input, lag, ref, localEnergy));
            raw[b] = clamp(c / Math.max(0.08f, best * 0.72f), 0f, 1f);
        }

        if (!baselineReady) {
            if (warmFrames == 0) System.arraycopy(raw, 0, baseline, 0, BINS);
            else for (int b = 0; b < BINS; b++) baseline[b] = baseline[b] * 0.88f + raw[b] * 0.12f;
            warmFrames++;
            if (warmFrames >= 18) baselineReady = true;
        }

        float peak = 0f;
        int peakBin = -1;
        for (int b = 0; b < BINS; b++) {
            float delta = Math.abs(raw[b] - baseline[b]);
            float score = clamp(delta * sensitivity, 0f, 1f);
            dyn[b] = score;
            meanDelta += score;
            if (b > 2 && score > peak) { peak = score; peakBin = b; }
            if (baselineReady) {
                float alpha = score > 0.25f ? 0.0015f : 0.004f;
                baseline[b] = baseline[b] * (1f - alpha) + raw[b] * alpha;
            }
        }
        meanDelta /= BINS;

        long now = System.nanoTime();
        float range = -1f;
        float velocity = 0f;
        float confidence = 0f;
        if (peakBin >= 0 && peak > 0.06f) {
            int echoSamples = MIN_ECHO_SAMPLES + peakBin * BIN_STEP_SAMPLES;
            range = echoSamples * 343.0f / (2.0f * SAMPLE_RATE);
            confidence = clamp(peak / Math.max(0.025f, meanDelta * 2.5f), 0f, 1f);
            if (previousRange > 0f && previousTimeNs != 0L) {
                float dt = (now - previousTimeNs) / 1_000_000_000f;
                if (dt > 0.01f && dt < 0.8f && Math.abs(range - previousRange) < 0.65f) {
                    velocity = (range - previousRange) / dt;
                    velocity = clamp(velocity, -4f, 4f);
                }
            }
        }

        float microMm = microPositionMm;
        if (peakBin >= 0 && confidence > 0.12f) {
            int targetLag = bestLag + MIN_ECHO_SAMPLES + peakBin * BIN_STEP_SAMPLES;
            double directPhase = phaseAt(input, bestLag, ref, quad);
            double targetPhase = phaseAt(input, targetLag, ref, quad);
            double relative = wrapPi(targetPhase - directPhase);
            if (!Double.isNaN(previousRelativePhase) && previousPeak >= 0 && Math.abs(peakBin - previousPeak) <= 2) {
                double d = wrapPi(relative - previousRelativePhase);
                double centerHz = (BANDS[bandIndex][0] + BANDS[bandIndex][1]) * 0.5;
                double lambda = 343.0 / centerHz;
                float deltaMm = (float) (d * lambda * 1000.0 / (4.0 * Math.PI));
                if (Math.abs(deltaMm) < 4.0f) microPositionMm = clamp(microPositionMm + deltaMm, -60f, 60f);
            } else {
                microPositionMm *= 0.5f;
            }
            previousRelativePhase = relative;
            microMm = microPositionMm;
        } else {
            microPositionMm *= 0.94f;
            previousRelativePhase = Double.NaN;
            microMm = microPositionMm;
        }

        previousPeak = peakBin;
        if (range > 0f) previousRange = range;
        previousTimeNs = now;

        if (calibrating) handleCalibration(best);

        float rms = rms(input, n);
        int warm = baselineReady ? 100 : Math.min(99, (int) (warmFrames * 100f / 18f));
        listener.onFrame(new Frame(raw, dyn, range, velocity, microMm, confidence,
                clamp(best, 0f, 1f), rms, BANDS[bandIndex][0], BANDS[bandIndex][1], calibrating, warm));
    }

    private void handleCalibration(float link) {
        if (!calibrating) return;
        calScores[calBand] += link;
        calFrames++;
        if (calFrames < 12) return;
        calScores[calBand] /= calFrames;
        if (calBand < BANDS.length - 1) {
            calBand++;
            calFrames = 0;
            bandIndex = calBand;
            rebuildSignal();
            resetBaseline();
            listener.onState("AUTO CAL • probing " + BANDS[bandIndex][0] / 1000.0f + "–" + BANDS[bandIndex][1] / 1000.0f + " kHz");
        } else {
            int bestBand = 0;
            for (int i = 1; i < calScores.length; i++) if (calScores[i] > calScores[bestBand]) bestBand = i;
            bandIndex = bestBand;
            calibrating = false;
            rebuildSignal();
            resetBaseline();
            listener.onState("CAL LOCK • " + BANDS[bestBand][0] / 1000.0f + "–" + BANDS[bestBand][1] / 1000.0f + " kHz");
        }
    }

    private static float normalizedCorrelation(short[] x, int lag, float[] ref, float refEnergy) {
        double dot = 0.0;
        double ex = 1e-9;
        int len = ref.length;
        for (int i = 0; i < len; i++) {
            float xv = x[lag + i] / 32768f;
            dot += xv * ref[i];
            ex += xv * xv;
        }
        return (float) (dot / Math.sqrt(ex * refEnergy + 1e-12));
    }

    private static double phaseAt(short[] x, int lag, float[] iRef, float[] qRef) {
        double i = 0.0;
        double q = 0.0;
        for (int n = 0; n < iRef.length; n++) {
            double v = x[lag + n] / 32768.0;
            i += v * iRef[n];
            q += v * qRef[n];
        }
        return Math.atan2(q, i);
    }

    private static double wrapPi(double x) {
        while (x > Math.PI) x -= Math.PI * 2.0;
        while (x < -Math.PI) x += Math.PI * 2.0;
        return x;
    }

    private static float rms(short[] x, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            double v = x[i] / 32768.0;
            sum += v * v;
        }
        return (float) Math.sqrt(sum / Math.max(1, n));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
