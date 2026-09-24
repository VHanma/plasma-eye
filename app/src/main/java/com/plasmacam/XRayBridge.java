package com.plasmacam;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Behind the Curtain launcher plus its user-authorized MediaProjection scanner.
 * Nothing starts until Android's screen-capture confirmation is accepted.
 */
public class XRayBridge extends Activity {
    private static final int REQ_CAPTURE = 7001;
    private static final int REQ_AUDIO = 7002;
    private static final int REQ_OVERLAY = 7003;

    private TextView status;
    private SeekBar sensitivity;
    private boolean waitingForOverlay = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(36, 56, 36, 36);
        root.setBackgroundColor(Color.rgb(4, 6, 10));

        TextView title = text("BEHIND THE CURTAIN", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = text("LIVE SCREEN + AUDIO FORENSICS", 13, Color.rgb(0, 235, 210));
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.setMargins(0, 10, 0, 38);
        root.addView(sub, subLp);

        status = text("READY • scan stays on-device", 14, Color.LTGRAY);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.setMargins(0, 0, 0, 22);
        root.addView(status, statusLp);

        TextView sensLabel = text("DETECTION SENSITIVITY", 12, Color.GRAY);
        root.addView(sensLabel, new LinearLayout.LayoutParams(-1, -2));

        sensitivity = new SeekBar(this);
        sensitivity.setMax(100);
        sensitivity.setProgress(66);
        root.addView(sensitivity, new LinearLayout.LayoutParams(-1, -2));
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Intent i = new Intent(XRayBridge.this, ScanService.class);
                    i.setAction(ScanService.ACTION_SENSITIVITY);
                    i.putExtra(ScanService.EXTRA_SENSITIVITY, progress);
                    startServiceCompat(i, false);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        Button start = button("START LIVE SCREEN SCAN");
        start.setOnClickListener(v -> beginPermissionFlow());
        LinearLayout.LayoutParams btn = new LinearLayout.LayoutParams(-1, dp(54));
        btn.setMargins(0, 24, 0, 12);
        root.addView(start, btn);

        Button stop = button("STOP SCAN");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, ScanService.class);
            i.setAction(ScanService.ACTION_STOP);
            startServiceCompat(i, false);
            status.setText("STOPPED");
        });
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(-1, dp(50));
        stopLp.setMargins(0, 0, 0, 12);
        root.addView(stop, stopLp);

        Button camera = button("CAMERA FORENSICS");
        camera.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(camera, new LinearLayout.LayoutParams(-1, dp(50)));

        TextView note = text(
                "Screen Scan follows whatever is visible while you scroll or play media. " +
                "The overlay marks candidate low-contrast, micro-structure, chroma and temporal regions. " +
                "Playback audio analysis runs when the source app allows Android playback capture.",
                12, Color.rgb(150, 158, 170));
        note.setPadding(0, 28, 0, 0);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private int dp(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void beginPermissionFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            waitingForOverlay = true;
            Intent overlay = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(overlay, REQ_OVERLAY);
            status.setText("ALLOW DISPLAY OVER OTHER APPS • then return");
            return;
        }
        requestAudioThenCapture();
    }

    private void requestAudioThenCapture() {
        if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        MediaProjectionManager mpm = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            status.setText("MEDIA PROJECTION UNAVAILABLE");
            return;
        }
        status.setText("CONFIRM SCREEN ACCESS");
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForOverlay && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this))) {
            waitingForOverlay = false;
            requestAudioThenCapture();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) requestProjection();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, ScanService.class);
                service.setAction(ScanService.ACTION_START);
                service.putExtra(ScanService.EXTRA_RESULT_CODE, resultCode);
                service.putExtra(ScanService.EXTRA_DATA, data);
                service.putExtra(ScanService.EXTRA_SENSITIVITY, sensitivity.getProgress());
                startServiceCompat(service, true);
                status.setText("LIVE • switch to any app and keep scrolling");
            } else {
                status.setText("SCREEN ACCESS NOT STARTED");
            }
        }
    }

    private void startServiceCompat(Intent intent, boolean foreground) {
        if (foreground && Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    public static final class Hit {
        public final Rect rect;
        public final int score;
        public final String kind;

        Hit(Rect rect, int score, String kind) {
            this.rect = rect;
            this.score = score;
            this.kind = kind;
        }
    }

    public static class ScanService extends Service {
        static final String ACTION_START = "btc.START";
        static final String ACTION_STOP = "btc.STOP";
        static final String ACTION_SENSITIVITY = "btc.SENS";
        static final String EXTRA_RESULT_CODE = "resultCode";
        static final String EXTRA_DATA = "projectionData";
        static final String EXTRA_SENSITIVITY = "sensitivity";

        private static final int NOTIFICATION_ID = 3917;
        private static final String CHANNEL = "behind_the_curtain_scan";

        private MediaProjection projection;
        private VirtualDisplay virtualDisplay;
        private ImageReader imageReader;
        private HandlerThread captureThread;
        private Handler captureHandler;
        private long lastFrameMs = 0L;
        private int screenW, screenH, density;
        private int sensitivity = 66;
        private int[] previousGray;

        private WindowManager windowManager;
        private XRayVisionView overlayView;

        private AudioRecord audioRecord;
        private Thread audioThread;
        private volatile boolean audioRunning = false;

        @Override public void onCreate() {
            super.onCreate();
            createNotificationChannel();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent == null) return START_NOT_STICKY;
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopEverything();
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_SENSITIVITY.equals(action)) {
                sensitivity = intent.getIntExtra(EXTRA_SENSITIVITY, sensitivity);
                if (overlayView != null) overlayView.setSensitivity(sensitivity);
                return START_STICKY;
            }
            if (ACTION_START.equals(action)) {
                sensitivity = intent.getIntExtra(EXTRA_SENSITIVITY, sensitivity);
                int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                Intent data = intent.getParcelableExtra(EXTRA_DATA);
                if (resultCode == Activity.RESULT_OK && data != null) startProjection(resultCode, data);
            }
            return START_STICKY;
        }

        private void startProjection(int resultCode, Intent data) {
            if (projection != null) return;
            startForeground(NOTIFICATION_ID, buildNotification("Scanning screen + audio"));

            windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            if (windowManager == null) return;
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
            density = dm.densityDpi;

            addOverlay();

            captureThread = new HandlerThread("BTC-ScreenScan");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());

            MediaProjectionManager mpm = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) return;
            projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) return;
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, captureHandler);

            imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(this::onImage, captureHandler);
            virtualDisplay = projection.createVirtualDisplay(
                    "BehindTheCurtain",
                    screenW, screenH, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, captureHandler);

            startPlaybackAudio();
        }

        private void addOverlay() {
            if (windowManager == null) return;
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
            overlayView = new XRayVisionView(this);
            overlayView.setSensitivity(sensitivity);
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_SECURE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    flags,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(overlayView, lp);
        }

        private void onImage(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                long now = System.currentTimeMillis();
                if (now - lastFrameMs < 220L) return;
                lastFrameMs = now;

                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();
                int pixelStride = plane.getPixelStride();
                int rowStride = plane.getRowStride();
                int rowPadding = rowStride - pixelStride * screenW;
                int paddedW = screenW + rowPadding / pixelStride;

                Bitmap padded = Bitmap.createBitmap(paddedW, screenH, Bitmap.Config.ARGB_8888);
                padded.copyPixelsFromBuffer(buffer);
                Bitmap frame = Bitmap.createBitmap(padded, 0, 0, screenW, screenH);
                if (frame != padded) padded.recycle();

                List<Hit> hits = analyze(frame);
                frame.recycle();
                if (overlayView != null) overlayView.setHits(hits, screenW, screenH);
            } catch (Throwable ignored) {
            } finally {
                image.close();
            }
        }

        private List<Hit> analyze(Bitmap frame) {
            int targetW = Math.min(300, frame.getWidth());
            int targetH = Math.max(1, Math.round(frame.getHeight() * (targetW / (float)frame.getWidth())));
            Bitmap small = Bitmap.createScaledBitmap(frame, targetW, targetH, false);
            int[] px = new int[targetW * targetH];
            small.getPixels(px, 0, targetW, 0, 0, targetW, targetH);
            if (small != frame) small.recycle();

            int n = px.length;
            int[] gray = new int[n];
            for (int i = 0; i < n; i++) {
                int c = px[i];
                int r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
                gray[i] = (r * 77 + g * 150 + b * 29) >>> 8;
            }

            long[] integral = integral(gray, targetW, targetH);
            int cell = 12;
            int gw = (targetW + cell - 1) / cell;
            int gh = (targetH + cell - 1) / cell;
            int[] low = new int[gw * gh];
            int[] micro = new int[gw * gh];
            int[] chroma = new int[gw * gh];
            int[] temporal = new int[gw * gh];
            int[] counts = new int[gw * gh];

            for (int y = 1; y < targetH - 1; y++) {
                for (int x = 1; x < targetW - 1; x++) {
                    int p = y * targetW + x;
                    int ci = (y / cell) * gw + (x / cell);
                    int local = mean(integral, targetW, targetH, x, y, 2);
                    int broad = mean(integral, targetW, targetH, x, y, 7);
                    int detail = Math.abs(gray[p] - local);
                    int scale = Math.abs(local - broad);
                    int edge = Math.abs(gray[p - 1] - gray[p + 1])
                            + Math.abs(gray[p - targetW] - gray[p + targetW]);
                    int c = px[p];
                    int r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
                    int colorSpread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                    int motion = previousGray != null && previousGray.length == gray.length
                            ? Math.abs(gray[p] - previousGray[p]) : 0;

                    low[ci] += Math.min(255, scale * 6 + detail * 2);
                    micro[ci] += Math.min(255, edge);
                    chroma[ci] += colorSpread;
                    temporal[ci] += motion;
                    counts[ci]++;
                }
            }

            previousGray = gray;
            int threshold = Math.max(34, 82 - sensitivity / 2);
            ArrayList<Hit> raw = new ArrayList<>();
            float sx = screenW / (float)targetW;
            float sy = screenH / (float)targetH;

            for (int cy = 0; cy < gh; cy++) {
                for (int cx = 0; cx < gw; cx++) {
                    int ci = cy * gw + cx;
                    int c = Math.max(1, counts[ci]);
                    int a = low[ci] / c;
                    int b = micro[ci] / c;
                    int ch = chroma[ci] / c;
                    int t = temporal[ci] / c;

                    int scoreLow = a;
                    int scoreMicro = b + Math.min(35, a / 3);
                    int scoreChroma = ch * 2 + a / 4;
                    int scoreTemporal = t * 5;

                    int score = scoreLow;
                    String kind = "LOW-CONTRAST";
                    if (scoreMicro > score) { score = scoreMicro; kind = "MICRO"; }
                    if (scoreChroma > score) { score = scoreChroma; kind = "CHROMA"; }
                    if (scoreTemporal > score) { score = scoreTemporal; kind = "TEMPORAL"; }
                    score = Math.min(100, score);
                    if (score < threshold) continue;

                    int l = Math.max(0, Math.round(cx * cell * sx));
                    int top = Math.max(0, Math.round(cy * cell * sy));
                    int r = Math.min(screenW, Math.round((cx + 1) * cell * sx));
                    int bottom = Math.min(screenH, Math.round((cy + 1) * cell * sy));
                    raw.add(new Hit(new Rect(l, top, r, bottom), score, kind));
                }
            }

            Collections.sort(raw, new Comparator<Hit>() {
                @Override public int compare(Hit o1, Hit o2) { return o2.score - o1.score; }
            });
            ArrayList<Hit> best = new ArrayList<>();
            for (Hit h : raw) {
                boolean clash = false;
                for (Hit b : best) {
                    Rect expanded = new Rect(b.rect);
                    expanded.inset(-24, -24);
                    if (Rect.intersects(expanded, h.rect)) { clash = true; break; }
                }
                if (!clash) best.add(h);
                if (best.size() >= 8) break;
            }
            return best;
        }

        private long[] integral(int[] gray, int w, int h) {
            int stride = w + 1;
            long[] ii = new long[(w + 1) * (h + 1)];
            for (int y = 1; y <= h; y++) {
                long row = 0;
                for (int x = 1; x <= w; x++) {
                    row += gray[(y - 1) * w + (x - 1)];
                    ii[y * stride + x] = ii[(y - 1) * stride + x] + row;
                }
            }
            return ii;
        }

        private int mean(long[] ii, int w, int h, int x, int y, int radius) {
            int x1 = Math.max(0, x - radius), y1 = Math.max(0, y - radius);
            int x2 = Math.min(w - 1, x + radius), y2 = Math.min(h - 1, y + radius);
            int stride = w + 1;
            long sum = ii[(y2 + 1) * stride + (x2 + 1)] - ii[y1 * stride + (x2 + 1)]
                    - ii[(y2 + 1) * stride + x1] + ii[y1 * stride + x1];
            return (int)(sum / Math.max(1, (x2 - x1 + 1) * (y2 - y1 + 1)));
        }

        private void startPlaybackAudio() {
            if (Build.VERSION.SDK_INT < 29 || projection == null) {
                if (overlayView != null) overlayView.setAudioStatus("AUDIO: MIC/PLAYBACK PATH LIMITED");
                return;
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                if (overlayView != null) overlayView.setAudioStatus("AUDIO: PERMISSION OFF");
                return;
            }
            try {
                AudioPlaybackCaptureConfiguration config =
                        new AudioPlaybackCaptureConfiguration.Builder(projection)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .build();
                int sampleRate = 48000;
                int channelMask = AudioFormat.CHANNEL_IN_STEREO;
                int min = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
                int bufferBytes = Math.max(min * 4, 32768);
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build();
                audioRecord = new AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferBytes)
                        .setAudioPlaybackCaptureConfig(config)
                        .build();
                audioRecord.startRecording();
                audioRunning = true;
                audioThread = new Thread(() -> audioLoop(sampleRate), "BTC-AudioScan");
                audioThread.start();
            } catch (Throwable e) {
                if (overlayView != null) overlayView.setAudioStatus("AUDIO: SOURCE BLOCKED CAPTURE");
            }
        }

        private void audioLoop(int sampleRate) {
            short[] stereo = new short[8192];
            while (audioRunning && audioRecord != null) {
                int read;
                try { read = audioRecord.read(stereo, 0, stereo.length); }
                catch (Throwable e) { break; }
                if (read <= 512) continue;

                int monoN = read / 2;
                short[] mono = new short[monoN];
                double sumSq = 0.0;
                for (int i = 0; i < monoN; i++) {
                    int a = stereo[i * 2];
                    int b = (i * 2 + 1 < read) ? stereo[i * 2 + 1] : a;
                    short v = (short)((a + b) / 2);
                    mono[i] = v;
                    double f = v / 32768.0;
                    sumSq += f * f;
                }
                double rms = Math.sqrt(sumSq / Math.max(1, monoN));
                double sub = bandProbe(mono, sampleRate, new double[]{8, 12, 16, 20});
                double high = bandProbe(mono, sampleRate, new double[]{17000, 18500, 20000, 21500, 23000});
                double voice = bandProbe(mono, sampleRate, new double[]{350, 700, 1200, 2200, 3200});

                String s;
                boolean alert = false;
                if (rms < 0.0005) s = "AUDIO: QUIET / NO CAPTURE";
                else if (high > voice * 1.8 && high > 0.000001) { s = "AUDIO ALERT: 17–23kHz ENERGY"; alert = true; }
                else if (sub > voice * 2.2 && sub > 0.000001) { s = "AUDIO ALERT: SUB-20Hz COMPONENT"; alert = true; }
                else s = String.format("AUDIO LIVE • level %.0f%%", Math.min(100.0, rms * 420.0));
                if (overlayView != null) overlayView.setAudioStatus(s, alert);
            }
        }

        private double bandProbe(short[] data, int sampleRate, double[] freqs) {
            double total = 0.0;
            for (double f : freqs) total += goertzel(data, sampleRate, f);
            return total / Math.max(1, freqs.length);
        }

        private double goertzel(short[] data, int sampleRate, double freq) {
            double omega = 2.0 * Math.PI * freq / sampleRate;
            double coeff = 2.0 * Math.cos(omega);
            double s0, s1 = 0.0, s2 = 0.0;
            int stride = Math.max(1, data.length / 4096);
            int used = 0;
            for (int i = 0; i < data.length; i += stride) {
                double x = data[i] / 32768.0;
                s0 = x + coeff * s1 - s2;
                s2 = s1;
                s1 = s0;
                used++;
            }
            double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
            return Math.max(0.0, power / Math.max(1, used * used));
        }

        private void createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL,
                            "Behind the Curtain live scan", NotificationManager.IMPORTANCE_LOW);
                    nm.createNotificationChannel(ch);
                }
            }
        }

        private Notification buildNotification(String text) {
            Intent open = new Intent(this, XRayBridge.class);
            int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent openPi = PendingIntent.getActivity(this, 1, open, piFlags);

            Intent stop = new Intent(this, ScanService.class);
            stop.setAction(ACTION_STOP);
            PendingIntent stopPi = PendingIntent.getService(this, 2, stop, piFlags);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            return b.setContentTitle("Behind the Curtain • LIVE")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setOngoing(true)
                    .setContentIntent(openPi)
                    .addAction(new Notification.Action.Builder(null, "STOP", stopPi).build())
                    .build();
        }

        private void stopEverything() {
            audioRunning = false;
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Throwable ignored) {}
                audioRecord.release();
                audioRecord = null;
            }
            audioThread = null;
            if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
            if (projection != null) { projection.stop(); projection = null; }
            if (overlayView != null && windowManager != null) {
                try { windowManager.removeView(overlayView); } catch (Throwable ignored) {}
                overlayView = null;
            }
            if (captureThread != null) {
                captureThread.quitSafely();
                captureThread = null;
                captureHandler = null;
            }
            previousGray = null;
            stopForeground(true);
        }

        @Override public void onDestroy() {
            stopEverything();
            super.onDestroy();
        }

        @Nullable @Override public android.os.IBinder onBind(Intent intent) { return null; }
    }
}