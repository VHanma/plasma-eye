package com.vhanma.liarai;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SurfaceHolder.Callback,
        Camera.PreviewCallback, Camera.FaceDetectionListener {

    private static final int PERMISSION_REQUEST = 40;
    private static final int SPEECH_REQUEST = 41;
    private static final int EXPORT_REQUEST = 42;
    private static final int SAMPLE_RATE = 16000;
    private static final String LOG_FILE = "liar_ai_log.jsonl";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private SurfaceView preview;
    private Camera camera;
    private int cameraId = -1;
    private int previewWidth = 0;
    private int previewHeight = 0;
    private int[] previousLuma;
    private volatile double visualMotion = 0.0;
    private volatile double faceJitter = 0.0;
    private volatile boolean facePresent = false;
    private double lastFaceX = Double.NaN;
    private double lastFaceY = Double.NaN;

    private AudioRecord recorder;
    private Thread audioThread;
    private volatile boolean audioRunning = false;
    private volatile double audioDb = -60.0;
    private volatile double audioZcr = 0.0;
    private volatile double audioPitch = 0.0;

    private RunningStat baseMotion = new RunningStat();
    private RunningStat baseJitter = new RunningStat();
    private RunningStat baseDb = new RunningStat();
    private RunningStat baseZcr = new RunningStat();
    private RunningStat basePitch = new RunningStat();
    private volatile boolean calibrating = false;
    private volatile boolean baselineReady = false;
    private long calibrationEnd = 0L;

    private TextView statusText;
    private TextView resultText;
    private TextView evidenceText;
    private TextView liveText;
    private TextView visionScoreText;
    private TextView audioScoreText;
    private TextView textScoreText;
    private TextView fusionScoreText;
    private ProgressBar visionBar;
    private ProgressBar audioBar;
    private ProgressBar textBar;
    private ProgressBar fusionBar;
    private EditText transcriptInput;
    private Button calibrateButton;

    private double lastVisionScore = 0.5;
    private double lastAudioScore = 0.5;
    private double lastTextScore = 0.5;
    private double lastFusionScore = 0.5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(5, 8, 11));
        getWindow().setNavigationBarColor(Color.rgb(5, 8, 11));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        requestNeededPermissions();
        ui.post(updateLoop);
    }

    private void buildUi() {
        int bg = Color.rgb(5, 8, 11);
        int panel = Color.rgb(14, 21, 27);
        int mint = Color.rgb(0, 245, 212);
        int pink = Color.rgb(255, 59, 107);
        int soft = Color.rgb(178, 196, 205);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(18), dp(14), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("LIAR AI", 29, mint, true);
        title.setLetterSpacing(0.12f);
        root.addView(title);

        TextView subtitle = text("MULTI-MODAL DECEPTION CUE ANALYZER", 12, soft, true);
        subtitle.setLetterSpacing(0.08f);
        addBottom(root, subtitle, 12);

        statusText = text("Camera + microphone waiting for permission", 13, soft, false);
        statusText.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusText.setBackground(roundRect(panel, mint, 1, 12));
        addBottom(root, statusText, 12);

        FrameLayout cameraFrame = new FrameLayout(this);
        cameraFrame.setBackground(roundRect(Color.BLACK, Color.rgb(42, 68, 74), 1, 14));
        preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        cameraFrame.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(250)));

        TextView camTag = text("VISION STREAM", 11, mint, true);
        camTag.setPadding(dp(8), dp(5), dp(8), dp(5));
        camTag.setBackground(roundRect(Color.argb(210, 5, 8, 11), mint, 1, 8));
        FrameLayout.LayoutParams tagLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.gravity = Gravity.TOP | Gravity.START;
        tagLp.setMargins(dp(10), dp(10), 0, 0);
        cameraFrame.addView(camTag, tagLp);
        addBottom(root, cameraFrame, 12);

        calibrateButton = button("CALIBRATE BASELINE 10s", mint);
        calibrateButton.setOnClickListener(v -> startCalibration());
        addBottom(root, calibrateButton, 10);

        liveText = text("Live: motion --  voice --  face --", 12, soft, false);
        addBottom(root, liveText, 14);

        TextView scoreTitle = text("FUSION MATRIX", 15, Color.WHITE, true);
        addBottom(root, scoreTitle, 8);

        visionScoreText = scoreLabel("VISION", mint);
        visionBar = scoreBar(mint);
        root.addView(visionScoreText);
        addBottom(root, visionBar, 8);

        audioScoreText = scoreLabel("AUDIO", mint);
        audioBar = scoreBar(mint);
        root.addView(audioScoreText);
        addBottom(root, audioBar, 8);

        textScoreText = scoreLabel("TEXT", mint);
        textBar = scoreBar(mint);
        root.addView(textScoreText);
        addBottom(root, textBar, 8);

        fusionScoreText = scoreLabel("FUSED", pink);
        fusionBar = scoreBar(pink);
        root.addView(fusionScoreText);
        addBottom(root, fusionBar, 14);

        TextView transcriptTitle = text("TRANSCRIPT / STATEMENT", 13, Color.WHITE, true);
        addBottom(root, transcriptTitle, 6);

        transcriptInput = new EditText(this);
        transcriptInput.setTextColor(Color.WHITE);
        transcriptInput.setHintTextColor(Color.rgb(105, 128, 138));
        transcriptInput.setHint("Type or speak the statement here...");
        transcriptInput.setTextSize(15);
        transcriptInput.setMinLines(4);
        transcriptInput.setGravity(Gravity.TOP | Gravity.START);
        transcriptInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        transcriptInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        transcriptInput.setBackground(roundRect(panel, Color.rgb(42, 68, 74), 1, 12));
        addBottom(root, transcriptInput, 8);

        LinearLayout textButtons = new LinearLayout(this);
        textButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button speak = button("SPEAK", mint);
        Button analyze = button("ANALYZE + SAVE", pink);
        textButtons.addView(speak, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams analyzeLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        analyzeLp.setMargins(dp(8), 0, 0, 0);
        textButtons.addView(analyze, analyzeLp);
        speak.setOnClickListener(v -> startSpeechInput());
        analyze.setOnClickListener(v -> analyzeAndSave());
        addBottom(root, textButtons, 14);

        resultText = text("RESULT: waiting for analysis", 20, mint, true);
        resultText.setPadding(dp(12), dp(14), dp(12), dp(14));
        resultText.setBackground(roundRect(panel, pink, 1, 12));
        addBottom(root, resultText, 10);

        evidenceText = text("Evidence summary will appear here.", 13, soft, false);
        evidenceText.setPadding(dp(12), dp(12), dp(12), dp(12));
        evidenceText.setBackground(roundRect(panel, Color.rgb(42, 68, 74), 1, 12));
        addBottom(root, evidenceText, 10);

        Button export = button("EXPORT SESSION LOG", Color.rgb(166, 113, 255));
        export.setOnClickListener(v -> exportLog());
        addBottom(root, export, 12);

        TextView footer = text(
                "Blueprint: live vision motion + face jitter, vocal stress features, linguistic cues, late fusion, uncertainty, and local audit logging.",
                11, Color.rgb(114, 137, 147), false);
        root.addView(footer);

        setContentView(scroll);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView scoreLabel(String name, int color) {
        TextView t = text(name + " 50%", 12, color, true);
        t.setGravity(Gravity.END);
        return t;
    }

    private ProgressBar scoreBar(int color) {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(50);
        if (Build.VERSION.SDK_INT >= 21) {
            bar.setProgressTintList(ColorStateList.valueOf(color));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(34, 48, 54)));
        }
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        return bar;
    }

    private Button button(String label, int accent) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(roundRect(Color.rgb(19, 28, 34), accent, 1, 11));
        return b;
    }

    private GradientDrawable roundRect(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private void addBottom(LinearLayout parent, View child, int marginBottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(marginBottomDp));
        parent.addView(child, lp);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            startHardware();
            return;
        }
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (missing.isEmpty()) {
            startHardware();
        } else {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) startHardware();
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void startHardware() {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) startAudio();
        if (hasPermission(Manifest.permission.CAMERA) && preview.getHolder().getSurface().isValid()) {
            openCamera(preview.getHolder());
        }
        if (hasPermission(Manifest.permission.CAMERA) || hasPermission(Manifest.permission.RECORD_AUDIO)) {
            statusText.setText("Sensors live. Calibrate while looking at the camera and speaking normally.");
        } else {
            statusText.setText("Camera/microphone permission is needed for live modes. Text analysis still works.");
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (hasPermission(Manifest.permission.CAMERA)) openCamera(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopCamera();
    }

    private synchronized void openCamera(SurfaceHolder holder) {
        if (camera != null) return;
        try {
            cameraId = findFrontCamera();
            if (cameraId < 0 && Camera.getNumberOfCameras() > 0) cameraId = 0;
            if (cameraId < 0) {
                statusText.setText("No camera detected. Audio + text modes remain active.");
                return;
            }

            camera = Camera.open(cameraId);
            Camera.Parameters p = camera.getParameters();
            Camera.Size chosen = choosePreviewSize(p.getSupportedPreviewSizes());
            if (chosen != null) p.setPreviewSize(chosen.width, chosen.height);
            p.setPreviewFormat(ImageFormat.NV21);
            if (p.getSupportedFocusModes() != null &&
                    p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            camera.setParameters(p);
            Camera.Size actual = camera.getParameters().getPreviewSize();
            previewWidth = actual.width;
            previewHeight = actual.height;
            setCameraDisplayOrientation(cameraId, camera);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.setFaceDetectionListener(this);
            camera.startPreview();
            try {
                if (camera.getParameters().getMaxNumDetectedFaces() > 0) camera.startFaceDetection();
            } catch (RuntimeException ignored) {
            }
        } catch (Exception e) {
            stopCamera();
            statusText.setText("Camera error: " + e.getClass().getSimpleName());
        }
    }

    private int findFrontCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
        }
        return -1;
    }

    private Camera.Size choosePreviewSize(java.util.List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = sizes.get(0);
        int target = 640 * 480;
        int bestDelta = Math.abs(best.width * best.height - target);
        for (Camera.Size s : sizes) {
            int delta = Math.abs(s.width * s.height - target);
            if (delta < bestDelta) {
                best = s;
                bestDelta = delta;
            }
        }
        return best;
    }

    private void setCameraDisplayOrientation(int id, Camera cam) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(id, info);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees;
        switch (rotation) {
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
            default: degrees = 0;
        }
        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        cam.setDisplayOrientation(result);
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (data == null || previewWidth <= 0 || previewHeight <= 0) return;
        int x0 = previewWidth / 5;
        int x1 = previewWidth * 4 / 5;
        int y0 = previewHeight / 5;
        int y1 = previewHeight * 4 / 5;
        int step = 8;
        int countExpected = ((x1 - x0 + step - 1) / step) * ((y1 - y0 + step - 1) / step);
        if (previousLuma == null || previousLuma.length != countExpected) {
            previousLuma = new int[countExpected];
            int c = 0;
            for (int y = y0; y < y1; y += step) {
                int row = y * previewWidth;
                for (int x = x0; x < x1; x += step) {
                    previousLuma[c++] = data[row + x] & 0xFF;
                }
            }
            return;
        }

        long diff = 0;
        int c = 0;
        for (int y = y0; y < y1; y += step) {
            int row = y * previewWidth;
            for (int x = x0; x < x1; x += step) {
                int now = data[row + x] & 0xFF;
                diff += Math.abs(now - previousLuma[c]);
                previousLuma[c] = now;
                c++;
            }
        }
        if (c > 0) visualMotion = (diff / (double) c) / 255.0;
    }

    @Override
    public void onFaceDetection(Camera.Face[] faces, Camera camera) {
        if (faces != null && faces.length > 0) {
            facePresent = true;
            Rect r = faces[0].rect;
            double cx = (r.left + r.right) / 2000.0;
            double cy = (r.top + r.bottom) / 2000.0;
            if (!Double.isNaN(lastFaceX)) {
                faceJitter = Math.min(1.0, Math.hypot(cx - lastFaceX, cy - lastFaceY));
            }
            lastFaceX = cx;
            lastFaceY = cy;
        } else {
            facePresent = false;
            faceJitter *= 0.8;
        }
    }

    private synchronized void stopCamera() {
        if (camera == null) return;
        try { camera.setPreviewCallback(null); } catch (Exception ignored) {}
        try { camera.stopFaceDetection(); } catch (Exception ignored) {}
        try { camera.stopPreview(); } catch (Exception ignored) {}
        try { camera.release(); } catch (Exception ignored) {}
        camera = null;
        previousLuma = null;
        facePresent = false;
    }

    private synchronized void startAudio() {
        if (audioRunning) return;
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return;
        int bufferBytes = Math.max(min * 2, 8192);
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            }
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = null;
                return;
            }
            recorder.startRecording();
            audioRunning = true;
            audioThread = new Thread(this::audioLoop, "LiarAI-Audio");
            audioThread.start();
        } catch (Exception e) {
            audioRunning = false;
            if (recorder != null) {
                try { recorder.release(); } catch (Exception ignored) {}
                recorder = null;
            }
        }
    }

    private void audioLoop() {
        short[] buffer = new short[2048];
        int pitchCounter = 0;
        while (audioRunning && recorder != null) {
            int n;
            try {
                n = recorder.read(buffer, 0, buffer.length);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) continue;

            double sumSq = 0.0;
            int crossings = 0;
            short prev = buffer[0];
            for (int i = 0; i < n; i++) {
                double v = buffer[i] / 32768.0;
                sumSq += v * v;
                if (i > 0 && ((prev < 0 && buffer[i] >= 0) || (prev >= 0 && buffer[i] < 0))) {
                    crossings++;
                }
                prev = buffer[i];
            }
            double rms = Math.sqrt(sumSq / Math.max(1, n));
            audioDb = 20.0 * Math.log10(Math.max(0.000001, rms));
            audioZcr = crossings / (double) Math.max(1, n - 1);

            pitchCounter++;
            if (pitchCounter >= 3) {
                pitchCounter = 0;
                double p = estimatePitch(buffer, n);
                if (p > 0) audioPitch = p;
            }
        }
    }

    private double estimatePitch(short[] x, int n) {
        if (n < 600) return 0.0;
        int minLag = SAMPLE_RATE / 400;
        int maxLag = SAMPLE_RATE / 60;
        maxLag = Math.min(maxLag, n / 2);
        double mean = 0.0;
        for (int i = 0; i < n; i++) mean += x[i];
        mean /= n;

        double best = 0.0;
        int bestLag = 0;
        for (int lag = minLag; lag <= maxLag; lag += 2) {
            double corr = 0.0;
            double a2 = 0.0;
            double b2 = 0.0;
            for (int i = 0; i < n - lag; i += 2) {
                double a = x[i] - mean;
                double b = x[i + lag] - mean;
                corr += a * b;
                a2 += a * a;
                b2 += b * b;
            }
            double norm = corr / Math.sqrt(Math.max(1.0, a2 * b2));
            if (norm > best) {
                best = norm;
                bestLag = lag;
            }
        }
        if (bestLag == 0 || best < 0.25) return 0.0;
        return SAMPLE_RATE / (double) bestLag;
    }

    private synchronized void stopAudio() {
        audioRunning = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (audioThread != null) {
            try { audioThread.join(250); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
    }

    private void startCalibration() {
        if (!hasPermission(Manifest.permission.CAMERA) && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, "Enable camera or microphone permission first.", Toast.LENGTH_SHORT).show();
            return;
        }
        baseMotion = new RunningStat();
        baseJitter = new RunningStat();
        baseDb = new RunningStat();
        baseZcr = new RunningStat();
        basePitch = new RunningStat();
        baselineReady = false;
        calibrating = true;
        calibrationEnd = SystemClock.elapsedRealtime() + 10_000L;
        statusText.setText("Calibrating: stay natural, look toward the camera, and speak normally.");
    }

    private final Runnable updateLoop = new Runnable() {
        @Override
        public void run() {
            try {
                if (calibrating) sampleCalibration();
                refreshScores(false);
                refreshLiveText();
            } catch (Exception ignored) {
            }
            ui.postDelayed(this, 250);
        }
    };

    private void sampleCalibration() {
        if (camera != null) {
            baseMotion.add(visualMotion);
            if (facePresent) baseJitter.add(faceJitter);
        }
        if (audioRunning) {
            baseDb.add(audioDb);
            baseZcr.add(audioZcr);
            if (audioPitch >= 60 && audioPitch <= 400) basePitch.add(audioPitch);
        }

        long left = calibrationEnd - SystemClock.elapsedRealtime();
        if (left > 0) {
            calibrateButton.setText("CALIBRATING  " + (int) Math.ceil(left / 1000.0) + "s");
        } else {
            calibrating = false;
            calibrateButton.setText("RECALIBRATE BASELINE 10s");
            baselineReady = baseMotion.n >= 8 || baseDb.n >= 8;
            statusText.setText(baselineReady
                    ? "Baseline locked. Live deviation scoring is active."
                    : "Baseline was too sparse. Try calibration again with camera/mic active.");
        }
    }

    private void refreshLiveText() {
        String face = facePresent ? "face LOCK" : "face --";
        String pitch = audioPitch > 0 ? String.format(Locale.US, "%.0f Hz", audioPitch) : "--";
        liveText.setText(String.format(Locale.US,
                "Live: motion %.3f   voice %.1f dB / %s   %s",
                visualMotion, audioDb, pitch, face));
    }

    private void refreshScores(boolean forceText) {
        String transcript = transcriptInput == null ? "" : transcriptInput.getText().toString().trim();
        TextResult tr = analyzeText(transcript);
        lastTextScore = tr.score;

        if (baselineReady) {
            lastVisionScore = computeVisionScore();
            lastAudioScore = computeAudioScore();
        } else {
            lastVisionScore = 0.5;
            lastAudioScore = 0.5;
        }

        double wVision = baselineReady && camera != null ? 0.40 : 0.0;
        double wAudio = baselineReady && audioRunning ? 0.35 : 0.0;
        double wText = transcript.length() >= 5 ? (0.15 + 0.10 * tr.confidence) : 0.0;
        double total = wVision + wAudio + wText;
        if (total <= 0.0) {
            lastFusionScore = 0.5;
        } else {
            lastFusionScore = (lastVisionScore * wVision + lastAudioScore * wAudio + lastTextScore * wText) / total;
        }

        setScore(visionBar, visionScoreText, "VISION", lastVisionScore,
                baselineReady && camera != null ? null : "CALIBRATE");
        setScore(audioBar, audioScoreText, "AUDIO", lastAudioScore,
                baselineReady && audioRunning ? null : "CALIBRATE");
        setScore(textBar, textScoreText, "TEXT", lastTextScore,
                transcript.length() >= 5 ? null : "ADD TEXT");
        setScore(fusionBar, fusionScoreText, "FUSED", lastFusionScore,
                total > 0.0 ? null : "WAITING");

        if (forceText) evidenceText.setText(buildEvidence(tr));
    }

    private double computeVisionScore() {
        ArrayList<Double> scores = new ArrayList<>();
        if (baseMotion.n >= 5) scores.add(deviationScore(visualMotion, baseMotion, 0.004));
        if (baseJitter.n >= 3 && facePresent) scores.add(deviationScore(faceJitter, baseJitter, 0.015));
        if (scores.isEmpty()) return 0.5;
        double s = 0;
        for (Double d : scores) s += d;
        return clamp(s / scores.size());
    }

    private double computeAudioScore() {
        ArrayList<Double> scores = new ArrayList<>();
        if (baseDb.n >= 5) scores.add(deviationScore(audioDb, baseDb, 1.5));
        if (baseZcr.n >= 5) scores.add(deviationScore(audioZcr, baseZcr, 0.012));
        if (basePitch.n >= 5 && audioPitch >= 60 && audioPitch <= 400) {
            scores.add(deviationScore(audioPitch, basePitch, 12.0));
        }
        if (scores.isEmpty()) return 0.5;
        double s = 0;
        for (Double d : scores) s += d;
        return clamp(s / scores.size());
    }

    private double deviationScore(double value, RunningStat stat, double floorSd) {
        double sd = Math.max(floorSd, stat.sd());
        double z = Math.abs(value - stat.mean) / sd;
        return clamp(0.18 + 0.17 * z);
    }

    private TextResult analyzeText(String text) {
        if (text == null || text.trim().isEmpty()) return new TextResult(0.5, 0.0, 0, 0);
        String lower = " " + text.toLowerCase(Locale.US).replaceAll("[^a-z0-9' ]", " ") + " ";
        String[] words = lower.trim().split("\\s+");
        int wordCount = words.length;
        String[] cues = {
                " honestly ", " to be honest ", " trust me ", " believe me ", " i swear ",
                " maybe ", " probably ", " basically ", " actually ", " literally ",
                " i think ", " i guess ", " kind of ", " sort of ", " not sure ",
                " don't remember ", " dont remember ", " can't recall ", " cant recall ",
                " as far as i know ", " if i remember ", " i believe "
        };
        int cueCount = 0;
        for (String cue : cues) cueCount += countOccurrences(lower, cue);

        int negations = 0;
        for (String w : words) {
            if (w.equals("no") || w.equals("not") || w.equals("never") || w.equals("nothing") ||
                    w.equals("nobody") || w.equals("nowhere") || w.endsWith("n't")) negations++;
        }

        double cueDensity = cueCount * 100.0 / Math.max(1, wordCount);
        double negDensity = negations * 100.0 / Math.max(1, wordCount);
        double score = 0.23 + Math.min(0.42, cueDensity * 0.075) + Math.min(0.18, negDensity * 0.025);
        if (wordCount < 8) score = 0.45 + (score - 0.45) * 0.35;
        double confidence = clamp(wordCount / 45.0);
        return new TextResult(clamp(score), confidence, cueCount, negations);
    }

    private int countOccurrences(String text, String phrase) {
        int count = 0;
        int from = 0;
        while (true) {
            int i = text.indexOf(phrase, from);
            if (i < 0) break;
            count++;
            from = i + phrase.length();
        }
        return count;
    }

    private void setScore(ProgressBar bar, TextView label, String name, double score, String state) {
        int p = (int) Math.round(clamp(score) * 100.0);
        bar.setProgress(p);
        label.setText(state == null ? name + "  " + p + "%" : name + "  " + state);
    }

    private String buildEvidence(TextResult tr) {
        StringBuilder b = new StringBuilder();
        b.append("Vision: ");
        if (!baselineReady || camera == null) {
            b.append("baseline unavailable");
        } else {
            b.append(String.format(Locale.US, "motion %.3f, face jitter %.3f, score %.0f%%",
                    visualMotion, faceJitter, lastVisionScore * 100));
        }
        b.append("\nAudio: ");
        if (!baselineReady || !audioRunning) {
            b.append("baseline unavailable");
        } else {
            b.append(String.format(Locale.US, "%.1f dB, ZCR %.3f, pitch %.0f Hz, score %.0f%%",
                    audioDb, audioZcr, audioPitch, lastAudioScore * 100));
        }
        b.append("\nText: ").append(tr.cues).append(" cue phrases, ")
                .append(tr.negations).append(" negation markers, score ")
                .append((int) Math.round(lastTextScore * 100)).append("%");
        b.append("\nFusion: ").append((int) Math.round(lastFusionScore * 100)).append("%");
        if (lastFusionScore >= 0.68) b.append(" | converging elevated cues");
        else if (lastFusionScore >= 0.52) b.append(" | elevated but mixed cues");
        else if (lastFusionScore >= 0.40) b.append(" | ambiguous cue pattern");
        else b.append(" | low cue load");
        return b.toString();
    }

    private void analyzeAndSave() {
        refreshScores(true);
        String label;
        if (lastFusionScore >= 0.68) label = "HIGH DECEPTION-CUE LOAD";
        else if (lastFusionScore >= 0.52) label = "ELEVATED DECEPTION CUES";
        else if (lastFusionScore >= 0.40) label = "MIXED / UNCERTAIN";
        else label = "LOW DECEPTION-CUE LOAD";
        resultText.setText("RESULT: " + label + "  " + (int) Math.round(lastFusionScore * 100) + "%");
        saveAnalysis(label);
        Toast.makeText(this, "Analysis saved locally", Toast.LENGTH_SHORT).show();
    }

    private void saveAnalysis(String label) {
        try {
            JSONObject j = new JSONObject();
            j.put("timestamp", DateFormat.format("yyyy-MM-dd HH:mm:ss", new Date()).toString());
            j.put("label", label);
            j.put("fusion", lastFusionScore);
            j.put("vision", lastVisionScore);
            j.put("audio", lastAudioScore);
            j.put("text", lastTextScore);
            j.put("motion", visualMotion);
            j.put("faceJitter", faceJitter);
            j.put("audioDb", audioDb);
            j.put("audioZcr", audioZcr);
            j.put("audioPitch", audioPitch);
            j.put("baselineReady", baselineReady);
            j.put("statement", transcriptInput.getText().toString());
            j.put("evidence", evidenceText.getText().toString());
            FileOutputStream out = openFileOutput(LOG_FILE, MODE_APPEND);
            out.write((j.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private void startSpeechInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak the statement");
        try {
            startActivityForResult(intent, SPEECH_REQUEST);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Speech recognition is not available on this device.", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportLog() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "LiarAI-session-log.jsonl");
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == SPEECH_REQUEST) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) transcriptInput.setText(results.get(0));
        } else if (requestCode == EXPORT_REQUEST && data.getData() != null) {
            try {
                OutputStream out = getContentResolver().openOutputStream(data.getData());
                if (out == null) return;
                FileInputStream in = openFileInput(LOG_FILE);
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                in.close();
                out.close();
                Toast.makeText(this, "Log exported", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "No saved analyses yet", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preview != null) startHardware();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCamera();
        stopAudio();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(updateLoop);
        stopCamera();
        stopAudio();
        super.onDestroy();
    }

    private static class RunningStat {
        int n = 0;
        double mean = 0.0;
        double m2 = 0.0;

        void add(double x) {
            if (Double.isNaN(x) || Double.isInfinite(x)) return;
            n++;
            double delta = x - mean;
            mean += delta / n;
            double delta2 = x - mean;
            m2 += delta * delta2;
        }

        double sd() {
            return n > 1 ? Math.sqrt(Math.max(0.0, m2 / (n - 1))) : 0.0;
        }
    }

    private static class TextResult {
        final double score;
        final double confidence;
        final int cues;
        final int negations;

        TextResult(double score, double confidence, int cues, int negations) {
            this.score = score;
            this.confidence = confidence;
            this.cues = cues;
            this.negations = negations;
        }
    }
}
