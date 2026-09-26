package com.vaan.behindthecurtain.session;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    public static final String ACTION_START = "btc.session.START";
    public static final String ACTION_STOP = "btc.session.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL = "btc_capture";
    private static final int NOTIFICATION_ID = 1401;
    private static final int SAMPLE_RATE = 48000;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    private SessionStore store;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread screenThread;
    private Handler screenHandler;
    private VisualAnalyzer visualAnalyzer;
    private long lastFrameMs = 0;
    private int visualIndex = 0;

    private Thread audioThread;
    private AudioRecord audioRecord;
    private WavFile.StreamWriter wavWriter;
    private File audioEvents;
    private long audioSamples = 0;
    private long lastAudioEventMs = 0;
    private String audioSource = "unknown";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCaptureAndAnalyze("user_stop");
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || running.get()) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startAsForeground();
        store = SessionStore.create(this);
        SessionStore.appendText(new File(store.session, "session_info.txt"),
                "Capture process started: " + new Date() + "\n" +
                "Screen collector: max 720px wide, about 4 analysis frames/second, saves candidates only\n" +
                "Audio collector: 48 kHz mono PCM; playback capture first, microphone fallback\n");

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            SessionStore.appendText(new File(store.session, "session_info.txt"), "MediaProjection creation failed.\n");
            stopSelf();
            return START_NOT_STICKY;
        }

        running.set(true);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (running.get()) main.post(() -> stopCaptureAndAnalyze("projection_stopped"));
            }
        }, main);

        try {
            startScreenCapture();
        } catch (Throwable t) {
            SessionStore.appendText(new File(store.session, "session_info.txt"), "Screen collector error: " + t + "\n");
        }
        try {
            startAudioCapture();
        } catch (Throwable t) {
            SessionStore.appendText(new File(store.session, "session_info.txt"), "Audio collector error: " + t + "\n");
        }
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Behind the Curtain session live")
                .setContentText("Collecting screen candidates and audio with low overhead")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel c = new NotificationChannel(CHANNEL, "Behind the Curtain capture", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Active Behind the Curtain session capture");
        nm.createNotificationChannel(c);
    }

    private void startScreenCapture() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);

        int sourceW = Math.max(1, dm.widthPixels);
        int sourceH = Math.max(1, dm.heightPixels);
        int captureW = Math.min(720, sourceW);
        int captureH = Math.max(1, Math.round(sourceH * captureW / (float) sourceW));

        screenThread = new HandlerThread("btc-screen");
        screenThread.start();
        screenHandler = new Handler(screenThread.getLooper());
        visualAnalyzer = new VisualAnalyzer();

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null || !running.get()) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastFrameMs < 240) return;
                lastFrameMs = now;

                Bitmap bitmap = imageToBitmap(image, captureW, captureH);
                if (bitmap == null) return;
                VisualAnalyzer.Result result = visualAnalyzer.analyze(bitmap, now);
                if (result.save) saveVisualCandidate(bitmap, result);
                bitmap.recycle();
                if (result.trace != null) result.trace.recycle();
                if (result.reveal != null) result.reveal.recycle();
            } catch (Throwable t) {
                SessionStore.appendText(new File(store.session, "collector_errors.txt"), "visual: " + t + "\n");
            } finally {
                if (image != null) image.close();
            }
        }, screenHandler);

        virtualDisplay = projection.createVirtualDisplay(
                "BehindTheCurtainSession",
                captureW,
                captureH,
                dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                screenHandler);
    }

    private Bitmap imageToBitmap(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        int paddedWidth = width + rowPadding / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private void saveVisualCandidate(Bitmap original, VisualAnalyzer.Result result) {
        int id = ++visualIndex;
        String base = String.format(Locale.US, "event_%06d", id);
        File originalFile = new File(store.visual, base + "_original.png");
        File traceFile = new File(store.visual, base + "_trace.png");
        File revealFile = new File(store.visual, base + "_reveal.png");
        File noteFile = new File(store.visual, base + "_note.txt");
        try {
            try (FileOutputStream out = new FileOutputStream(originalFile)) {
                original.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            if (result.trace != null) {
                try (FileOutputStream out = new FileOutputStream(traceFile)) {
                    result.trace.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }
            if (result.reveal != null) {
                try (FileOutputStream out = new FileOutputStream(revealFile)) {
                    result.reveal.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }
            SessionStore.writeText(noteFile,
                    "Captured: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "\n" +
                    result.note() +
                    "OCR and text description are appended during post-session analysis.\n");
        } catch (Throwable t) {
            SessionStore.appendText(new File(store.session, "collector_errors.txt"), "save visual: " + t + "\n");
        }
    }

    private void startAudioCapture() throws Exception {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(min, 8192);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();

        try {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            audioRecord = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setAudioPlaybackCaptureConfig(config)
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("playback AudioRecord init failed");
            audioSource = "internal_playback_capture";
        } catch (Throwable playbackError) {
            if (audioRecord != null) {
                try { audioRecord.release(); } catch (Throwable ignored) {}
            }
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("microphone AudioRecord init failed");
            audioSource = "microphone_fallback";
        }

        File wav = new File(store.audio, "original.wav");
        wavWriter = new WavFile.StreamWriter(wav, SAMPLE_RATE);
        audioEvents = new File(store.audio, "audio_events.csv");
        SessionStore.writeText(audioEvents, "seconds,rms_dbfs,event,detail\n");
        SessionStore.appendText(new File(store.session, "session_info.txt"), "Audio source: " + audioSource + "\n");

        audioRecord.startRecording();
        final int finalBufferBytes = bufferBytes;
        audioThread = new Thread(() -> audioLoop(finalBufferBytes), "btc-audio");
        audioThread.start();
    }

    private void audioLoop(int bufferBytes) {
        byte[] buf = new byte[bufferBytes];
        try {
            while (running.get()) {
                int n = audioRecord.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                if (n <= 0) continue;
                wavWriter.write(buf, 0, n);
                audioSamples += n / 2;
                inspectAudio(buf, n);
            }
        } catch (Throwable t) {
            SessionStore.appendText(new File(store.session, "collector_errors.txt"), "audio: " + t + "\n");
        } finally {
            try { wavWriter.close(); } catch (Throwable ignored) {}
            wavWriter = null;
        }
    }

    private void inspectAudio(byte[] data, int len) {
        int samples = len / 2;
        if (samples < 64) return;
        double sumSq = 0.0;
        for (int i = 0; i < samples; i++) {
            int lo = data[i * 2] & 0xff;
            int hi = data[i * 2 + 1];
            short s = (short) (lo | (hi << 8));
            double v = s / 32768.0;
            sumSq += v * v;
        }
        double rmsPower = sumSq / samples;
        double rms = Math.sqrt(Math.max(1e-12, rmsPower));
        double db = 20.0 * Math.log10(rms);

        double high = Math.max(goertzel(data, samples, 18500), Math.max(goertzel(data, samples, 20500), goertzel(data, samples, 22500)));
        double low = Math.max(goertzel(data, samples, 12), Math.max(goertzel(data, samples, 18), goertzel(data, samples, 30)));
        double highRatio = high / Math.max(1e-12, rmsPower);
        double lowRatio = low / Math.max(1e-12, rmsPower);

        String event = null;
        String detail = null;
        if (highRatio > 0.30 && db > -75) {
            event = "high_frequency_energy_candidate";
            detail = String.format(Locale.US, "ratio=%.4f", highRatio);
        } else if (lowRatio > 0.40 && db > -75) {
            event = "very_low_frequency_energy_candidate";
            detail = String.format(Locale.US, "ratio=%.4f", lowRatio);
        } else if (db >= -68 && db <= -38) {
            event = "low_level_audio_candidate";
            detail = "quiet signal band";
        }

        long now = SystemClock.elapsedRealtime();
        if (event != null && now - lastAudioEventMs > 1800) {
            lastAudioEventMs = now;
            double sec = audioSamples / (double) SAMPLE_RATE;
            SessionStore.appendText(audioEvents,
                    String.format(Locale.US, "%.3f,%.2f,%s,%s\n", sec, db, event, detail));
        }
    }

    private double goertzel(byte[] data, int samples, double freq) {
        double w = 2.0 * Math.PI * freq / SAMPLE_RATE;
        double coeff = 2.0 * Math.cos(w);
        double s0;
        double s1 = 0.0;
        double s2 = 0.0;
        for (int i = 0; i < samples; i++) {
            int lo = data[i * 2] & 0xff;
            int hi = data[i * 2 + 1];
            short q = (short) (lo | (hi << 8));
            double x = q / 32768.0;
            s0 = x + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        return Math.max(0.0, power) / (samples * (double) samples);
    }

    private synchronized void stopCaptureAndAnalyze(String reason) {
        if (!running.getAndSet(false)) {
            stopSelf();
            return;
        }
        if (store != null) SessionStore.appendText(new File(store.session, "session_info.txt"), "Capture stopped: " + new Date() + " reason=" + reason + "\n");

        try { if (audioRecord != null) audioRecord.stop(); } catch (Throwable ignored) {}
        if (audioThread != null) {
            try { audioThread.join(2500); } catch (InterruptedException ignored) {}
        }
        try { if (audioRecord != null) audioRecord.release(); } catch (Throwable ignored) {}
        audioRecord = null;

        try { if (imageReader != null) imageReader.setOnImageAvailableListener(null, null); } catch (Throwable ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Throwable ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Throwable ignored) {}
        virtualDisplay = null;
        imageReader = null;

        if (screenThread != null) {
            try { screenThread.quitSafely(); } catch (Throwable ignored) {}
            screenThread = null;
            screenHandler = null;
        }

        try { if (projection != null) projection.stop(); } catch (Throwable ignored) {}
        projection = null;

        if (store != null) {
            Intent analyze = new Intent(this, AnalysisService.class);
            analyze.setAction(AnalysisService.ACTION_ANALYZE);
            analyze.putExtra(AnalysisService.EXTRA_SESSION_PATH, store.session.getAbsolutePath());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(analyze); else startService(analyze);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (running.get()) stopCaptureAndAnalyze("service_destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
